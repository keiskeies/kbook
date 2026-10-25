package com.kbook.service.roundtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SpeechSanitizer;
import com.kbook.common.util.SseHelper;
import com.kbook.config.ai.AiConfig;
import com.kbook.config.ai.AiConfigProvider;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.constants.AiPromptConstants;
import com.kbook.dto.roundtable.NextSpeakerResult;
import com.kbook.dto.roundtable.RoleVO;
import com.kbook.dto.roundtable.RoundTableSessionFeedVO;
import com.kbook.dto.roundtable.SpeakRequest;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableCoverageRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import com.kbook.service.ai.core.ChatHistoryCompressor;
import com.kbook.service.ai.core.ExternalKnowledgeGenerator;
import com.kbook.service.ai.streaming.StreamingSseHandler;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.progress.ReadingProgressService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.in;
import com.kbook.service.ai.rag.RagPipelineComponents;
import com.kbook.service.ai.core.ChatModelManager;
import com.kbook.service.ai.core.AiProviderConfigService;
import com.kbook.service.ai.core.AiSceneConfigService;
import com.kbook.service.ai.core.AiUsageContext;

/**
 * 圆桌派发言服务 — 单角色 SSE 发言 / 提示词构建 / 消息持久化与历史压缩 / 主持话题进度。
 */
@Slf4j
@Service
@LogModule("圆桌派")
public class RoundTableSpeechService {

    private final BookService bookService;
    private final ChatModelManager chatModelManager;
    private final ChatHistoryCompressor chatHistoryCompressor;
    private final ExternalKnowledgeGenerator externalKnowledgeGenerator;
    private final AiSceneConfigService aiSceneConfigService;
    private final RoundTableSessionRepository sessionRepository;
    private final RoundTableMessageRepository messageRepository;
    private final RoundTableCoverageService coverageService;
    private final AiConfigProvider aiConfigProvider;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;
    private final RoundTableSessionService sessionService;
    private final RoundTableRagService ragService;

    public RoundTableSpeechService(
            BookService bookService,
            ChatModelManager chatModelManager,
            ChatHistoryCompressor chatHistoryCompressor,
            ExternalKnowledgeGenerator externalKnowledgeGenerator,
            AiSceneConfigService aiSceneConfigService,
            RoundTableSessionRepository sessionRepository,
            RoundTableMessageRepository messageRepository,
            RoundTableCoverageService coverageService,
            AiConfigProvider aiConfigProvider,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            ObjectMapper objectMapper,
            RoundTableSessionService sessionService,
            RoundTableRagService ragService) {
        this.bookService = bookService;
        this.chatModelManager = chatModelManager;
        this.chatHistoryCompressor = chatHistoryCompressor;
        this.externalKnowledgeGenerator = externalKnowledgeGenerator;
        this.aiSceneConfigService = aiSceneConfigService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.coverageService = coverageService;
        this.aiConfigProvider = aiConfigProvider;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.ragService = ragService;
    }

    // ==================== 单角色发言（SSE + DB 持久化） ====================

    /**
     * 单角色发言 SSE：前端指定某个角色发言，后端从 DB 加载历史并调用 AI 生成发言内容
     *
     * @param userId  用户ID
     * @param bookId  书籍ID
     * @param request 发言请求（包含角色键名、会话ID、话题方向）
     * @return SseEmitter 流式发射器
     */
    @LogAction("圆桌派角色发言")
    public SseEmitter streamCharacterSpeak(Long userId, Long bookId, SpeakRequest request) {
        SseEmitter emitter = new SseEmitter(3600_000L);

        // 解析角色
        AiConfig.RoundTableRole role = aiConfigProvider.getRoundTableRole(request.getRoleKey());
        if (role == null) {
            SseHelper.sendErrorAndComplete(emitter, "未知角色: " + request.getRoleKey());
            return emitter;
        }

        // 验证会话
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(request.getSessionId()))
                .list(1)
                .stream().findFirst().orElse(null);
        if (session == null) {
            SseHelper.sendErrorAndComplete(emitter, "会话不存在: " + request.getSessionId());
            return emitter;
        }

        // 验证会话所有权：只有创建者可以发言
        try {
            sessionService.verifySessionOwnership(userId, request.getSessionId());
        } catch (BusinessException e) {
            SseHelper.sendErrorAndComplete(emitter, e.getMessage());
            return emitter;
        }

        // 获取角色的 domainRelevance（从 roleConfigs JSON 中读取，或使用枚举默认值）
        int domainRelevance = resolveDomainRelevance(role, session.getRoleConfigs());

        // 获取角色的 languageStyle（从 roleConfigs JSON 中读取）
        String languageStyle = resolveLanguageStyle(role, session.getRoleConfigs());

