package com.kbook.service.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.dto.debate.*;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.debate.DebateMessage;
import com.kbook.entity.debate.DebateScore;
import com.kbook.entity.debate.DebateSession;
import com.kbook.common.enums.DebateRole;
import com.kbook.repository.BookRepository;
import com.kbook.repository.debate.DebateMessageRepository;
import com.kbook.repository.debate.DebateScoreRepository;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ne;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.constants.ai.DebatePrompts;

/**
 * 辩论辩题服务 — 辩题生成/优化/解析与 Redis 缓存。
 */
@Slf4j
@Service
public class DebateTopicService {

    private static final String DEBATE_TOPICS_KEY_PREFIX = "kbook:debate:topics:";
    private static final long DEBATE_TOPICS_TTL_SECONDS = 86400; // 24h

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public DebateTopicService(
            BookService bookService,
            ChatModelManager chatModelManager,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 从书籍信息生成辩论话题列表。
     *
     * @param bookInfo 书籍元数据文本（书名、作者、标签、简介等）
     * @return AI 原始响应文本（Markdown 块格式），由调用方解析
     */
    public String callAiForDebateTopics(String bookInfo) {
        return chatModelManager.callAiForScene(AiScene.DEBATE_TOPIC_GENERATE, "辩论辩题生成", "bookInfo", List.of(
                SystemMessage.from(DebatePrompts.DEBATE_TOPIC_GENERATION_SYSTEM_PROMPT),
                UserMessage.from("书籍信息：" + bookInfo)));
    }

    /**
     * 优化用户自定义辩论话题。
     */
    public String callAiForDebateTopicOptimization(long bookId, String topic, String bookInfo, String proArg, String conArg) {
        String userPrompt = String.format("""
                书籍信息：
                %s

                用户的原始输入：
                辩题：%s
                正方观点：%s
                反方观点：%s""", bookInfo, topic, proArg, conArg);

        return chatModelManager.callAiForScene(AiScene.DEBATE_TOPIC_OPTIMIZE, "辩论辩题优化",
                String.format("bookId=%d, topic=%s", bookId, topic), List.of(
                        SystemMessage.from(DebatePrompts.DEBATE_OPTIMIZE_TOPIC_SYSTEM_PROMPT),
                        UserMessage.from(userPrompt)));
    }

    /**
     * 从书籍内容生成争议辩题（LLM 驱动），结果缓存 24h
     */
    public List<DebateTopicVO> generateTopics(Long bookId, boolean forceRefresh) {
        String cacheKey = DEBATE_TOPICS_KEY_PREFIX + bookId;

        if (!forceRefresh) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null && !cached.isBlank()) {
                    List<DebateTopicVO> topics = objectMapper.readValue(cached,
                            new TypeReference<>() {
                            });
                    if (topics != null && !topics.isEmpty()) {
                        log.debug("辩题缓存命中: bookId={}", bookId);
                        return topics;
                    }
                }
            } catch (Exception e) {
                log.debug("辩题缓存读取失败: {}", e.getMessage());
            }
        } else {
            log.info("强制刷新辩题，跳过缓存: bookId={}", bookId);
        }

        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return getFallbackTopics();
        }

        List<DebateTopicVO> topics;
        try {
            String bookInfo = buildBookInfoForTopic(book);
            String result = callAiForDebateTopics(bookInfo);

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                topics = parseDebateTopicsFromMarkdown(result);
                if (topics == null || topics.isEmpty()) {
                    topics = getFallbackTopics();
                }
            } else {
                topics = getFallbackTopics();
            }
        } catch (Exception e) {
            log.warn("LLM 辩题生成失败，使用兜底话题: bookId={} - {}", bookId, e.getMessage());
            topics = getFallbackTopics();
        }

        try {
            stringRedisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(topics),
                    java.time.Duration.ofSeconds(DEBATE_TOPICS_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("辩题缓存写入失败: {}", e.getMessage());
        }

        return topics;
    }

    private String buildBookInfoForTopic(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String tags = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(tags).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String tags = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者需求：").append(tags).append("\n");
        }
        if (book.getTargetReaderTags() != null && !book.getTargetReaderTags().isBlank()) {
            String tags = book.getTargetReaderTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("目标读者：").append(tags).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            sb.append("目录：\n").append(book.getToc()).append("\n");
        }

        // 摘要：优先 compressedSummary（LLM精炼） → chapterSummary（原始提取），均完整不截断
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            sb.append("\n【图书精炼摘要】\n").append(book.getCompressedSummary()).append("\n");
        } else if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            sb.append("\n【章节摘要】（每章核心内容概述）\n").append(book.getChapterSummary()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 使用LLM优化用户自定义辩题
     */
    public DebateTopicVO optimizeTopic(Long bookId, String topic, String proArg, String conArg) {
        Book book = bookService.getBookById(bookId);
        if (book == null) {
            return DebateTopicVO.builder()
                    .topic(topic).source("USER")
                    .proArgument(proArg).conArgument(conArg)
                    .build();
        }

        String bookInfo = buildBookInfoForTopic(book);

        try {
            String result = callAiForDebateTopicOptimization(
                    bookId, topic, bookInfo,
                    proArg != null ? proArg : "",
                    conArg != null ? conArg : "");

            if (result != null && !result.isBlank()) {
                result = CommonUtils.stripCodeFence(result);
                String optimizedTopic = CommonUtils.extractMarkdownSection(result, "优化后辩题");
                String optimizedPro = CommonUtils.extractMarkdownSection(result, "正方观点");
                String optimizedCon = CommonUtils.extractMarkdownSection(result, "反方观点");
                return DebateTopicVO.builder()
                        .topic(optimizedTopic != null ? optimizedTopic : topic)
                        .source("LLM")
                        .proArgument(optimizedPro != null ? optimizedPro : proArg)
                        .conArgument(optimizedCon != null ? optimizedCon : conArg)
                        .build();
            }
        } catch (Exception e) {
            log.warn("LLM 辩题优化失败，返回原始输入: {}", e.getMessage());
        }

        return DebateTopicVO.builder()
                .topic(topic).source("USER")
                .proArgument(proArg).conArgument(conArg)
                .build();
    }

    private List<DebateTopicVO> getFallbackTopics() {
        return List.of(
                DebateTopicVO.builder().topic("技术进步是否必然带来幸福？").source("SYSTEM")
                        .proArgument("技术进步提高了生活效率、医疗水平和信息获取能力，是人类幸福的基石")
                        .conArgument("技术进步导致隐私丧失、社会焦虑加剧、人际关系疏离").build(),
                DebateTopicVO.builder().topic("成功更多依赖天赋还是努力？").source("SYSTEM")
                        .proArgument("天赋决定上限，没有天赋再努力也难以达到顶尖水平")
                        .conArgument("努力可以弥补天赋的不足，持续的努力才是成功的关键").build(),
                DebateTopicVO.builder().topic("人工智能的发展对人类利大于弊吗？").source("SYSTEM")
                        .proArgument("AI 提升了生产效率、医疗诊断准确率，解决了很多人类难以解决的问题")
                        .conArgument("AI 会导致大规模失业、伦理困境，甚至可能威胁人类生存").build()
        );
    }

    /**
     * 解析 LLM 辩题生成的 Markdown 输出。
     * <p>期望格式（来自 DEBATE_TOPIC_GENERATION_SYSTEM_PROMPT）：
     * <pre>
     * 【辩题1】辩题文字
     * - 正方观点：xxx
     * - 反方观点：xxx
     *
     * 【辩题2】辩题文字
     * - 正方观点：xxx
     * - 反方观点：xxx
     * </pre>
     * <p>容错：标题行支持「【辩题N】」或「【辩题N】 xxx」两种形式；
     * 观点行支持「- 正方观点：」「- 正方观点:」「正方观点：」等多种变体。
     *
     * @return 解析得到的辩题列表；解析失败返回空列表
     */
    private List<DebateTopicVO> parseDebateTopicsFromMarkdown(String markdown) {
        List<DebateTopicVO> topics = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return topics;

        // 按【辩题N】分块（保留分隔行作为每块的开头）
        String[] blocks = markdown.split("(?=\\s*【辩题\\d+】)");
        Pattern titlePat = Pattern.compile("【辩题\\d+】\\s*(.*)");
        // 兼容 "- 正方观点："、"正方观点："、"- 正方观点:" 等
        Pattern proPat = Pattern.compile("(?:^|\\n)\\s*[-•*]?\\s*正方观点[：:]\\s*(.*)");
        Pattern conPat = Pattern.compile("(?:^|\\n)\\s*[-•*]?\\s*反方观点[：:]\\s*(.*)");

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            Matcher tm = titlePat.matcher(trimmed);
            if (!tm.find()) continue;
            String topic = tm.group(1).trim();
            if (topic.isEmpty()) continue;

            String pro = extractFirstGroup(proPat, trimmed);
            String con = extractFirstGroup(conPat, trimmed);

            topics.add(DebateTopicVO.builder()
                    .topic(topic)
                    .source("LLM")
                    .proArgument(pro != null ? pro : "")
                    .conArgument(con != null ? con : "")
                    .build());
        }
        return topics;
    }

    /** 工具：正则首次匹配的第 1 组内容（已 trim），无匹配返回 null */
    private String extractFirstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            String g = m.group(1);
            return g == null ? null : g.trim();
        }
        return null;
    }
}
