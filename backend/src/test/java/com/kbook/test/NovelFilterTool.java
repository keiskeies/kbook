package com.kbook.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest
@ActiveProfiles("test")
public class NovelFilterTool {

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Path INPUT_DIR = Paths.get("G:\\图书\\txt");
    private static final Path OUTPUT_DIR = Paths.get("G:\\图书\\txt1");
    private static final int READ_BYTES = 262144;
    private static final int SAMPLE_CHARS = 6000;
    private static final int THREAD_COUNT = 16;

    private static final Charset[] CANDIDATE_CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("GB18030"),
            Charset.forName("BIG5"),
            StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16BE
    };

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "(第[一二三四五六七八九十百千0-9]+[章节回部集])|(Chapter\\s*\\d+)|(\\d+\\s*/\\s*\\d+\\s*[章节])",
            Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一个专业的图书分类器。根据提供的文本片段，判断它是否属于"小说"（虚构类叙事作品）。

            ## 小说（返回 isNovel=true）的明确标志：
            1. 有明确的人物角色，出现人物姓名、人称（我、他、她）
            2. 包含对话（引号内的交谈内容）
            3. 有情节推进、场景描写、动作描写、心理描写
            4. 叙事性强，有故事发展的脉络
            5. 网络小说特征：修仙/玄幻/穿越/重生/系统/魔法/异能/宫斗/霸总等
            6. 传统小说特征：章回体、话本、演义、传奇等
            7. 有章节标题如"第一章 穿越异世"、"第1章 重生"等

            ## 非小说（返回 isNovel=false）的明确标志：
            1. 技术文档：代码片段、API说明、命令行操作、配置项
            2. 学术文本：摘要/关键词/参考文献/公式/图表/引用标注
            3. 教材讲义：知识点列表、习题、思考题、学习目标
            4. 工具书：目录结构体系化、词条式排列、索引
            5. 法律文书：条款编号、法律法规名称、章程条例
            6. 史料方志：年号、地点志、统计数据
            7. 纯诗歌集/散文集（没有故事主线）
            8. 目录/索引/清单类内容

            ## 判断原则：
            - 不确定时倾向于判为 isNovel=true（宁放过不误杀）
            - 只看文本本身的内容特征，不要被章节标题误导（教材也有"第一章"）
            - 文本内容同时包含小说和非小说特征时，只要明显具备叙事性就判为小说

            只返回 JSON：{"isNovel": true} 或 {"isNovel": false}
            """;

    @Test
    public void filterNonNovels() throws Exception {
        if (!Files.isDirectory(INPUT_DIR)) {
            System.err.println("输入目录不存在: " + INPUT_DIR);
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        List<Path> txtFiles = new ArrayList<>();
        Files.walkFileTree(INPUT_DIR, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".txt")) {
                    txtFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (txtFiles.isEmpty()) {
            System.out.println("没有找到txt文件");
            return;
        }

        System.out.printf("找到 %d 个txt文件，开始AI分类...%n%n", txtFiles.size());

        long totalStart = System.currentTimeMillis();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger novelCount = new AtomicInteger(0);
        AtomicInteger nonNovelCount = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(txtFiles.size());

        for (Path file : txtFiles) {
            executor.submit(() -> {
                long start = System.currentTimeMillis();
                try {
                    boolean isNonNovel = classifyAndCopy(file);
                    long elapsed = System.currentTimeMillis() - start;
                    if (isNonNovel) {
                        nonNovelCount.incrementAndGet();
                        System.out.printf("  ✓ [非小说] %s (%.1fs)%n", INPUT_DIR.relativize(file), elapsed / 1000.0);
                    } else {
                        novelCount.incrementAndGet();
                    }
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.err.printf("  ✗ %s - %s%n", INPUT_DIR.relativize(file), e.getMessage());
                } finally {
                    completed.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        Thread progress = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                int done = completed.get();
                if (done >= txtFiles.size()) break;
                long elapsed = System.currentTimeMillis() - totalStart;
                int processed = success.get() + failed.get();
                double avg = processed > 0 ? (double) elapsed / processed : 0;
                int remaining = txtFiles.size() - done;
                double estMin = remaining * avg / 60000.0;
                System.out.printf("[进度] %d/%d | 小说=%d 非小说=%d 失败=%d | 预计剩余=%.1f分%n",
                        done, txtFiles.size(), novelCount.get(), nonNovelCount.get(), failed.get(), estMin);
            }
        });
        progress.setDaemon(true);
        progress.start();

        latch.await();
        executor.shutdown();
        progress.interrupt();

        long totalMs = System.currentTimeMillis() - totalStart;
        System.out.printf("%n========== 筛选完成 ==========%n");
        System.out.printf("总数: %d, 小说: %d, 非小说(已复制到%s): %d, 失败: %d, 耗时: %.1f分%n",
                txtFiles.size(), novelCount.get(), OUTPUT_DIR, nonNovelCount.get(), failed.get(), totalMs / 60000.0);
    }

    private boolean classifyAndCopy(Path file) throws Exception {
        String text = readFileText(file);
        if (text == null || text.isBlank() || text.length() < 50) {
            return false;
        }

        String sample = extractSample(text);
        if (sample.length() < 50) {
            sample = text.length() > SAMPLE_CHARS ? text.substring(0, SAMPLE_CHARS) : text;
        }

        ChatModel model = chatModelFactory.buildChatModelWithoutThinkingFromYml();
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from("以下是文件内容，请判断是否为小说：\n\n" + sample)
        ));

        String aiText = response.aiMessage().text();
        if (aiText == null || aiText.isBlank()) {
            throw new RuntimeException("AI返回为空");
        }

        String json = aiText.trim();
        if (json.contains("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }

        boolean isNovel = objectMapper.readTree(json).path("isNovel").asBoolean(true);

        if (!isNovel) {
            Path rel = INPUT_DIR.relativize(file);
            Path target = OUTPUT_DIR.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        return false;
    }

    private String extractSample(String text) {
        int textLen = text.length();
        int sampleLen = Math.min(textLen, SAMPLE_CHARS);

        Matcher m = CHAPTER_PATTERN.matcher(text);
        int startPos = 0;
        if (m.find()) {
            startPos = m.start();
            if (startPos > textLen / 2) {
                startPos = 0;
            }
        }

        StringBuilder sb = new StringBuilder(sampleLen + 200);
        int remaining = sampleLen;
        int pos = startPos;

        while (remaining > 0 && pos < textLen) {
            int end = Math.min(pos + remaining, textLen);
            sb.append(text, pos, end);
            remaining -= (end - pos);

            if (remaining > 200 && pos + (sampleLen / 2) < textLen) {
                pos = Math.min(pos + (textLen / 3), textLen - 1);
            } else {
                break;
            }
        }

        String result = sb.toString();
        return result.length() > SAMPLE_CHARS ? result.substring(0, SAMPLE_CHARS) : result;
    }

    private String readFileText(Path file) throws IOException {
        long fileSize = Files.size(file);
        if (fileSize == 0) return "";

        int readSize = (int) Math.min(fileSize, Math.max(READ_BYTES, SAMPLE_CHARS * 4));

        byte[] rawBytes;
        try (InputStream is = Files.newInputStream(file)) {
            rawBytes = is.readNBytes(readSize);
        }

        for (Charset charset : CANDIDATE_CHARSETS) {
            String result = tryDecode(rawBytes, charset);
            if (result != null) return result;
        }

        return new String(rawBytes, StandardCharsets.UTF_8);
    }

    private String tryDecode(byte[] bytes, Charset charset) {
        try {
            String decoded = new String(bytes, charset);
            byte[] reEncoded = decoded.getBytes(charset);
            if (Arrays.equals(bytes, reEncoded)) return decoded;
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
                byte[] noBom = new byte[bytes.length - 3];
                System.arraycopy(bytes, 3, noBom, 0, noBom.length);
                if (Arrays.equals(noBom, reEncoded)) return decoded;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