        Future<?> aiFuture = AiUsageContext.submit(sseExecutor, bookId, session.getSessionId(), () -> {
            try {
                Book book = bookService.getBookById(bookId);

                // 加载历史消息（用于判断是否开场以及构建 RAG）
                List<RoundTableMessage> historyMessages = Collections.emptyList();
                if (book != null) {
                    historyMessages = messageRepository.query()
                            .where(RoundTableMessage::getSessionId, eq(request.getSessionId()))
                            .orderBy(RoundTableMessage::getId)
                            .list();
                }
                boolean isOpening = historyMessages.isEmpty();

                // 构建系统提示词（仅共享规则）
                String systemPrompt = buildCharacterSystemPrompt(role, domainRelevance, request.getTopic(), languageStyle, isOpening);

                // 构建角色设定 UserMessage（每个角色不同，KV 缓存在此处分叉）
                String roleSetting = buildRoleSettingPrompt(role, domainRelevance, languageStyle);

                // 构建额外指令（话题方向 / 开场引导）
                String extraInstructions = buildExtraInstructions(role, request.getTopic(), isOpening);

                // 构建书籍上下文（静态信息）和 RAG 内容（每次变化）
                String bookInfo = book != null ? buildBookInfo(book) : "";
                String ragContent = "";
                if (book != null) {
                    if (!"HOST".equals(role.getKey())) {
                        // 嘉宾：RAG 检索原著内容
                        if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                            boolean embedded = ragService.waitForContentEmbedding(bookId);
                            if (embedded) book.setContentEmbedded(true);
                        }
                        ragContent = ragService.retrieveRagContextForRole(book, role, historyMessages);
                    } else {
                        // 主持人
                        if (historyMessages.isEmpty()) {
                            // 首轮开场：不注入覆盖度/话题进度，避免覆盖「开场介绍书籍」的指令
                            ragContent = "这是圆桌派讨论的第一轮。请先以主持人的身份做一个完整的开场："
                                    + "欢迎各位嘉宾，然后简要介绍今天要讨论的书籍《" + book.getTitle() + "》的核心主题，"
                                    + "最后向嘉宾抛出第一个讨论问题。不要跳过开场直接讨论具体概念。";
                        } else {
                            // 覆盖度引导（比 RAG 更精准）
                            try {
                                coverageService.updateCoverage(request.getSessionId(), true);
                                String coverageGuidance = coverageService.buildHostCoverageGuidance(request.getSessionId());
                                if (!coverageGuidance.isBlank()) {
                                    ragContent = coverageGuidance;
                                }
                            } catch (Exception e) {
                                log.warn("覆盖度更新/引导生成失败，回退到简单模式: {}", e.getMessage());
                            }
                            if (ragContent.isBlank()) {
                                ragContent = appendHostTopicProgress(book, historyMessages);
                            }
                        }
                    }
                }

                // 消息顺序：SystemMessage(规则) → UserMessage(书籍信息) → History → UserMessage(RAG) → UserMessage(角色设定) → UserMessage(发言指令)
                // 清洗用的角色名清单 + 未覆盖章节素材（在流式回调里也要用）
                final List<String> allRoleNames = sessionRoleNames(session);
                final List<String> uncoveredTitles = loadUncoveredTitles(request.getSessionId());

                List<ChatMessage> messages = buildChatMessages(
                        request.getSessionId(), userId, systemPrompt, bookInfo, ragContent, roleSetting, extraInstructions, role.getName(),
                        role, book, allRoleNames, uncoveredTitles);

                // 构建流式模型（不使用 thinking 模式）
                StreamingChatModel streamingChatModel = chatModelManager.getStreamingModelForScene(AiScene.ROUND_TABLE_SPEECH);
                if (streamingChatModel == null) {
                    SseHelper.sendErrorAndComplete(emitter, "AI 助理暂未配置，请联系管理员");
                    return;
                }

                // 构建日志上下文（场景/模型/思考配置），供 StreamingSseHandler 打印统一摘要
                var logContext = chatModelManager.buildLogContext(AiScene.ROUND_TABLE_SPEECH);

                StreamingSseHandler.stream(streamingChatModel, messages, emitter, new StreamingSseHandler.Callback() {
                    @Override
                    public String getOperationName() { return "圆桌派发言"; }

                    @Override
                    public String formatMessageEvent(String text) {
                        try {
                            return objectMapper.writeValueAsString(
                                    Map.of("roleKey", role.getKey(), "text", text));
                        } catch (Exception e) {
                            return text;
                        }
                    }

                    @Override
                    public void onComplete(String content, ChatResponse completeResponse) {
                        // 统一摘要日志已由 StreamingSseHandler.onCompleteResponse 打印

                        // 落库前清洗：剥名字前缀、截断串台、修第三人称旁白
                        String cleaned = SpeechSanitizer.sanitize(content, role.getName(), allRoleNames);
                        if (!cleaned.isBlank()) {
                            saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), cleaned);
                            updateSessionTimestamp(request.getSessionId());

                            try {
                                compressHistoryIfNeeded(userId, request.getSessionId(), 0);
                            } catch (Exception e) {
                                log.warn("压缩圆桌派历史失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                            }
                        }

                        // 异步更新覆盖度
                        if (!"HOST".equals(role.getKey()) && !content.isBlank()) {
                            try {
                                coverageService.updateCoverage(request.getSessionId(), false);
                            } catch (Exception e) {
                                log.warn("覆盖度更新失败: sessionId={} - {}", request.getSessionId(), e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConnectionClosed(String partialContent) {
                        if (!partialContent.isBlank()) {
                            try {
                                String cleaned = SpeechSanitizer.sanitize(partialContent, role.getName(), allRoleNames);
                                if (!cleaned.isBlank()) {
                                    saveMessage(userId, request.getSessionId(), bookId, role.getKey(), role.getName(), cleaned);
                                }
                            } catch (Exception e) {
                                log.warn("保存部分消息失败: {}", e.getMessage());
                            }
                        }
                    }
                }, 2, logContext);

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                log.error("圆桌派单角色发言异常: bookId={}, roleKey={} - {}", bookId, role.getKey(), e.getMessage(), e);
                SseHelper.sendErrorAndComplete(emitter, "AI 响应异常: " + SseHelper.extractFriendlyError(e));
            }
        });

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> {
            aiFuture.cancel(true);
            log.warn("圆桌派SSE超时: bookId={}, roleKey={}", bookId, role.getKey());
        });
        emitter.onError(e -> {
            aiFuture.cancel(true);
            log.error("圆桌派SSE错误: bookId={}, roleKey={}", bookId, role.getKey(), e);
        });

        return emitter;
    }

    // ==================== 提示词构建 ====================

    /**
     * 谢幕指令前缀 — 前端把 closingSummary 作为 topic 传入时加此前缀，
     * 后端据此切换 HOST 的系统提示词为谢幕 prompt。
     */
    private static final String CLOSING_TOPIC_PREFIX = "[CLOSING]";

    /**
     * 构建单角色系统提示词（含语言风格、性格维度和 domainRelevance）
     * <p>
     * HOST 检测到 topic 以 [CLOSING] 开头时切换为谢幕 prompt。
     */
    private String buildCharacterSystemPrompt(AiConfig.RoundTableRole role, int domainRelevance, String topic, String languageStyle, boolean isOpening) {
        if ("HOST".equals(role.getKey())) {
            if (topic != null && topic.startsWith(CLOSING_TOPIC_PREFIX)) {
                return AiPromptConstants.ROUND_TABLE_HOST_CLOSING_PROMPT;
            }
            return AiPromptConstants.ROUND_TABLE_HOST_PROMPT;
        } else {
            return AiPromptConstants.ROUND_TABLE_CHARACTER_PROMPT;
        }
    }

    /**
     * 构建角色设定 UserMessage（包含人设核心、说话方式、风格、性格参数）
     * 此部分每个角色不同，KV 缓存在此处分叉
     */
    private String buildRoleSettingPrompt(AiConfig.RoundTableRole role, int domainRelevance, String languageStyle) {
        if ("HOST".equals(role.getKey())) {
            String hostStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "沉稳大方，善于引导和总结";
            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_HOST,
                    hostStyle,
                    role.getParams().getChallenge(), describeChallenge(role.getParams().getChallenge()),
                    role.getParams().getEmpathy(), describeEmpathy(role.getParams().getEmpathy()),
                    role.getParams().getOpinionated(), describeOpinionated(role.getParams().getOpinionated()),
                    role.getParams().getVerbosity(), describeVerbosity(role.getParams().getVerbosity()));
        } else {
            String charStyle = (languageStyle != null && !languageStyle.isBlank()) ? languageStyle : "自然流畅，符合你的专业身份";

            String rolePrompt = role.getPrompt();
            String catchphrase = role.getCatchphrase() != null ? role.getCatchphrase() : "用你自己的方式表达，保持自然";

            return String.format(AiPromptConstants.ROUND_TABLE_ROLE_SETTING_GUEST,
                    rolePrompt,
                    catchphrase,
                    charStyle,
                    role.getParams().getChallenge(), describeChallenge(role.getParams().getChallenge()),
                    role.getParams().getEmpathy(), describeEmpathy(role.getParams().getEmpathy()),
                    role.getParams().getOpinionated(), describeOpinionated(role.getParams().getOpinionated()),
                    role.getParams().getVerbosity(), describeVerbosity(role.getParams().getVerbosity()),
                    role.getParams().getHumor(), describeHumor(role.getParams().getHumor()),
                    domainRelevance, describeDomainRelevance(domainRelevance));
        }
    }

