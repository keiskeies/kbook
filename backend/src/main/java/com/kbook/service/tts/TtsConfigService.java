package com.kbook.service.tts;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.entity.TtsConfig;
import com.kbook.repository.TtsConfigRepository;
import com.kbook.service.tts.TtsCache;
import com.kbook.service.tts.TtsEngine;
import com.kbook.service.tts.TtsEngineFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * TTS配置管理服务类
 * 提供TTS（文本转语音）配置的增删改查、切换默认配置、语音合成等功能
 * 支持普通合成和流式合成，集成缓存机制提高性能
 */
@Slf4j
@Service
@LogModule("语音合成")
public class TtsConfigService {

    @Autowired
    private TtsConfigRepository ttsConfigRepository;
    @Autowired
    private TtsEngineFactory ttsEngineFactory;
    @Autowired
    private TtsCache ttsCache;

    /**
     * 获取所有TTS配置列表
     * 按默认配置优先、更新时间倒序排列
     * @return 配置列表
     */
    @LogAction("获取TTS配置列表")
    public List<TtsConfig> listAll() {
        return ttsConfigRepository.findByOrderByIsDefaultDescUpdatedAtDesc();
    }

    /**
     * 获取当前活跃的TTS配置
     * 返回默认且启用的配置，如果没有则返回null
     * @return 活跃配置或null
     */
    @LogAction("获取活跃TTS配置")
    public TtsConfig getActiveConfig() {
        return ttsConfigRepository.findByIsDefaultTrueAndEnabledTrue().orElse(null);
    }

