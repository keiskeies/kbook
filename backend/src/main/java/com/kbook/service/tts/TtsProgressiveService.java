package com.kbook.service.tts;

import com.kbook.entity.TtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 渐进式语音播放编排
 * <p>
 * 目标：让「整篇朗读」在浏览器看来就是一个普通媒体文件 —— 单次请求、单一容器、
 * 连续音频。这样锁屏控制、后台播放、系统媒体键、精确进度全部由平台原生能力承担，
 * 不再依赖前端用 JS 串联分片，也就不存在「某一段的结束事件丢失导致整条链路死掉」。
 * <p>
 * 数据流：
 * <ol>
 *   <li>所有片段均已缓存 → 直接整段输出，附带 Content-Length，支持 Range seek</li>
 *   <li>否则边接收引擎音频块边写响应体（首帧 0.4~0.6s 即可出声）</li>
 * </ol>
 * <p>
 * 分层缓存：文本先按句切成若干段，每段独立缓存。多段 MP3 的字节可以无损拼成
 * 一条连续流，因此「分段缓存 + 顺序拼接」既保证了单次上游请求的时长可控，
 * 又让中断后的重试只需补合成缺失的片段，而不是从头再来。
 */
@Slf4j
@Service
public class TtsProgressiveService {

    /** 单次上游合成请求的最大字符数；超过则按句切分，仅对可安全拼接的容器生效 */
    private static final int MAX_SEGMENT_CHARS = 600;

    /** 句末标点：优先在这些字符之后切断 */
    private static final String SENTENCE_ENDS = "。！？!?；;\n";

    /** 次级断点：句内停顿 */
    private static final String CLAUSE_ENDS = "，,、：:";

    private final TtsEngineFactory engineFactory;

    private final TtsCache ttsCache;

    private final TtsConfigService ttsConfigService;

    public TtsProgressiveService(TtsEngineFactory engineFactory,
                                 TtsCache ttsCache,
                                 TtsConfigService ttsConfigService) {
        this.engineFactory = engineFactory;
        this.ttsCache = ttsCache;
        this.ttsConfigService = ttsConfigService;
    }

    /**
     * 渐进式播放的音频容器信息
     *
     * @param contentType HTTP 内容类型
     * @param ext         缓存扩展名
     * @param supported   当前配置是否支持渐进式输出
     */
    public record AudioProfile(String contentType, String ext, boolean supported) {
    }

    /**
     * 解析给定配置的音频容器信息
     *
     * @param configId TTS 配置ID，null 表示当前默认配置
     * @return 容器信息
     */
    public AudioProfile resolveProfile(Long configId) {
        TtsConfig config = ttsConfigService.resolveConfigForStream(configId);
        TtsEngine engine = engineFactory.getEngine(config);
        boolean supported = engine.supportsProgressiveAudio(config);
        String ext = resolveExt(config, engine);
        return new AudioProfile(contentTypeOf(ext), ext, supported);
    }

    /**
     * 按配置解析容器扩展名
     *
     * @param config TTS 配置
     * @param engine 对应引擎
     * @return 扩展名
     */
    public String resolveExt(TtsConfig config, TtsEngine engine) {
        return engine.supportsProgressiveAudio(config) ? engine.audioFormat(config) : TtsCache.EXT_WAV;
    }

    /**
     * 扩展名对应的 HTTP 内容类型
     *
     * @param ext 扩展名
     * @return 内容类型
     */
    public static String contentTypeOf(String ext) {
        return TtsCache.EXT_MP3.equals(ext) ? "audio/mpeg" : "audio/wav";
    }

    /**
     * 补齐全部分段并返回完整音频
     * <p>
     * 供「拖动进度条」这类需要精确定位的场景使用。渐进式流在浏览器侧
     * 只能 seek 到已缓冲位置，用户拖到未缓冲区间时会被静默忽略。此时
     * 由服务端把缺失片段合成完，一次性返回带 Content-Length 的完整文件，
     * 浏览器即可自由跳转到任意位置。
     * <p>
     * 代价是这次请求要等到全部合成完成（首字节延迟较高），但此路径只在
     * 用户主动拖动时触发，且已缓存的分段不会重复合成。
     *
     * @param text     待合成文本
     * @param configId TTS 配置ID
     * @return 完整音频字节
     * @throws IOException 合成或读盘失败
     */
    public byte[] ensureComplete(String text, Long configId) throws IOException {
        TtsConfig config = ttsConfigService.resolveConfigForStream(configId);
        TtsEngine engine = engineFactory.getEngine(config);
        String ext = resolveExt(config, engine);
        List<String> segments = splitForProgressive(text, ext);

        long start = System.currentTimeMillis();
        int cacheHits = 0;
        int synthesized = 0;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        for (String segment : segments) {
            byte[] cached = ttsCache.get(config.getId(), segment, ext);
            if (cached != null) {
                buffer.write(cached, 0, cached.length);
                cacheHits++;
                continue;
            }
            byte[] part = synthesizeSegment(config, engine, segment, ext);
            buffer.write(part, 0, part.length);
            synthesized++;
        }

        log.info("TTS ensureComplete: configId={}, ext={}, textLength={}, segments={}, cacheHits={}, synthesized={}, bytes={}, elapsed={}ms",
                config.getId(), ext, text.length(), segments.size(), cacheHits, synthesized, buffer.size(),
                System.currentTimeMillis() - start);
        return buffer.toByteArray();
    }