    /**
     * 构建额外指令（话题方向 / 开场引导 / 覆盖度 / 谢幕），作为发言指令的前缀
     * <p>
     * 谢幕指令（topic 以 [CLOSING] 开头）走独立分支：
     * - 系统提示词已在 buildCharacterSystemPrompt 中切换为 ROUND_TABLE_HOST_CLOSING_PROMPT
     * - 这里只提取 closingSummary 主体作为 UserMessage，让 HOST 自由发挥
     */
    private String buildExtraInstructions(AiConfig.RoundTableRole role, String topic, boolean isOpening) {
        if ("HOST".equals(role.getKey())) {
            // 谢幕指令：topic 格式为 "[CLOSING]<closingSummary>"
            if (topic != null && topic.startsWith(CLOSING_TOPIC_PREFIX)) {
                String closingSummary = topic.substring(CLOSING_TOPIC_PREFIX.length()).trim();
                return "【谢幕提纲】\n" + (closingSummary.isBlank()
                        ? "请对本次讨论做总结谢幕。"
                        : closingSummary);
            }
            if (topic != null && !topic.isBlank()) {
                return "【主持任务】\n" + topic
                        + "\n（只说一两句，然后点名一位嘉宾发言，不要自己展开论述、不要总结全场。）";
            } else if (isOpening) {
                return "【开场（第一轮）】这是圆桌派讨论的开场。你作为主持人，最重要的任务是做开场介绍："
                        + "首先欢迎各位嘉宾，然后简要介绍今天要讨论的书籍的核心主题和为什么值得讨论，"
                        + "最后向嘉宾抛出第一个讨论问题。此指令优先于「每次发言必须引入新主题」。";
            } else {
                return "请回顾之前的对话。如果讨论陷入僵局、重复或钻牛角尖，请果断抛出一个新的话题或角度来激发讨论。如果讨论还在正常进行，可以简短回应或向某位嘉宾提问。";
            }
        }
        return "";
    }

