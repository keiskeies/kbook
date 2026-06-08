package com.kbook.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.Book;
import com.kbook.entity.Comment;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.CommentRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.book.BookParserService;
import com.kbook.service.recommend.RecommendCoefficientService;
import com.kbook.service.recommend.RecommendMatchCalculator;
import com.kbook.service.tools.DimensionStatsService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * 书籍评论造数工具
 * 为所有用户对书籍生成符合用户画像的AI评论
 */
@SpringBootTest
@ActiveProfiles("test")
//@Transactional // 测试完成后回滚，如需持久化请注释掉此注解
public class CommentDataGeneratorTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private RecommendCoefficientService coefficientService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DimensionStatsService dimensionStatsService;
    // 创建线程池用于超时控制
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final Random random = new Random();

    /**
     * 生成全量评论数据 - 每个用户对每本书生成1条评论
     * 评论内容由AI根据用户画像和书籍信息生成
     */
    @Test
    public void generateAllComments() {
        System.out.println("========== 开始生成书籍评论数据 ==========");

        // 1. 获取所有用户
        List<User> allUsers = userRepository.findAll();
        System.out.println("找到 " + allUsers.size() + " 个用户");

        if (allUsers.isEmpty()) {
            System.out.println("错误：没有找到任何用户数据，请先运行用户数据生成工具！");
            return;
        }

        // 2. 获取所有书籍
        List<Book> allBooks = bookRepository.findAllByOrderByRatingDesc();
        System.out.println("找到 " + allBooks.size() + " 本书籍");

        if (allBooks.isEmpty()) {
            System.out.println("错误：没有找到任何书籍数据！");
            return;
        }

        System.out.println("预计生成评论数: " + (allUsers.size() * allBooks.size()));
        System.out.println("请稍候，AI生成评论可能需要较长时间...\n");

        // 3. 初始化AI模型（使用application.yml中的配置）
        ChatModel chatModel = initChatModel();

        // 4. 生成评论
        List<Comment> commentsToSave = new ArrayList<>();
        int totalGenerated = 0;
        long startTime = System.currentTimeMillis();


        for (int userIdx = 0; userIdx < allUsers.size(); userIdx++) {
            User user = allUsers.get(userIdx);
            if (!user.getEmail().endsWith("@keiskei.top")) {
                continue;
            }
            for (int bookIdx = 0; bookIdx < allBooks.size(); bookIdx++) {
                Book book = allBooks.get(bookIdx);
                if (book.getRating() < 3) {
                    continue;
                }



                double score = RecommendMatchCalculator.calculateMatchScore(user, BookProjection.from(book), coefficientService, objectMapper, dimensionStatsService);
                if (score < 0.85) {
                    continue;
                }

                try {
                    // 检查是否已经评论过
//                    List<Comment> existingComments = commentRepository.findByUserIdAndBookIdAndChapterIdIsNull(user.getId(), book.getId());
//                    if (!existingComments.isEmpty()) {
//                        System.out.println("跳过：用户 " + user.getEmail() + " 已评论过书籍 " + book.getTitle());
//                        continue;
//                    }

                    // 生成AI评论
                    String commentContent = generateCommentByAI(chatModel, user, book);
                    if (null == commentContent) {
                        continue;
                    }
                    System.err.println("生成评论成功: 用户=" + user.getEmail() + ", 书籍=" + book.getTitle() + ", \n内容=" + commentContent);

                    // 创建评论对象
                    Comment comment = Comment.builder()
                            .userId(user.getId())
                            .bookId(book.getId())
                            .chapterId(null) // 书籍级别评论
                            .content(commentContent)
                            .likeCount(0) // 初始点赞数为0
                            .replyCount(0) // 初始回复数为0
                            .favoriteCount(0) // 初始收藏数为0
                            .build();

                    commentsToSave.add(comment);
                    totalGenerated++;

                    // 每50条保存一次
                    if (commentsToSave.size() >= 50) {
                        commentRepository.saveAll(commentsToSave);
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        System.out.printf("进度: 用户 %d/%d, 书籍 %d/%d, 已生成 %d 条评论 (耗时: %d秒)%n",
                                userIdx + 1, allUsers.size(), bookIdx + 1, allBooks.size(),
                                totalGenerated, elapsed);
                        commentsToSave.clear();
                    }

                } catch (Exception e) {
                    System.err.println("生成评论失败: 用户=" + user.getEmail() + ", 书籍=" + book.getTitle() +
                            ", 错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // 保存剩余的评论
        if (!commentsToSave.isEmpty()) {
            commentRepository.saveAll(commentsToSave);
        }

        long endTime = System.currentTimeMillis();
        long totalElapsed = (endTime - startTime) / 1000;

        System.out.println("\n========== 生成完成 ==========");
        System.out.println("共生成 " + totalGenerated + " 条评论");
        System.out.println("总耗时: " + totalElapsed + " 秒 (约" + (totalElapsed / 60) + "分钟)");
        System.out.println("平均速度: " + (totalGenerated / Math.max(totalElapsed, 1)) + " 条/秒");
        System.out.println("==============================\n");
    }

    /**
     * 初始化聊天模型（使用application.yml中的配置）
     */
    private ChatModel initChatModel() {
        System.out.println("正在从配置加载AI模型...");
        // 使用 AiProviderConfigService 获取当前活跃的AI配置
        ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();

        if (chatModel == null) {
            throw new IllegalStateException("未找到可用的AI模型配置，请先在管理后台配置AI模型或检查application.yml中的langchain4j配置");
        }

        System.out.println("AI模型初始化成功");
        return chatModel;
    }

    /**
     * 使用AI生成符合用户画像的评论
     */
    private String generateCommentByAI(ChatModel chatModel, User user, Book book) {
        // 构建用户画像描述
        String userProfile = buildUserProfile(user);

        // 构建书籍信息
        String bookInfo = buildBookInfo(book);

        // 构建提示词
        String prompt = String.format("""
                你是一位风格多变、语感锋利、绝不说废话的书评人。请根据用户画像和书籍信息，创作一条情绪真实、语言新鲜的个人化短评。你的评论必须像来自活生生的读者，每一篇都要有截然不同的口吻和灵魂。
                
                【用户画像】
                %s
                
                【书籍信息】
                %s
                
                【核心法则】
                1. 字数：10到140字。
                2. 情绪与风格强制轮换：你必须从以下风格库中为每一本书选择一个主导基调，且连续几条评论中不得重复使用同一基调。风格库：
                   - 冷眼拆解：指出书中某个被过度吹捧的观点，揭露其天真或过时之处。
                   - 被冒犯的刺痛：书中的某句话让你极度不适或愤怒，但这种不适恰恰是真相。
                   - 黑色幽默：用自嘲、反讽和荒诞的语调，说出书中内容与现实的巨大反差。
                   - 私密共振：不讲道理，只描述一个被书瞬间拉回的具体个人回忆、气味或身体感受。
                   - 狂喜发现：像发现新大陆一样，兴奋但克制地指出一个别人会忽略的神来之笔。
                   - 无情吐槽：直接表达对书的不耐烦、后悔，但槽点必须精准有料。
                   - 幽灵的低语：全书读罢，只留下一个挥之不去的意象或问题，用极简的语言白描出那种萦绕。
                   - 温柔一刺：表面平静、语带慈悲，却在结尾无声处抛出一个让内心无法安宁的疑问。
                   - 肉身战栗：某段描写让你生理上起了反应（起鸡皮疙瘩、胃部收紧、忽然屏住呼吸）。
                   - 迟来的顿悟：多年前读不懂，现在瞬间贯通，带着岁月赋予的苍凉或自省。
                   - 危险的共谋：你发现自己竟在暗处认同书中某个“不正确”的观点，感到不安又兴奋。
                   - 乡愁式抽离：读时如隔着毛玻璃望见已回不去的旧世界，只有一片无法命名的怅然。
                3. 风格匹配原则：严肃书籍优先深度风格（冷眼拆解、肉身战栗、幽灵的低语），轻松题材可用无情吐槽、黑色幽默，但绝不能消解深度。无论选哪种，必须保持真实的个人情绪质感，拒绝任何形式的“标准答案”。
                4. 致命雷区：禁止出现“击中了我”“像是提醒我”“带着一种…的无力感”“真的…”“天哪”“绝了”“强烈安利”等模板句式。禁止将用户身份简化为“迷茫的年轻人”式叙事，必须根据真实画像调用其职业、阅历的特有语言。
                5. 刺点强制：你必须从书中拽出一个**独一无二的刺点**——一个反常的数字、一句争议性对白、一个被忽视的配角、一个细思极恐的细节——并让整个评论围绕它生长，而不是复述简介。
                6. 示例感知（体会刺点与风格的咬合）：
                   书籍《局外人》，风格：肉身战栗
                   → “今天，妈妈死了。”读这第一句时我正在地铁上，空调冷风正对着后颈吹。我忽然意识到，我可能也是自己生活的局外人。没有悲痛，只有那一瞬皮肤收紧的、可怕的诚实。
                7. 输出：只输出10到140字评论正文，无标题、署名题、风格。
                
                请直接输出评论内容,不要给出风格等其他无关内容：
                """, userProfile, bookInfo);

        try {
            // 使用Future实现超时控制
            Future<ChatResponse> future = executor.submit(() -> chatModel.chat(List.of(UserMessage.from(prompt))));
            ChatResponse response;
            try {
                response = future.get(5, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("AI调用超时（超过" + 5 + "分钟）", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI调用被中断", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("AI调用执行异常", e.getCause());
            }
            String comment = response.aiMessage().text();

            // 清理可能的多余内容
            comment = comment.trim();
            if (comment.startsWith("\"") && comment.endsWith("\"")) {
                comment = comment.substring(1, comment.length() - 1);
            }

            return comment;
        } catch (Exception e) {
            System.err.println("AI生成评论失败，使用默认评论: " + e.getMessage());
            // 降级方案：返回基于用户画像的简单评论
//            return generateFallbackComment(user, book);
            return null;
        }
    }

    /**
     * 构建用户画像描述
     */
    private String buildUserProfile(User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 邮箱: ").append(user.getEmail()).append("\n");

        if (user.getBirthday() != null) {
            int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
            sb.append("- 年龄: ").append(age).append("岁\n");
        }

        if (user.getGender() != null) {
            String genderText = switch (user.getGender()) {
                case "MALE" -> "男性";
                case "FEMALE" -> "女性";
                default -> "其他";
            };
            sb.append("- 性别: ").append(genderText).append("\n");
        }

        if (user.getMarried() != null) {
            sb.append("- 婚姻状态: ").append(user.getMarried() ? "已婚" : "未婚").append("\n");
        }

        if (user.getHasChildren() != null) {
            sb.append("- 子女情况: ").append(user.getHasChildren() ? "有孩子" : "无孩子").append("\n");
        }

        if (user.getMbti() != null) {
            sb.append("- MBTI人格: ").append(user.getMbti()).append("\n");
        }

        if (user.getOccupation() != null) {
            String occupationText = translateOccupation(user.getOccupation());
            sb.append("- 职业: ").append(occupationText).append("\n");
        }
//
//        if (user.getEducation() != null) {
//            String educationText = translateEducation(user.getEducation());
//            sb.append("- 学历: ").append(educationText).append("\n");
//        }

        if (user.getEntrepreneurship() != null) {
            String entrepreneurshipText = translateEntrepreneurship(user.getEntrepreneurship());
            sb.append("- 创业意向: ").append(entrepreneurshipText).append("\n");
        }
//
//        if (user.getAnnualIncome() != null) {
//            String incomeText = translateIncome(user.getAnnualIncome());
//            sb.append("- 年收入: ").append(incomeText).append("\n");
//        }

        if (user.getMood() != null) {
            String moodText = translateMood(user.getMood());
            sb.append("- 当前心情: ").append(moodText).append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建书籍信息（包含前20000字内容摘要）
     */
    private String buildBookInfo(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 书名: ").append(book.getTitle()).append("\n");

        if (book.getAuthor() != null) {
            sb.append("- 作者: ").append(book.getAuthor()).append("\n");
        }

        // 提取图书前20000字作为内容摘要
        String contentSummary = extractBookContentSummary(book);
        if (contentSummary != null && !contentSummary.isBlank()) {
            sb.append("- 内容摘要(前20000字): ").append(contentSummary).append("\n");
        } else {
            System.out.println("警告: 无法提取图书内容，将使用简介: " + book.getTitle());
            // 降级方案：如果无法提取内容，使用简介
            if (book.getDescription() != null && !book.getDescription().isBlank()) {
                String desc = book.getDescription();
                if (desc.length() > 3000) {
                    desc = desc.substring(0, 3000) + "...";
                }
                sb.append("- 简介: ").append(desc).append("\n");
            }
        }

        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            sb.append("- 标签: ").append(book.getFormatTags()).append("\n");
        }

        if (book.getRating() != null && book.getRating() > 0) {
            sb.append("- 平均评分: ").append(book.getRating()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 从图书文件中提取前20000字作为内容摘要
     */
    private String extractBookContentSummary(Book book) {
        try {
            if (book.getFileUrl() == null || book.getFileUrl().isBlank()) {
                return null;
            }

            java.nio.file.Path filePath = java.nio.file.Paths.get(book.getFileUrl());
            if (!java.nio.file.Files.exists(filePath)) {
                System.out.println("警告: 图书文件不存在: " + book.getFileUrl());
                return null;
            }

            // 使用 BookParserService 的私有方法反射调用
            // 由于这些方法是private，我们需要通过反射来调用
            try {
                java.lang.reflect.Method method = bookParserService.getClass()
                        .getDeclaredMethod("extractContentForTags", Book.class);
                method.setAccessible(true);
                String content = (String) method.invoke(bookParserService, book);

                if (content == null || content.isBlank()) {
                    return null;
                }

                // 取前20000字
                int maxLength = 20000;
                if (content.length() > maxLength) {
                    content = content.substring(0, maxLength) + "...";
                }

                return content.trim();

            } catch (ReflectiveOperationException e) {
                System.err.println("反射调用失败: " + e.getMessage());
                return null;
            }

        } catch (Exception e) {
            System.err.println("提取图书内容失败: " + book.getTitle() + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 翻译职业
     */
    private String translateOccupation(String occupation) {
        return switch (occupation) {
            case "STUDENT" -> "学生";
            case "TECH" -> "技术人员";
            case "FINANCE" -> "金融从业者";
            case "EDUCATION" -> "教育工作者";
            case "MEDICAL" -> "医疗从业者";
            case "ARTS" -> "艺术工作者";
            case "MANAGEMENT" -> "管理人员";
            case "FREELANCE" -> "自由职业者";
            case "RETIRED" -> "退休人员";
            case "OTHER" -> "其他";
            default -> occupation;
        };
    }

    /**
     * 翻译学历
     */
    private String translateEducation(String education) {
        return switch (education) {
            case "HIGH_SCHOOL" -> "高中";
            case "COLLEGE" -> "大专";
            case "BACHELOR" -> "本科";
            case "MASTER" -> "硕士";
            case "DOCTORATE" -> "博士";
            case "OTHER" -> "其他";
            default -> education;
        };
    }

    /**
     * 翻译创业意向
     */
    private String translateEntrepreneurship(String entrepreneurship) {
        return switch (entrepreneurship) {
            case "ENTREPRENEUR" -> "正在创业";
            case "WANT_ENTREPRENEUR" -> "想创业";
            case "NOT_INTERESTED" -> "暂不考虑";
            default -> entrepreneurship;
        };
    }

    /**
     * 翻译收入
     */
    private String translateIncome(String income) {
        return switch (income) {
            case "UNDER_50K" -> "5万以下";
            case "50K_150K" -> "5-15万";
            case "150K_300K" -> "15-30万";
            case "300K_500K" -> "30-50万";
            case "500K_1M" -> "50-100万";
            case "OVER_1M" -> "100万以上";
            case "PREFER_NOT_TO_SAY" -> "不便透露";
            default -> income;
        };
    }

    /**
     * 翻译心情
     */
    private String translateMood(String mood) {
        return switch (mood) {
            case "HAPPY" -> "开心";
            case "CALM" -> "平静";
            case "ANXIOUS" -> "焦虑";
            case "SAD" -> "悲伤";
            case "MOTIVATED" -> "充满动力";
            case "TIRED" -> "疲惫";
            case "CURIOUS" -> "好奇";
            default -> mood;
        };
    }

    /**
     * 降级方案：生成简单的默认评论
     */
    private String generateFallbackComment(User user, Book book) {
        String[] templates = {
                "这本书写得不错，值得一读。",
                "内容很丰富，收获颇多。",
                "作者的见解很独到，推荐给大家。",
                "读完受益匪浅，会再读一遍。",
                "很好的书，对工作和生活都有帮助。",
                "文笔流畅，观点清晰，值得推荐。",
                "这本书让我有了新的思考角度。",
                "内容实用，对我的工作很有启发。"
        };
        return templates[random.nextInt(templates.length)];
    }

    /**
     * 清空所有评论数据（谨慎使用）
     */
    @Test
    public void cleanAllComments() {
        System.out.println("开始清理所有评论数据...");
        long beforeCount = commentRepository.count();
        System.out.println("清理前评论总数: " + beforeCount);

        commentRepository.deleteAll();

        long afterCount = commentRepository.count();
        System.out.println("清理后评论总数: " + afterCount);
        System.out.println("共删除 " + (beforeCount - afterCount) + " 条评论数据");
    }
}