    /**
     * 合成单个片段到内存并落盘缓存
     *
     * @return 该片段的音频字节
     */
    private byte[] synthesizeSegment(TtsConfig config, TtsEngine engine, String segment,
                                     String ext) throws IOException {
        Path tmp = ttsCache.createTempFile();
        OutputStream fileOut = openQuietly(tmp);

        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        IOException[] fileError = {null};
        Path[] tmpRef = {tmp};
        OutputStream[] cacheRef = {fileOut};

        try {
            engine.synthesizeProgressiveAudio(segment, config,
                    bytes -> {
                        collected.write(bytes, 0, bytes.length);
                        OutputStream cs = cacheRef[0];
                        if (cs != null && fileError[0] == null) {
                            try {
                                cs.write(bytes);
                            } catch (IOException e) {
                                fileError[0] = e;
                            }
                        }
                    },
                    () -> { }
            );

            // 仅在片段完整产出后才转正缓存。
            // 上游（429、超时、连接中断）中途失败时，已经收到的字节是一段
            // 残缺音频；此时若转正，后续所有请求都会把这个残缺片段当成
            // 「已缓存的完整片段」命中，朗读会在同一位置反复截断且无法自愈。
            if (cacheRef[0] != null && fileError[0] == null) {
                closeQuietly(cacheRef[0]);
                cacheRef[0] = null;
                ttsCache.commit(tmpRef[0], config.getId(), segment, ext);
                tmpRef[0] = null;
            } else if (fileError[0] != null) {
                log.warn("TTS cache write failed during ensureComplete: {}", fileError[0].getMessage());
            }
            return collected.toByteArray();
        } finally {
            closeQuietly(cacheRef[0]);
            if (tmpRef[0] != null) ttsCache.deleteQuietly(tmpRef[0]);
        }
    }

    /**
     * 尝试取得完整音频（全部片段均已缓存时）
     * <p>
     * 供需要 Content-Length 与 Range 支持的调用路径使用。任一片段缺失即返回 null，
     * 调用方应转为渐进式输出。
     *
     * @param text     待合成文本
     * @param configId TTS 配置ID
     * @return 完整音频字节；无法完整取得时返回 null
     */
    public byte[] tryLoadComplete(String text, Long configId) {
        TtsConfig config = ttsConfigService.resolveConfigForStream(configId);
        TtsEngine engine = engineFactory.getEngine(config);
        String ext = resolveExt(config, engine);
        List<String> segments = splitForProgressive(text, ext);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (String segment : segments) {
            byte[] part = ttsCache.get(config.getId(), segment, ext);
            if (part == null) return null;
            buffer.write(part, 0, part.length);
        }
        return buffer.toByteArray();
    }

    /**
     * 把文本的语音写入输出流
     * <p>
     * 调用方负责设置响应头；本方法按片段顺序输出音频字节，并尽力补齐缓存。
     *
     * @param text     待合成文本
     * @param configId TTS 配置ID，null 表示当前默认配置
     * @param out      响应输出流
     * @return 实际写入的音频字节数
     * @throws IOException 客户端断开或写盘失败
     */
    public long streamTo(String text, Long configId, OutputStream out) throws IOException {
        TtsConfig config = ttsConfigService.resolveConfigForStream(configId);
        TtsEngine engine = engineFactory.getEngine(config);
        String ext = resolveExt(config, engine);
        List<String> segments = splitForProgressive(text, ext);

        long start = System.currentTimeMillis();
        long written = 0;
        int cacheHits = 0;
        int synthesized = 0;

        for (String segment : segments) {
            // 片段级缓存：上次中断前已完成的片段无需重新合成
            byte[] cached = ttsCache.get(config.getId(), segment, ext);
            if (cached != null) {
                out.write(cached);
                out.flush();
                written += cached.length;
                cacheHits++;
                continue;
            }

            written += synthesizeSegmentTo(config, engine, segment, ext, out);
            synthesized++;
        }

        log.info("TTS progressive completed: configId={}, ext={}, textLength={}, segments={}, cacheHits={}, synthesized={}, bytes={}, elapsed={}ms",
                config.getId(), ext, text.length(), segments.size(), cacheHits, synthesized, written,
                System.currentTimeMillis() - start);
        return written;
    }