    /**
     * 创建新的TTS配置
     * 如果设置为默认配置，会先取消其他配置的默认状态
     * @param config 配置信息
     * @return 保存后的配置
     */
    @Transactional
    @LogAction("创建TTS配置")
    public TtsConfig create(TtsConfig config) {
        if (config.getIsDefault() == null) {
            config.setIsDefault(false);
        }
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            ttsConfigRepository.clearDefaultForOthers(-1L);
        }
        TtsConfig saved = ttsConfigRepository.save(config);
        log.info("TTS config created: id={}, name={}, type={}, provider={}",
                saved.getId(), saved.getName(), saved.getTtsType(), saved.getProvider());
        return saved;
    }

    /**
     * 更新TTS配置
     * 支持部分字段更新，如果设置为默认配置会取消其他默认配置
     * @param id 配置ID
     * @param config 更新的配置信息
     * @return 更新后的配置
     */
    @Transactional
    @LogAction("更新TTS配置")
    public TtsConfig update(Long id, TtsConfig config) {
        TtsConfig existing = ttsConfigRepository.findOneById(id);
        if (existing == null) {
            throw new RuntimeException("TTS 配置不存在");
        }
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getTtsType() != null) existing.setTtsType(config.getTtsType());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getBaseUrl() != null) existing.setBaseUrl(config.getBaseUrl());
        if (config.getModelName() != null) existing.setModelName(config.getModelName());
        if (config.getApiKey() != null) existing.setApiKey(config.getApiKey());
        if (config.getApiSecret() != null) existing.setApiSecret(config.getApiSecret());
        if (config.getAppId() != null) existing.setAppId(config.getAppId());
        if (config.getVoice() != null) existing.setVoice(config.getVoice());
        if (config.getVoicePresetId() != null) existing.setVoicePresetId(config.getVoicePresetId());
        if (config.getLanguage() != null) existing.setLanguage(config.getLanguage());
        if (config.getSpeed() != null) existing.setSpeed(config.getSpeed());
        if (config.getPitch() != null) existing.setPitch(config.getPitch());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getStreaming() != null) existing.setStreaming(config.getStreaming());
        if (Boolean.TRUE.equals(config.getIsDefault()) && !existing.getIsDefault()) {
            ttsConfigRepository.clearDefaultForOthers(id);
            existing.setIsDefault(true);
        }
        TtsConfig saved = ttsConfigRepository.save(existing);
        log.info("TTS config updated: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * 删除TTS配置
     * @param id 配置ID
     */
    @Transactional
    @LogAction("删除TTS配置")
    public void delete(Long id) {
        ttsConfigRepository.deleteById(id);
        log.info("TTS config deleted: id={}", id);
    }

    /**
     * 切换默认TTS配置
     * 将指定配置设为默认，同时取消其他配置的默认状态
     * @param id 配置ID
     * @return 更新后的配置
     */
    @Transactional
    @LogAction("切换默认TTS配置")
    public TtsConfig switchDefault(Long id) {
        TtsConfig config = ttsConfigRepository.findOneById(id);
        if (config == null) {
            throw new RuntimeException("TTS 配置不存在");
        }
        ttsConfigRepository.clearDefaultForOthers(id);
        config.setIsDefault(true);
        TtsConfig saved = ttsConfigRepository.save(config);
        log.info("TTS default switched: id={}, name={}", id, saved.getName());
        return saved;
    }

    /**
     * 执行普通语音合成
     * 优先从缓存获取，缓存未命中则调用引擎合成并缓存结果
     * @param text 待合成文本
     * @param configId 配置ID，为null时使用默认配置
     * @return 音频字节数组
     */
    @LogAction("语音合成")
    public byte[] synthesize(String text, Long configId) {
        TtsConfig config = resolveConfig(configId);

        byte[] cached = ttsCache.get(config.getId(), text);
        if (cached != null) {
            log.info("TTS cache hit: configId={}, textLength={}", config.getId(), text.length());
            return cached;
        }

        byte[] audio = ttsEngineFactory.getEngine(config).synthesize(text, config);
        ttsCache.put(config.getId(), text, audio);
        return audio;
    }

    @LogAction("检查流式合成支持")
    public boolean supportsStreaming(Long configId) {
        TtsConfig config = resolveConfig(configId);
        return Boolean.TRUE.equals(config.getStreaming()) && ttsEngineFactory.getEngine(config).supportsStreaming(config);
    }

    @LogAction("流式语音合成")
    public void synthesizeStream(String text, Long configId, SseEmitter emitter) {
        TtsConfig config = resolveConfig(configId);
        TtsEngine engine = ttsEngineFactory.getEngine(config);

        byte[] cached = ttsCache.get(config.getId(), text);
        if (cached != null) {
            log.info("TTS stream cache hit: configId={}, textLength={}", config.getId(), text.length());
            try {
                int offset = 0;
                int chunkSize = 8192;
                while (offset < cached.length) {
                    int end = Math.min(offset + chunkSize, cached.length);
                    byte[] chunk = new byte[end - offset];
                    System.arraycopy(cached, offset, chunk, 0, chunk.length);
                    String base64 = Base64.getEncoder().encodeToString(chunk);
                    emitter.send(SseEmitter.event().name("audio").data(base64));
                    offset = end;
                }
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (IOException e) {
                log.debug("SSE send cached audio failed: {}", e.getMessage());
            }
            return;
        }

        java.io.ByteArrayOutputStream cacheBuffer = new java.io.ByteArrayOutputStream();

        engine.synthesizeStream(text, config,
                (pcmBytes) -> {
                    try {
                        cacheBuffer.write(pcmBytes);
                        String base64 = Base64.getEncoder().encodeToString(pcmBytes);
                        emitter.send(SseEmitter.event().name("audio").data(base64));
                    } catch (IOException e) {
                        log.debug("SSE send audio chunk failed (client disconnected): {}", e.getMessage());
                    }
                },
                () -> {
                    try {
                        byte[] fullAudio = cacheBuffer.toByteArray();
                        if (fullAudio.length > 0) {
                            ttsCache.put(config.getId(), text, fullAudio);
                        }
                        emitter.send(SseEmitter.event().name("done").data(""));
                        emitter.complete();
                    } catch (IOException e) {
                        log.debug("SSE send done failed (client disconnected): {}", e.getMessage());
                    }
                }
        );
    }

    private TtsConfig resolveConfig(Long configId) {
        TtsConfig config;
        if (configId != null) {
            config = ttsConfigRepository.findOneById(configId);
            if (config == null) {
                throw new RuntimeException("TTS 配置不存在");
            }
        } else {
            config = getActiveConfig();
            if (config == null) {
                throw new RuntimeException("未找到启用的 TTS 配置");
            }
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new RuntimeException("TTS 配置未启用");
        }
        return config;
    }
}