    /**
     * 挑战倾向描述
     */
    private String describeChallenge(int v) {
        if (v >= 4) return "喜欢质疑和反驳";
        if (v >= 3) return "适度挑战";
        return "较少质疑";
    }

    /**
     * 共情力描述
     */
    private String describeEmpathy(int v) {
        if (v >= 4) return "善于理解和共鸣";
        if (v >= 3) return "适度共情";
        return "理性优先";
    }

    /**
     * 主见程度描述
     */
    private String describeOpinionated(int v) {
        if (v >= 4) return "立场坚定";
        if (v >= 3) return "有一定主见";
        return "立场灵活";
    }

    /**
     * 话量描述
     */
    private String describeVerbosity(int v) {
        if (v >= 4) return "话多";
        if (v >= 3) return "话量适中";
        return "话少精炼";
    }

    /**
     * 幽默感描述
     */
    private String describeHumor(int v) {
        if (v >= 4) return "善于调侃和活跃气氛";
        if (v >= 3) return "适度幽默";
        return "严肃认真";
    }

    /**
     * 专业相关度描述
     */
    private String describeDomainRelevance(int v) {
        if (v >= 7) return "这是你的专业主场，应该自信发言";
        if (v >= 4) return "与你的领域有一定关联";
        return "超出你的专业领域，但可以从你的视角提供独特见解";
    }

