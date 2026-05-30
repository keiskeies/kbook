package com.kbook.test;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.entity.BookSuggestedQuestion;
import com.kbook.repository.BookRepository;
import com.kbook.repository.BookSuggestedQuestionRepository;
import com.kbook.service.AiProviderConfigService;
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
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("test")
public class BookQuestionGenTest {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AiProviderConfigService aiProviderConfigService;

    @Autowired
    private BookSuggestedQuestionRepository suggestedQuestionRepository;

    // 创建线程池用于超时控制
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    
    // AI调用超时时间（分钟）
    private static final int AI_TIMEOUT_MINUTES = 5;

    private static final String IGNORE_TAGS = "长篇小说、短篇小说、推理、悬疑、科幻、奇幻、武侠、言情、官场、穿越、重生、仙侠、修真、玄幻、都市、青春校园、乡土、历史小说、军事小说、谍战、惊悚、恐怖、灵异、轻小说、浪漫、史诗、悲剧、喜剧、意识流、黑色幽默、讽刺、现实主义、魔幻现实主义、传记、回忆录、纪实、随笔、散文、诗歌、书信、寓言、童话、神话、传说、民间故事、绘本、漫画、连环画、戏剧、戏曲、网络小说、同人、种田、后宫、系统";
    @Autowired
    private ChatModelFactory chatModelFactory;

    @Test
    public void generateQuestionsForAllBooks() {
        long methodStartTime = System.currentTimeMillis(); // 记录方法开始时间
        ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAllByOrderByRatingDesc();
        System.out.println("共 " + allBooks.size() + " 本图书需要生成预设问题");

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        int totalAiCalls = 0;
        long totalElapsed = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        for (int i = 0; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);


                    
//            // 检查图书标签是否包含需要忽略的标签
//            if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
//                String[] ignoreTagsArray = IGNORE_TAGS.split("、");
//                boolean shouldSkip = false;
//                for (String ignoreTag : ignoreTagsArray) {
//                    if (book.getFormatTags().contains(ignoreTag.trim())) {
//                        shouldSkip = true;
//                        break;
//                    }
//                }
//                if (shouldSkip) {
//                    skipCount++;
//                    System.out.println("  图书包含忽略标签，跳过: " + book.getTitle());
//                    continue;
//                }
//            }

            System.out.println("\n处理第 " + (i + 1) + "/" + allBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());
            
            // 计算已用时间和预计剩余时间
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - methodStartTime; // 已用时间（毫秒）
            
            // 如果已经处理了一些书，可以估算剩余时间
            String timeInfo = "";
            if (i > 0) {
                double avgTimePerBook = (double) elapsedTime / i; // 平均每本书处理时间
                long remainingBooks = allBooks.size() - i; // 剩余书籍数量
                long estimatedRemainingTime = (long) (avgTimePerBook * remainingBooks); // 预计剩余时间（毫秒）
                
                // 格式化时间显示
                String elapsedFormatted = formatDuration(elapsedTime);
                String remainingFormatted = formatDuration(estimatedRemainingTime);
                
                timeInfo = String.format(" | 已用时: %s, 预计剩余: %s", elapsedFormatted, remainingFormatted);
            }
            
            System.out.println("  [进度] " + String.format("%.1f%%", (i * 100.0 / allBooks.size())) + timeInfo);

            // 检查是否已有预设问题（使用count查询提高性能）
            Long existingCount = suggestedQuestionRepository.countByBookId(book.getId());
            if (existingCount != null && existingCount > 0L) {
                skipCount++;
                System.out.println("  已有 " + existingCount + " 个预设问题，跳过");
                continue;
            }

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
                           - 不要出现“问题1”“以下是……问题”等前缀
                        
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
            
                System.out.println("  开始调用AI生成问题...");
                long startTime = System.currentTimeMillis();
                            
                // 使用Future实现超时控制
                Future<ChatResponse> future = executor.submit(() -> chatModel.chat(List.of(UserMessage.from(prompt))));
                ChatResponse response;
                try {
                    response = future.get(AI_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new RuntimeException("AI调用超时（超过" + AI_TIMEOUT_MINUTES + "分钟）", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("AI调用被中断", e);
                } catch (ExecutionException e) {
                    throw new RuntimeException("AI调用执行异常", e.getCause());
                }
                            
                long elapsed = System.currentTimeMillis() - startTime;

                String aiText = response.aiMessage().text();

                int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                        ? response.tokenUsage().inputTokenCount() : 0;
                int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                        ? response.tokenUsage().outputTokenCount() : 0;

                System.out.println("  AI 原始返回:");
                System.out.println("  " + aiText);
                System.out.println("  ---------- AI 调用日志 ----------");
                System.out.println("  耗时: " + elapsed + "ms");
                System.out.println("  API token: 输入=" + apiInputTokens + ", 输出=" + apiOutputTokens + ", 总=" + (apiInputTokens + apiOutputTokens));
                System.out.println("  --------------------------------");

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

                        System.out.println("  生成并保存 " + toSave.size() + " 个预设问题:");
                        for (int j = 0; j < toSave.size(); j++) {
                            System.out.println("    " + (j + 1) + ". " + toSave.get(j).getQuestion());
                        }

                        successCount++;
                        totalAiCalls++;
                        totalElapsed += elapsed;
                        totalInputTokens += apiInputTokens;
                        totalOutputTokens += apiOutputTokens;

                        CommonUtils.logAiCall("生成预设问题", elapsed, apiInputTokens, apiOutputTokens,
                                "[" + book.getId() + "] " + book.getTitle() + " | 生成 " + toSave.size() + " 个问题");
                    } else {
                        failCount++;
                        System.err.println("  ✗ AI 返回内容无法解析为问题列表");
                    }
                } else {
                    failCount++;
                    System.err.println("  ✗ AI 返回内容为空");
                }

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n========== 全部处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功生成: " + successCount);
        System.out.println("已有跳过: " + skipCount);
        System.out.println("失败: " + failCount);
        if (totalAiCalls > 0) {
            System.out.println("AI 调用统计: " + totalAiCalls + " 次, 总耗时 " + totalElapsed + "ms, "
                    + "平均 " + (totalElapsed / totalAiCalls) + "ms/次, "
                    + "总输入 " + totalInputTokens + " tokens, 总输出 " + totalOutputTokens + " tokens");
        }
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
    
    /**
     * 将毫秒数格式化为易读的时间字符串
     * @param millis 毫秒数
     * @return 格式化后的时间字符串，如 "2小时30分钟45秒"
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        seconds %= 60;
        minutes %= 60;
        
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0 || hours > 0) {
            sb.append(minutes).append("分钟");
        }
        sb.append(seconds).append("秒");
        
        return sb.toString();
    }
}
