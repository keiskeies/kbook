package com.kbook.test;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import com.kbook.service.ai.AiProviderConfigService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("dev")
public class BookQuestionGenTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AiProviderConfigService aiProviderConfigService;

    @Autowired
    private BookSuggestedQuestionRepository suggestedQuestionRepository;

    private static final int AI_TIMEOUT_MINUTES = 5;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Test
    public void generateQuestionsForAllBooks() {
        ChatModel chatModel = chatModelFactory.buildToolChatModel();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> books = bookRepository.findBooksWithoutQuestions();
        System.out.println("========================================");
        System.out.println("预设问题生成工具");
        System.out.println("========================================");
        System.out.println("缺少预设问题的图书: " + books.size() + " 本");
        System.out.println("----------------------------------------");

        if (books.isEmpty()) {
            System.out.println("所有图书均已有预设问题，无需处理。");
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);
        long totalStartTime = System.currentTimeMillis();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(books.size());

        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                int ok = successCount.get();
                int fail = failCount.get();
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                double elapsedMin = elapsedMs / 60000.0;
                int processed = ok + fail;
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = books.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                java.time.LocalDateTime finishTime = java.time.LocalDateTime.now().plusSeconds((long)(estMin * 60));
                System.out.printf("[进度] %d/%d (%.1f%%) | 成功=%d 失败=%d | 已用=%.1f分钟 | 预计剩余=%.1f分钟 | 预计完成=%s%n",
                        done, books.size(), done * 100.0 / books.size(),
                        ok, fail, elapsedMin, estMin,
                        finishTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (int i = 0; i < books.size(); i++) {
            final int index = i;
            final Book book = books.get(i);

            executor.submit(() -> {
                try {
                    String prompt = String.format(
                            """
                            你是一位资深阅读引导专家。请根据提供的图书信息，生成20个可以向AI深入探讨本书的问题。
                            
                            要求：
                            1. 问题角度多样化且均衡，必须覆盖以下五个维度，每个维度至少3个问题：
                               - 核心命题与深层思辨：书籍究竟想回答什么问题？其背后的思想根基或理论野心何在？
                               - 关键节点与论证骨架：若是虚构叙事，指向情节冲突、转折与决定性场景；若是非虚构，指向核心观点、关键论据、实验或逻辑转折点。
                               - 核心行动元与视角演变：虚构作品中主要人物的欲望、选择与成长；非虚构中则指研究对象的特质、作者立场的变化，或书中关键思想家、案例主体的行动逻辑。
                               - 表达技艺与结构设计：叙事视角、语言质感、章节编排的用意；对非虚构而言，还包括论证策略、跨学科方法、数据呈现方式等。
                               - 现实投射与个人映照：书中的洞见如何照进我们的日常生活、社会议题或个体决策，能与读者产生何种私人联结。
                            2. 仔细阅读【简介】【目录】和【摘要】，每个问题必须明确指向某个章节标题、简介中的具体概念，或摘要里出现的专有名词/事件，避免空泛笼统。
                            3. 问题采用读者在深度阅读后自然发生的口吻，带有探索、质疑或联想意味，仿佛在参加一场高质量的对谈。即便讨论理论，也保持对话感和好奇心，拒绝考试式发问。
                            4. 问题应体现【标签】所暗示的领域气质（如哲学则重概念辨析，科技则重机制与影响，历史则重史料与叙事），但严禁直接堆砌标签词语。
                            5. 严格遵守输出格式：
                               - 只输出20个问题本身
                               - 每个问题不超过50个汉字
                               - 一行一个问题
                               - 不加任何序号、项目符号、空行或解释性文字
                               - 不要出现"问题1""以下是……问题"等前缀
                            
                            图书信息：
                            书名：《%s》
                            作者：%s
                            标签：%s
                            简介：%s
                            目录：%s
                            摘要: %s
                            
                            """,
                            book.getTitle(),
                            book.getAuthor() != null ? book.getAuthor() : "未知",
                            book.getFormatTags() != null ? book.getFormatTags() : "暂无标签",
                            book.getDescription() != null ? book.getDescription() : "暂无简介",
                            book.getToc() != null ? book.getToc() : "暂无目录",
                            book.getChapterSummary() != null ? book.getChapterSummary() : "暂无摘要"
                    );

                    long startTime = System.currentTimeMillis();

                    ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));

                    long elapsed = System.currentTimeMillis() - startTime;
                    String aiText = response.aiMessage().text();

                    int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                            ? response.tokenUsage().inputTokenCount() : 0;
                    int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                            ? response.tokenUsage().outputTokenCount() : 0;

                    totalInputTokens.addAndGet(apiInputTokens);
                    totalOutputTokens.addAndGet(apiOutputTokens);

                    if (aiText != null && !aiText.isBlank()) {
                        List<String> generated = parseQuestions(aiText);
                        if (!generated.isEmpty()) {
                            List<BookSuggestedQuestion> toSave = generated.stream()
                                    .map(q -> {
                                        BookSuggestedQuestion sq = new BookSuggestedQuestion();
                                        sq.setBookId(book.getId());
                                        sq.setQuestion(q);
                                        return sq;
                                    })
                                    .collect(Collectors.toList());
                            suggestedQuestionRepository.saveAll(toSave);

                            successCount.incrementAndGet();
                            System.out.printf("  ✓ [%d] id=%d %s | %d个问题 | %dms | in=%d out=%d%n",
                                    index + 1, book.getId(), book.getTitle(), toSave.size(), elapsed, apiInputTokens, apiOutputTokens);

                            CommonUtils.logAiCall("生成预设问题", elapsed, apiInputTokens, apiOutputTokens,
                                    "[" + book.getId() + "] " + book.getTitle() + " | 生成 " + toSave.size() + " 个问题");
                        } else {
                            failCount.incrementAndGet();
                            System.err.printf("  ✗ [%d] id=%d %s — AI返回无法解析%n", index + 1, book.getId(), book.getTitle());
                        }
                    } else {
                        failCount.incrementAndGet();
                        System.err.printf("  ✗ [%d] id=%d %s — AI返回为空%n", index + 1, book.getId(), book.getTitle());
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.printf("  ✗ [%d] id=%d %s — %s%n", index + 1, book.getId(), book.getTitle(), e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        progressThread.interrupt();

        long totalElapsedMs = System.currentTimeMillis() - totalStartTime;
        double totalElapsedMin = totalElapsedMs / 60000.0;
        int processed = successCount.get() + failCount.get();
        double avgMs = processed > 0 ? (double) totalElapsedMs / processed : 0;

        System.out.println("----------------------------------------");
        System.out.println("总数: " + books.size());
        System.out.println("成功: " + successCount.get());
        System.out.println("失败: " + failCount.get());
        System.out.printf("总耗时: %.2f分钟%n", totalElapsedMin);
        System.out.printf("平均每本: %.2f秒%n", avgMs / 1000.0);
        System.out.println("总输入tokens: " + totalInputTokens.get() + ", 总输出tokens: " + totalOutputTokens.get());
        System.out.println("========================================");
    }

    private List<String> parseQuestions(String text) {
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceAll("^\\d+[.、)\\s]*", "").trim())
                .filter(line -> line.length() > 2)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }
}