    /**
     * 从 roleConfigs JSON 中解析角色的 domainRelevance
     */
    private int resolveDomainRelevance(AiConfig.RoundTableRole role, String roleConfigs) {
        if (roleConfigs == null || roleConfigs.isBlank()) {
            return 0;
        }
        try {
            var nodes = objectMapper.readTree(roleConfigs);
            if (nodes.isArray()) {
                for (var node : nodes) {
                    if (node.has("key") && role.getKey().equals(node.get("key").asText())) {
                        return node.has("domainRelevance") ? node.get("domainRelevance").asInt() : 0;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 roleConfigs 失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 从 roleConfigs JSON 中解析角色的 languageStyle
     */
    private String resolveLanguageStyle(AiConfig.RoundTableRole role, String roleConfigs) {
        if (roleConfigs == null || roleConfigs.isBlank()) {
            return "";
        }
        try {
            var nodes = objectMapper.readTree(roleConfigs);
            if (nodes.isArray()) {
                for (var node : nodes) {
                    if (node.has("key") && role.getKey().equals(node.get("key").asText())) {
                        return node.has("languageStyle") ? node.get("languageStyle").asText() : "";
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析角色语言风格失败: {}", e.getMessage());
        }
        return "";
    }

    // ==================== 消息构建 ====================

    /**
     * 构建消息列表，顺序优化 KV Cache：
     * SystemMessage(共享规则) → UserMessage(书籍信息) → 历史对话 → UserMessage(角色设定，角色内稳定)
     * → UserMessage(RAG，每次不同) → UserMessage(额外指令+发言指令)
     */
    private List<ChatMessage> buildChatMessages(String sessionId, Long userId,
                                                String systemPrompt, String bookInfo, String ragContent,
                                                String roleSetting, String extraInstructions, String roleName,
                                                AiConfig.RoundTableRole role, Book book,
                                                List<String> allRoleNames, List<String> uncoveredTitles) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词（仅共享规则）— 全局静态
        messages.add(SystemMessage.from(systemPrompt));

        // 2. 静态书籍基础信息 — 全局静态
        if (bookInfo != null && !bookInfo.isBlank()) {
            messages.add(UserMessage.from("【书籍信息】\n" + bookInfo));
        }

        // 3. 角色设定 — 同一角色跨轮次不变，通常 2000-3000 字符，尽量靠前以复用缓存
        if (roleSetting != null && !roleSetting.isBlank()) {
            messages.add(UserMessage.from(roleSetting));
        }

        // 4. 发言指令（含额外指令）— 同一角色跨轮次不变
        String speakInstruction;
        if ("HOST".equals(roleName) || "主持人".equals(roleName)) {
            speakInstruction = "请以主持人的身份发言。直接说你的观点或抛出问题，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头。";
        } else {
            speakInstruction = "请以" + roleName + "的身份发言。直接接话，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头，直接说你的观点。";
        }
        StringBuilder finalInstruction = new StringBuilder();
        if (extraInstructions != null && !extraInstructions.isBlank()) {
            finalInstruction.append(extraInstructions).append("\n\n");
        }
        finalInstruction.append(speakInstruction);
        messages.add(UserMessage.from(finalInstruction.toString()));

        // ——— 以上 4 条：同一角色跨轮次可命中缓存（SystemMessage + bookInfo + roleSetting + instruction）———
        // ——— 以下为动态内容，每次变化，放在最后以最小化缓存失效 ———

        // 5. 历史对话（每次变化）
        int currentOverhead = systemPrompt.length()
                + (bookInfo != null ? bookInfo.length() : 0)
                + (ragContent != null ? ragContent.length() : 0)
                + (roleSetting != null ? roleSetting.length() : 0)
                + (extraInstructions != null ? extraInstructions.length() : 0)
                + 2000;
        List<RoundTableMessage> history = new ArrayList<>();
        try {
            history = loadAndCompressHistory(userId, sessionId, currentOverhead);
            if (!history.isEmpty()) {
                StringBuilder historyBuilder = new StringBuilder("【之前的讨论内容】\n");
                for (RoundTableMessage msg : history) {
                    String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                            ? msg.getCompressedContent()
                            : msg.getContent();
                    if (content != null && !content.isBlank()) {
                        historyBuilder.append(msg.getRoleName()).append("：").append(content).append("\n\n");
                    }
                }
                messages.add(UserMessage.from(historyBuilder.toString()));
                log.debug("加载圆桌派历史: sessionId={}, totalRecords={}", sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("加载圆桌派历史失败，继续无历史对话: {}", e.getMessage());
        }

        // 6. RAG 内容（每次变化）
        if (ragContent != null && !ragContent.isBlank()) {
            messages.add(UserMessage.from("【书籍参考内容】\n" + ragContent));
        }

        // 7. 外部知识（每次变化）
        String externalKnowledge = generateExternalKnowledgeForRole(role, book, history);
        if (externalKnowledge != null && !externalKnowledge.isBlank()) {
            messages.add(UserMessage.from("【外部参考知识】\n以下知识可帮助你从专业视角评价讨论话题：\n" + externalKnowledge));
        }

        // 8. 收口指令（必须是最后一条）：
        //    前面所有内容都以「角色名：内容」结尾，模型天然会顺着续写下一行，产出名字前缀甚至替别人发言。
        //    把「现在轮到你」+ 自我重复禁区 + 书本素材放在最末尾，才能压住这个续写惯性。
        messages.add(UserMessage.from(
                buildSpeakTail(role, roleName, history, uncoveredTitles)));

        return messages;
    }

    /**
     * 构建消息列表末尾的收口指令块。
     */
    private String buildSpeakTail(AiConfig.RoundTableRole role, String roleName,
                                  List<RoundTableMessage> history, List<String> uncoveredTitles) {
        StringBuilder sb = new StringBuilder();

        if (uncoveredTitles != null && !uncoveredTitles.isEmpty()) {
            String material = uncoveredTitles.stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> "- 《" + t + "》")
                    .collect(Collectors.joining("\n"));
            if (!material.isBlank()) {
                sb.append(String.format(AiPromptConstants.ROUND_TABLE_MATERIAL_BLOCK, material)).append("\n\n");
            }
        }

        String own = recentOwnSpeeches(history, role.getKey(), 2);
        if (!own.isBlank()) {
            sb.append(String.format(AiPromptConstants.ROUND_TABLE_ANTI_REPEAT_BLOCK, own)).append("\n\n");
        }

        sb.append(String.format(AiPromptConstants.ROUND_TABLE_SPEAK_TAIL, roleName));
        return sb.toString();
    }

    /**
     * 提取该角色最近 N 条发言原文（用于生成"禁止复用"清单）。
     */
    private String recentOwnSpeeches(List<RoundTableMessage> history, String roleKey, int limit) {
        if (history == null || history.isEmpty() || roleKey == null) return "";
        List<String> lines = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && lines.size() < limit; i--) {
            RoundTableMessage m = history.get(i);
            if (!roleKey.equals(m.getRoleKey())) continue;
            String content = m.getCompressedContent() != null && !m.getCompressedContent().isBlank()
                    ? m.getCompressedContent() : m.getContent();
            if (content == null || content.isBlank()) continue;
            String line = content.replace("\n", " ").trim();
            if (line.length() > 90) line = line.substring(0, 90) + "…";
            lines.add("- " + line);
        }
        return String.join("\n", lines);
    }

    /**
     * 解析会话内全部角色中文名（用于清洗名字前缀/串台）。
     * 优先取 roleConfigs 里的会话级自定义名，回退到 AiConfig 预置名。
     */
    private List<String> sessionRoleNames(RoundTableSession session) {
        List<String> names = new ArrayList<>();
        if (session == null || session.getRoleKeys() == null || session.getRoleKeys().isBlank()) {
            return names;
        }
        Map<String, String> configNames = new HashMap<>();
        try {
            if (session.getRoleConfigs() != null && !session.getRoleConfigs().isBlank()) {
                JsonNode arr = objectMapper.readTree(session.getRoleConfigs());
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        if (n.hasNonNull("key") && n.hasNonNull("name")) {
                            configNames.put(n.get("key").asText(), n.get("name").asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析角色配置名称失败: {}", e.getMessage());
        }
        for (String k : session.getRoleKeys().split(",")) {
            String key = k.trim();
            if (key.isEmpty()) continue;
            String name = configNames.get(key);
            if (name == null || name.isBlank()) {
                AiConfig.RoundTableRole r = aiConfigProvider.getRoundTableRole(key);
                name = r != null ? r.getName() : key;
            }
            if (!names.contains(name)) names.add(name);
        }
        // 主持人别名也算，防止模型写「主持人：」
        for (String alias : List.of("主持人", "HOST")) {
            if (!names.contains(alias)) names.add(alias);
        }
        return names;
    }

    /**
     * 取尚未覆盖的章节标题，作为"把话题拉回书本"的素材。
     */
    List<String> loadUncoveredTitles(String sessionId) {
        try {
            return coverageService.getUncoveredBlockTitles(sessionId, 3);
        } catch (Exception e) {
            log.debug("获取未覆盖章节标题失败: sessionId={} - {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    // ==================== 消息持久化 ====================

    /**
     * 为角色生成外部知识
     */
    private String generateExternalKnowledgeForRole(AiConfig.RoundTableRole role, Book book, List<RoundTableMessage> history) {
        try {
            String domain = getRoleDomain(role);
            String topic = buildDiscussionTopic(book, history);
            return externalKnowledgeGenerator.generateForRoundTable(domain, topic);
        } catch (Exception e) {
            log.debug("生成外部知识失败: role={}, error={}", role.getKey(), e.getMessage());
            return "";
        }
    }

    private String getRoleDomain(AiConfig.RoundTableRole role) {
        return switch (role.getKey()) {
            case "SOCIOLOGIST" -> "社会学：文化资本、规训与惩罚、文化霸权、拟剧论、社会团结、性别操演";
            case "HISTORIAN" -> "历史学：长时段、年鉴学派、二重证据法、历史合力论、世界体系、集体记忆";
            case "PSYCHOLOGIST" -> "心理学：依恋理论、防御机制、双系统理论、认知失调、群体动力、习得性无助、大五人格";
            case "PHILOSOPHER" -> "哲学：概念辨析、逻辑推演、伦理框架、形而上学、语言分析、存在论";
            case "SCIENTIST" -> "科学：可证伪性、控制变量、RCT、统计显著性、范式革命、幸存者偏差";
            case "ENTREPRENEUR" -> "商业：商业模式画布、蓝海战略、精益创业、增长黑客、OKR、护城河理论";
            case "INVESTOR" -> "投资：安全边际、护城河、价值投资、损失厌恶、凯利公式、美林时钟";
            case "ECONOMIST" -> "经济学：边际效用、机会成本、博弈论、科斯定理、交易成本、行为经济学";
            case "LAWYER" -> "法律：三段论推理、归谬法、文义解释、举证责任、无罪推定、程序正义";
            case "JOURNALIST" -> "新闻：5W1H、信源交叉验证、框架理论、议程设置、数据驱动调查、媒介伦理";
            case "CRITIC" -> "文学批评：叙事学、隐喻修辞、形式主义、阐释学、解构主义、女性主义批评";
            case "WRITER" -> "创作：三幕式结构、冰山理论、人物弧光、陌生化、复调小说、意识流";
            case "EDUCATOR" -> "教育：最近发展区、自我决定理论、成长型思维、掌握学习、操作性条件反射";
            case "STUDENT" -> "求知：苏格拉底反诘法、费曼学习法、元认知、布鲁姆提问法、跨学科迁移";
            case "COMEDIAN" -> "幽默：预期违背、错位反差、归谬法、黑色幽默、三翻四抖、荒诞派";
            case "ACTOR" -> "表演：斯坦尼体系、方法派、布莱希特间离、情绪记忆、人物小传、第四面墙";
            case "DIRECTOR" -> "导演：蒙太奇理论、长镜头理论、场面调度、景别逻辑、剪辑节奏、声画关系";
            case "ARTIST" -> "艺术：形式主义、图像学、符号学、极简主义、观念艺术、关系美学";
            case "MUSICIAN" -> "音乐：曲式结构、和声对位、调性无调性、配器法、音色组合、偶然音乐";
            case "POET" -> "诗歌：意象叠加、客观对应物、通感、陌生化、含混多义、语言悖论";
            case "TRANSLATOR" -> "翻译：功能对等、归化异化、文化缺省、目的论、解构主义翻译、文本类型学";
            case "DOCTOR" -> "医学：鉴别诊断、循证医学、安慰剂效应、治疗窗、知情同意、医学伦理四原则";
            case "FARMER" -> "农业：二十四节气、轮作倒茬、地力养护、桑基鱼塘、看天吃饭、间作套种";
            case "FIREFIGHTER" -> "消防：火灾三角形、先控制后消灭、生命优先原则、安全观察员、两进一出、分级响应";
            case "NURSE" -> "护理：SBAR沟通、治疗性沟通、疼痛三阶梯、ADL量表、舒适护理、护理程序";
            case "MEDITATION_TEACHER" -> "冥想：观呼吸、身体扫描、RAIN法、正念减压MBSR、认知解离、四无量心";
            case "PARENT" -> "家庭：依恋理论、权威型教养、非暴力沟通、成长型思维、正强化、延迟满足";
            case "TRAVELER" -> "旅行：文化维度理论、文化相对主义、文化冲击、参与式观察、厚描述、第三文化";
            case "TECH_EXPERT" -> "技术：算法复杂度、系统架构、网络协议、分布式系统、技术伦理、银弹思维";
            case "ENGINEER" -> "工程：FMEA故障模式、约束理论、测试金字塔、混沌工程、SRE、MTBF/MTTR";
            case "EDITOR" -> "出版：叙事结构、杀死汝爱、show don't tell、钩子理论、读者画像、陌生化";
            case "BOOK_REVIEWER" -> "书评：期待视野、隐含读者、文化资本、互文性、原型批评、接受美学";
            case "DIPLOMAT" -> "外交：BATNA、ZOPA、囚徒困境、纳什均衡、均势理论、软实力";
            case "LIBRARIAN" -> "图书管理：杜威分类法、分面分类、版本目录学、引文分析、主题阅读、信息素养";
            case "SOCIAL_WORKER" -> "社会工作：优势视角、增权理论、生态系统理论、人在情境中、社会资本、社会支持网";
            case "SPORTS_COACH" -> "体育：周期化训练、超量恢复、刻意练习、目标设置、心理韧性、贝尔宾团队角色";
            case "ANTHROPOLOGIST" -> "人类学：文化相对主义、厚描述、参与式观察、结构人类学、仪式理论、莫斯礼物之灵";
            case "FEMINIST" -> "女性主义：性别展演、男性凝视、情感劳动、立场理论、交叉性、个人即政治";
            case "ECOLOGIST" -> "生态学：生态系统、能量金字塔、盖亚假说、关键种、生态足迹、公地悲剧";
            case "STRATEGIST" -> "战略：知己知彼、不战而屈人之兵、战争论、核威慑理论、海权论、OODA循环";
            default -> "跨学科：综合分析视角";
        };
    }

    private String buildDiscussionTopic(Book book, List<RoundTableMessage> history) {
        StringBuilder topic = new StringBuilder();
        topic.append("关于《").append(book.getTitle()).append("》的讨论");
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 2);
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String content = msg.getCompressedContent() != null ? msg.getCompressedContent() : msg.getContent();
                if (content != null && !content.isBlank()) {
                    topic.append("，最近讨论到：").append(content, 0, Math.min(100, content.length()));
                }
            }
        }
        return topic.toString();
    }

    /**
     * 保存角色发言消息
     */
    private void saveMessage(Long userId, String sessionId, Long bookId,
                             String roleKey, String roleName, String content) {
        try {
            int round = computeRound(sessionId, roleKey);

            RoundTableMessage record = RoundTableMessage.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .bookId(bookId)
                    .roleKey(roleKey)
                    .roleName(roleName)
                    .content(content)
                    .compressedContent(content) // 初始等于原始内容
                    .round(round)
                    .build();
            messageRepository.save(record);
        } catch (Exception e) {
            log.warn("保存圆桌派消息失败: {}", e.getMessage());
        }
    }

    /**
     * 计算消息所属轮次 —— 统一口径 ceil(嘉宾发言数 / 嘉宾数)，HOST 不贡献自己的发言计数。
     * <p>
     * 例：3 位嘉宾时，第 1-3 条嘉宾发言属第 1 轮，第 4 条起进入第 2 轮；
     * HOST 开场/串场/谢幕消息沿用当前轮次（至少 1）。
     */
    private int computeRound(String sessionId, String roleKey) {
        String roleKeys = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .map(RoundTableSession::getRoleKeys)
                .orElse("");
        long guestCount = Arrays.stream(roleKeys.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty() && !"HOST".equals(k))
                .count();
        if (guestCount <= 0) guestCount = 1;

        long guestBefore = messageRepository.countGuestMessages(sessionId);
        long guestAfter = guestBefore + ("HOST".equals(roleKey) ? 0 : 1);
        return (int) Math.max(1, Math.ceil(guestAfter / (double) guestCount));
    }

    /**
     * 更新会话的最后活跃时间
     */
    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .ifPresent(session -> {
                    session.setUpdatedAt(java.time.LocalDateTime.now());
                    sessionRepository.save(session);
                });
    }

    // ==================== 消息加载与压缩 ====================

    /**
     * 加载历史消息并同步压缩，一次 DB 查询完成加载+压缩判断+压缩执行。
     * 避免 {@link #compressHistoryIfNeeded} 加载后调用方再次查询数据库。
     *
     * @param userId               用户ID
     * @param sessionId            会话ID
     * @param currentOverheadChars 当前请求的系统开销字符数（系统提示词、书籍上下文、发言指令等固定部分）
     * @return 压缩后的消息列表，可直接用于构建 ChatMessage
     */
    List<RoundTableMessage> loadAndCompressHistory(Long userId, String sessionId, int currentOverheadChars) {
        // 1. 一次查询加载所有消息
        List<RoundTableMessage> messages = messageRepository.query()
                .where(RoundTableMessage::getUserId, eq(userId))
                .and(RoundTableMessage::getSessionId, eq(sessionId))
                .orderBy(RoundTableMessage::getId)
                .list();
        if (messages.isEmpty()) return messages;

        // 2. 内存计算总字符数（优先 compressedContent）
        long totalChars = messages.stream()
                .mapToLong(m -> {
                    String c = m.getCompressedContent();
                    return c != null ? c.length() : (m.getContent() != null ? m.getContent().length() : 0);
                })
                .sum();

        // 3. 计算触发阈值（从 ROUND_TABLE_SPEECH 场景绑定配置读，跟随场景路由）
        Integer maxTokens = null;
        try {
            AiProviderConfig sceneConfig = aiSceneConfigService.resolveConfig(AiScene.ROUND_TABLE_SPEECH);
            if (sceneConfig != null) {
                maxTokens = sceneConfig.getMaxTokens();
            }
        } catch (Exception e) {
            log.warn("解析 ROUND_TABLE_SPEECH 场景配置失败，回退到默认 maxTokens: {}", e.getMessage());
        }
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);

        // 4. 未达触发阈值，直接返回（不再做预算估算——batch 只有一次 LLM 调用，直接告诉比例即可）
        if (totalChars < charLimit * COMPRESS_TRIGGER_RATIO) {
            return messages;
        }

        // 5. 收集所有未压缩的消息，一次性批量压缩
        List<RoundTableMessage> toCompress = messages.stream()
                .filter(m -> {
                    String compressed = m.getCompressedContent();
                    String original = m.getContent();
                    return compressed == null || original == null || compressed.equals(original);
                })
                .collect(Collectors.toList());

        if (toCompress.isEmpty()) {
            return messages;
        }

        // 6. 一次性批量压缩（单次 LLM 调用），替换原有逐条串行压缩
        List<String> originals = toCompress.stream()
                .map(RoundTableMessage::getContent)
                .toList();
        List<String> summaries = chatHistoryCompressor.compressRoundTableContentBatch(originals);
        if (summaries == null || summaries.size() != toCompress.size()) {
            log.warn("圆桌派批量压缩返回异常(跳过): sessionId={}, expected={}, actual={}",
                    sessionId, toCompress.size(),
                    summaries != null ? summaries.size() : "null");
            return messages;
        }

        int compressed = 0;
        for (int i = 0; i < toCompress.size(); i++) {
            String summary = summaries.get(i);
            if (summary == null || summary.isBlank()) {
                log.warn("圆桌派单条压缩结果为空(跳过): sessionId={}, msgId={}",
                        sessionId, toCompress.get(i).getId());
                continue;
            }
            RoundTableMessage target = toCompress.get(i);
            String original = target.getContent();
            target.setCompressedContent(summary);
            messageRepository.save(target);
            totalChars = totalChars - (original != null ? original.length() : 0) + summary.length();
            compressed++;
            log.info("压缩圆桌派消息: sessionId={}, msgId={}, {}→{} chars, totalChars={}",
                    sessionId, target.getId(), original != null ? original.length() : 0, summary.length(), totalChars);
        }

        if (compressed > 0) {
            log.info("圆桌派压缩完成: sessionId={}, 批量压缩 {} 条（单次 LLM 调用）", sessionId, compressed);
        }

        return messages;
    }

    /**
     * 按需压缩会话历史消息（薄封装，无预加载列表时使用）。
     * <p>
     * 调用方已持有消息列表时应直接使用 {@link #loadAndCompressHistory}，
     * 本方法仅在发言完成后等无预加载场景使用，内部仍会加载一次。
     */
    private void compressHistoryIfNeeded(Long userId, String sessionId, int currentOverheadChars) {
        loadAndCompressHistory(userId, sessionId, currentOverheadChars);
    }

    // ==================== 书籍上下文构建 ====================

    /**
     * 构建静态图书基本信息（不含 RAG，用于 KV Cache 前缀复用）
     */
    private String buildBookInfo(Book book) {
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
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 JSON 数组格式的概念标签
     */
    private Set<String> parseConceptTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(tagsJson.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * HOST 话题进度注入：在书籍上下文末尾追加「已讨论/未讨论」概念清单。
     * <p>
     * 这样主持人在发言时能直接看到哪些概念还没聊到，而不是靠自己从长篇历史中推断。
     */
    private String appendHostTopicProgress(Book book, List<RoundTableMessage> history) {
        Set<String> allConcepts = parseConceptTags(book.getConceptTags());
        if (allConcepts.isEmpty()) return "";

        // 仅看最近 10 轮发言，避免遍历全量历史
        Set<String> discussed = new LinkedHashSet<>();
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            StringBuilder recentText = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                RoundTableMessage msg = history.get(i);
                String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                        ? msg.getCompressedContent() : msg.getContent();
                if (content != null) recentText.append(content);
            }
            String text = recentText.toString();
            for (String concept : allConcepts) {
                if (text.contains(concept)) discussed.add(concept);
            }
        }

        Set<String> undiscussed = new LinkedHashSet<>(allConcepts);
        undiscussed.removeAll(discussed);

        StringBuilder sb = new StringBuilder();
        sb.append("\n【话题进度——主持人专用】\n");

        if (!undiscussed.isEmpty()) {
            sb.append("尚未讨论的概念：").append(String.join("、", undiscussed)).append("\n");
            sb.append("请在本次发言中至少引入一个上述概念，引导嘉宾讨论新话题。\n");
        }
        if (!discussed.isEmpty()) {
            sb.append("已讨论的概念：").append(String.join("、", discussed)).append("\n");
        }
        if (undiscussed.isEmpty()) {
            sb.append("所有概念均已讨论。请换一个全新的切入角度，或引导嘉宾做更深层的交叉关联。\n");
        }

        return sb.toString();
    }

    /**
     * 默认上下文长度（32K tokens）
     */
    private static final int DEFAULT_MAX_TOKENS = 32768;
    /**
     * token → 中文字符换算比例
     */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;
    /**
     * 压缩触发阈值：历史占比超过此比例开始压缩
     */
    private static final double COMPRESS_TRIGGER_RATIO = 0.8;
}