    /**
     * 合成单个片段并写入输出流，同时旁路落盘以便后续复用
     *
     * @return 本片段写入的字节数
     */
    private long synthesizeSegmentTo(TtsConfig config, TtsEngine engine, String segment,
                                     String ext, OutputStream out) throws IOException {
        Path tmp = ttsCache.createTempFile();
        OutputStream fileOut = openQuietly(tmp);

        long[] written = {0};
        int[] frames = {0};
        Path[] tmpRef = {tmp};
        OutputStream[] cacheRef = {fileOut};
        IOException[] fileError = {null};

        try {
            engine.synthesizeProgressiveAudio(segment, config,
                    bytes -> {
                        try {
                            out.write(bytes);
                            written[0] += bytes.length;
                            frames[0]++;
                        } catch (IOException e) {
                            throw new TtsClientGoneException(e);
                        }
                        OutputStream cs = cacheRef[0];
                        if (cs != null && fileError[0] == null) {
                            try {
                                cs.write(bytes);
                            } catch (IOException e) {
                                fileError[0] = e;
                            }
                        }
                    },
                    () -> {
                        try {
                            out.flush();
                        } catch (IOException e) {
                            throw new TtsClientGoneException(e);
                        }
                    }
            );

            // 仅在片段完整产出后转正缓存，避免残缺音频被后续请求当成完整片段命中
            if (cacheRef[0] != null && fileError[0] == null) {
                closeQuietly(cacheRef[0]);
                cacheRef[0] = null;
                ttsCache.commit(tmpRef[0], config.getId(), segment, ext);
                tmpRef[0] = null;
            } else if (fileError[0] != null) {
                log.warn("TTS cache write failed during progressive stream: {}", fileError[0].getMessage());
            }
            return written[0];
        } finally {
            closeQuietly(cacheRef[0]);
            if (tmpRef[0] != null) ttsCache.deleteQuietly(tmpRef[0]);
        }
    }

    /**
     * 打开临时文件输出流，失败时清理临时文件并返回 null
     */
    private OutputStream openQuietly(Path tmp) {
        if (tmp == null) return null;
        try {
            return Files.newOutputStream(tmp);
        } catch (IOException e) {
            log.warn("TTS progressive temp stream open failed: {}", tmp, e);
            ttsCache.deleteQuietly(tmp);
            return null;
        }
    }

    /**
     * 按容器能力切分文本
     * <p>
     * 只有「帧独立可解码且边界对齐」的容器（当前为 MP3）才允许切分拼接；
     * WAV 等带全局头部的容器必须一次性合成，否则拼接结果无法解码。
     *
     * @param text 待合成文本
     * @param ext  容器扩展名
     * @return 片段列表，至少包含一个元素
     */
    List<String> splitForProgressive(String text, String ext) {
        if (!TtsCache.EXT_MP3.equals(ext) || text.length() <= MAX_SEGMENT_CHARS) {
            List<String> single = new ArrayList<>(1);
            single.add(text);
            return single;
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            if (remaining <= MAX_SEGMENT_CHARS) {
                out.add(text.substring(start));
                break;
            }
            int limit = start + MAX_SEGMENT_CHARS;
            int cut = lastIndexOfAny(text, SENTENCE_ENDS, start, limit);
            if (cut < 0) cut = lastIndexOfAny(text, CLAUSE_ENDS, start, limit);
            if (cut < 0) {
                cut = limit;
            } else {
                cut += 1;
            }
            // 防止极端情况（长串无标点）产生空片段
            if (cut <= start) cut = limit;
            out.add(text.substring(start, cut));
            start = cut;
        }
        if (out.isEmpty()) out.add(text);
        return out;
    }

    /**
     * 在 [from, to) 区间内从后往前查找任一目标字符的位置
     */
    private static int lastIndexOfAny(String text, String chars, int from, int to) {
        int end = Math.min(to, text.length()) - 1;
        for (int i = end; i >= from; i--) {
            if (chars.indexOf(text.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static void closeQuietly(OutputStream os) {
        if (os == null) return;
        try {
            os.close();
        } catch (IOException ignored) {
            // 关闭失败不影响主流程
        }
    }
}