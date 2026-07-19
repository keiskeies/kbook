package com.kbook.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableCoverage;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableCoverageRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 圆桌派覆盖度服务 — 从 RoundTableCoverageTest 提取的实时版
 * <p>
 * 核心功能：
 * 1. 构建图书内容块（TOC/LLM大纲/密集分块 三层回退，结果缓存到 Redis）
 * 2. 增量计算讨论对内容块的覆盖度（关键词重叠，快速无 LLM 调用）
 * 3. 计算概念标签覆盖度
 * 4. 持久化覆盖度数据到 DB，按 sessionId 更新
 * 5. 为 HOST 生成覆盖度引导文本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundTableCoverageService {

    private final RoundTableCoverageRepository coverageRepository;
    private final RoundTableMessageRepository messageRepository;
    private final RoundTableSessionRepository sessionRepository;
    private final BookRepository bookRepository;
    private final ChatModelManager chatModelManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 内容块最大数量
     */
    private static final int MAX_CONTENT_BLOCKS = 50;
    /**
     * 内容块 Redis 缓存键前缀
     */
    private static final String BLOCKS_CACHE_PREFIX = "kbook:round-table:blocks:";
    /**
     * 内容块缓存 TTL（72 小时，图书不变则内容块不变）
     */
    private static final long BLOCKS_CACHE_TTL_HOURS = 72;

    // ================================================================
    // 圆桌派域 AI 调用
    // ================================================================

    public String callAiForLlmOutline(String contentInfo, int minBlocks, int maxBlocks) {
        String systemPrompt = String.format(AiPromptConstants.LLM_OUTLINE_SYSTEM_PROMPT_TEMPLATE, minBlocks, maxBlocks);

        return chatModelManager.callAiForScene(AiScene.ROUND_TABLE_COVERAGE, "圆桌派覆盖度评估",
                "LLM大纲生成", List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from("图书信息：\n" + contentInfo)));
    }

    // ==================== 公开 API ====================

    /**
     * 更新会话覆盖度（增量）。
     * <p>
     * 每次角色发言完成后调用，只处理新增消息。
     * LLM 综合评估的频控策略：
     * - forHost=true（HOST 发言前）：始终执行，确保覆盖度引导最新
     * - forHost=false（非 HOST 发言后）：每凑够 roleCount 条新消息执行一次
     *
     * @param sessionId 会话ID
     * @param forHost   是否为 HOST 发言前调用
     */
    public RoundTableCoverage updateCoverage(String sessionId, boolean forHost) {
        // 1. 加载已有覆盖度记录
        RoundTableCoverage coverage = coverageRepository.query()
                .where(RoundTableCoverage::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst().orElse(null);

        // 2. 加载所有消息
        List<RoundTableMessage> allMessages = messageRepository.query()
                .where(RoundTableMessage::getSessionId, eq(sessionId))
                .orderBy(RoundTableMessage::getId)
                .list();
        if (allMessages.isEmpty()) return coverage;

        // 3. 增量判断：已处理的消息数
        int processedCount = coverage != null && coverage.getProcessedMessageCount() != null
                ? coverage.getProcessedMessageCount() : 0;
        if (processedCount >= allMessages.size()) {
            return coverage; // 无新消息
        }

        // 4. 获取图书信息
        Long bookId = allMessages.get(0).getBookId();
        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null) return coverage;

        // 5. 获取/构建内容块
        List<ContentBlock> blocks = getOrBuildContentBlocks(book);

        // 6. 构建讨论全文（只取最近消息用于关键词提取）
        String discussionText = buildDiscussionText(allMessages);
        Set<String> discussionKeywords = extractKeyTerms(discussionText);

        // 7. 逐块评估覆盖度（关键词重叠，快速）
        List<BlockCoverageDetail> details = evaluateAllBlocks(blocks, discussionKeywords);

        // 8. 概念标签覆盖度
        List<String> conceptTags = parseJsonArray(book.getConceptTags());
        ConceptCoverageResult conceptResult = evaluateConceptCoverage(discussionText, conceptTags);

        // 9. 计算综合得分
        double blockWeightedScore = computeBlockScore(details, blocks.size());
        double conceptScore = conceptTags.isEmpty() ? 0
                : (double) conceptResult.covered.size() / conceptTags.size() * 100;

        // 10. 获取会话信息，计算最小轮次（角色数 × 2）
        RoundTableSession session = sessionRepository.query()
                .where(RoundTableSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst().orElse(null);
        int roleCount = 4; // 默认4个角色
        if (session != null && session.getRoleKeys() != null && !session.getRoleKeys().isBlank()) {
            roleCount = session.getRoleKeys().split(",").length;
        }
        int minRoundsForLlm = roleCount * 2;

        // 11. LLM 综合评估（消息 >= 角色数×2 条时执行，较慢）
        // 频控：forHost（HOST 发言前）每次都跑；非 HOST 每 roleCount 条新消息跑一次
        LlmAssessmentResult llmResult = null;
        if (allMessages.size() >= minRoundsForLlm) {
            boolean shouldRunLlm = forHost;
            if (!shouldRunLlm && coverage != null) {
                int lastLlmCount = coverage.getLlmMessageCount() != null
                        ? coverage.getLlmMessageCount() : 0;
                shouldRunLlm = (allMessages.size() - lastLlmCount) >= roleCount;
            }
            if (shouldRunLlm) {
                try {
                    llmResult = runLlmAssessment(allMessages, book);
                } catch (Exception e) {
                    log.warn("LLM 综合评估失败: sessionId={} - {}", sessionId, e.getMessage());
                }
            }
        }

        // 12. 综合得分：内容块 40% + 概念标签 20% + LLM评估 40%（有LLM时）
        double overallScore;
        if (llmResult != null && llmResult.assessmentScore != null) {
            overallScore = blockWeightedScore * 0.4 + conceptScore * 0.2 + llmResult.assessmentScore * 0.4;
        } else {
            overallScore = conceptTags.isEmpty()
                    ? blockWeightedScore
                    : blockWeightedScore * 0.7 + conceptScore * 0.3;
        }
        overallScore = Math.min(100, Math.max(0, overallScore));

        String grade = computeGrade(overallScore);

        // 10. 持久化
        if (coverage == null) {
            coverage = RoundTableCoverage.builder()
                    .sessionId(sessionId)
                    .bookId(bookId)
                    .build();
        }

        coverage.setTotalBlocks(blocks.size());
        coverage.setCoveredBlocks((int) details.stream().filter(d -> d.coverageLevel >= 1).count());
        coverage.setDeepBlocks((int) details.stream().filter(d -> d.coverageLevel >= 2).count());
        coverage.setBlockCoverageScore(blockWeightedScore);
        coverage.setTotalConcepts(conceptTags.size());
        coverage.setCoveredConceptsCount(conceptResult.covered.size());
        coverage.setConceptCoverageScore(conceptScore);
        coverage.setOverallScore(overallScore);
        coverage.setGrade(grade);
        coverage.setProcessedMessageCount(allMessages.size());

        try {
            coverage.setBlocksJson(objectMapper.writeValueAsString(blocks));
            coverage.setBlockDetailsJson(objectMapper.writeValueAsString(details));
            coverage.setCoveredConceptsJson(objectMapper.writeValueAsString(conceptResult.covered));
            coverage.setMissedConceptsJson(objectMapper.writeValueAsString(conceptResult.missed));
            if (llmResult != null) {
                coverage.setLlmDimensionsJson(objectMapper.writeValueAsString(llmResult.dimensions));
                coverage.setLlmStrengthsJson(objectMapper.writeValueAsString(llmResult.strengths));
                coverage.setLlmWeaknessesJson(objectMapper.writeValueAsString(llmResult.weaknesses));
                coverage.setLlmSuggestionsJson(objectMapper.writeValueAsString(llmResult.suggestions));
                coverage.setLlmAssessmentScore(llmResult.assessmentScore);
                coverage.setLlmMessageCount(allMessages.size());
            }
        } catch (Exception e) {
            log.warn("序列化覆盖度数据失败: {}", e.getMessage());
        }

        coverage = coverageRepository.save(coverage);
        log.info("覆盖度更新: sessionId={}, overall={}, blocks={}/{}, concepts={}/{}",
                sessionId, overallScore, coverage.getCoveredBlocks(), blocks.size(),
                conceptResult.covered.size(), conceptTags.size());

        return coverage;
    }

    /**
     * 为 HOST 生成覆盖度引导文本，注入到 bookContext 中。
     * <p>
     * 传递完整信息：未覆盖块的标题+摘要+关键词、未覆盖概念、LLM评估不足/建议。
     * 让 HOST 能精准知道"该聊什么"和"哪里聊得不够"。
     */
    public String buildHostCoverageGuidance(String sessionId) {
        RoundTableCoverage coverage = coverageRepository.query()
                .where(RoundTableCoverage::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst().orElse(null);
        if (coverage == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n【话题覆盖度——主持人专用】\n");

        // 1. 未覆盖的内容块（带摘要和关键词）
        List<BlockCoverageDetail> details = parseJson(coverage.getBlockDetailsJson(),
                new TypeReference<List<BlockCoverageDetail>>() {
                });
        List<ContentBlock> blocks = parseJson(coverage.getBlocksJson(),
                new TypeReference<List<ContentBlock>>() {
                });

        if (details != null && blocks != null) {
            List<String> uncoveredLines = new ArrayList<>();
            List<String> mentionedLines = new ArrayList<>();
            for (int i = 0; i < details.size() && i < blocks.size(); i++) {
                ContentBlock block = blocks.get(i);
                BlockCoverageDetail detail = details.get(i);
                // 构建信息行：标题 + 摘要(如有) + 关键词(如有)
                String line = block.title;
                if (block.summary != null && !block.summary.isBlank()) {
                    line += "——" + CommonUtils.truncateText(block.summary, 60);
                } else if (block.keywords != null && !block.keywords.isEmpty()) {
                    String kwStr = block.keywords.stream().limit(5).collect(Collectors.joining("、"));
                    line += "（关键词：" + kwStr + "）";
                }
                if (detail.coverageLevel == 0) {
                    uncoveredLines.add(line);
                } else if (detail.coverageLevel == 1) {
                    mentionedLines.add(line);
                }
            }

            if (!uncoveredLines.isEmpty()) {
                sb.append("尚未讨论的主题（").append(uncoveredLines.size()).append("个）：\n");
                for (String line : uncoveredLines.stream().limit(8).toList()) {
                    sb.append("  · ").append(line).append("\n");
                }
                sb.append("请优先引导嘉宾讨论上述未覆盖的主题。\n");
            }
            if (!mentionedLines.isEmpty()) {
                sb.append("仅提及但未深入的主题（").append(mentionedLines.size()).append("个）：\n");
                for (String line : mentionedLines.stream().limit(6).toList()) {
                    sb.append("  · ").append(line).append("\n");
                }
                sb.append("这些主题可以进一步深挖。\n");
            }
        }

        // 2. 未覆盖的概念标签
        List<String> missedConcepts = parseJson(coverage.getMissedConceptsJson(),
                new TypeReference<List<String>>() {
                });
        if (missedConcepts != null && !missedConcepts.isEmpty()) {
            sb.append("尚未涉及的概念：").append(String.join("、", missedConcepts)).append("\n");
        }

        // 3. LLM 评估不足和建议
        List<String> weaknesses = parseJson(coverage.getLlmWeaknessesJson(),
                new TypeReference<List<String>>() {
                });
        List<String> suggestions = parseJson(coverage.getLlmSuggestionsJson(),
                new TypeReference<List<String>>() {
                });
        if (weaknesses != null && !weaknesses.isEmpty()) {
            sb.append("讨论不足：");
            sb.append(String.join("；", weaknesses.stream().limit(3).toList())).append("\n");
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            sb.append("改进方向：");
            sb.append(String.join("；", suggestions.stream().limit(3).toList())).append("\n");
        }

        // 4. 覆盖度概览
        sb.append(String.format("当前覆盖度：%.0f%%（等级 %s），内容块 %d/%d 已覆盖\n",
                coverage.getOverallScore() != null ? coverage.getOverallScore() : 0,
                coverage.getGrade() != null ? coverage.getGrade() : "-",
                coverage.getCoveredBlocks() != null ? coverage.getCoveredBlocks() : 0,
                coverage.getTotalBlocks() != null ? coverage.getTotalBlocks() : 0));

        return sb.toString();
    }

    /**
     * 获取会话覆盖度记录
     */
    public RoundTableCoverage getCoverage(String sessionId) {
        return coverageRepository.query()
                .where(RoundTableCoverage::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst().orElse(null);
    }

    // ==================== 内容块构建（三层回退 + Redis 缓存） ====================

    /**
     * 获取或构建内容块（缓存到 Redis）
     */
    private List<ContentBlock> getOrBuildContentBlocks(Book book) {
        String cacheKey = BLOCKS_CACHE_PREFIX + book.getId();
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                List<ContentBlock> blocks = objectMapper.readValue(cached,
                        new TypeReference<List<ContentBlock>>() {
                        });
                if (blocks != null && blocks.size() >= 3) {
                    return blocks;
                }
            }
        } catch (Exception e) {
            log.warn("读取内容块缓存失败: bookId={} - {}", book.getId(), e.getMessage());
        }

        // 构建（基于图书已有摘要信息，不 scroll Qdrant）
        List<ContentBlock> blocks = buildContentBlocks(book);

        // 缓存
        if (!blocks.isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(blocks);
                stringRedisTemplate.opsForValue().set(cacheKey, json,
                        BLOCKS_CACHE_TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("缓存内容块失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }

        return blocks;
    }

    /**
     * 构建内容块 — 三层回退策略（基于图书已有摘要信息，无需 Qdrant scroll）
     */
    private List<ContentBlock> buildContentBlocks(Book book) {
        // Tier 1: TOC-based（使用 book.toc + book.chapterSummary）
        List<ContentBlock> tocBlocks = buildBlocksFromToc(book);
        if (tocBlocks.size() >= 3) return tocBlocks;

        // Tier 2: LLM-outline（使用 book.compressedSummary + description 替代 chunk 采样）
        List<ContentBlock> llmBlocks = buildBlocksFromLlmOutline(book);
        if (llmBlocks.size() >= 3) return llmBlocks;

        // Tier 3: 从图书描述/精炼摘要兜底
        return buildBlocksFromBookSummary(book);
    }

    /**
     * Tier 1: 从 TOC 构建内容块（基于 book.toc + book.chapterSummary，无需 Qdrant scroll）
     * <p>
     * 过滤掉封面、目录、前言、后记等非正文章节，保留有实质内容的章节。
     */
    private List<ContentBlock> buildBlocksFromToc(Book book) {
        String toc = book.getToc();
        if (toc == null || toc.isBlank()) return new ArrayList<>();

        String[] chapterLines = toc.split("\n");
        List<String> chapterTitles = Arrays.stream(chapterLines)
                .map(String::trim)
                .filter(line -> line.length() >= 2 && !line.matches("^[\\d\\s.]+$"))
                .filter(line -> !isNonContentChapter(line)) // 过滤非正文章节
                .distinct()
                .collect(Collectors.toList());

        // 如果过滤后太少（<3），说明可能过滤太严格，返回空让 Tier 2 处理
        if (chapterTitles.isEmpty() || chapterTitles.size() < 3) {
            log.debug("TOC 过滤后章节数太少({})，回退到 LLM 大纲", chapterTitles.size());
            return new ArrayList<>();
        }

        if (chapterTitles.size() > MAX_CONTENT_BLOCKS) {
            chapterTitles = mergeChapters(chapterTitles);
        }

        Map<String, String> chapterSummaryMap = parseChapterSummary(book.getChapterSummary());

        List<ContentBlock> blocks = new ArrayList<>();
        for (String title : chapterTitles) {
            String summary = chapterSummaryMap.getOrDefault(title, "");
            // 用章节摘要作为代表文本（替代 Qdrant chunk 文本）
            String representativeText = summary.isEmpty() ? title : summary;
            if (representativeText.length() > 300) {
                representativeText = representativeText.substring(0, 300);
            }

            Set<String> keywords = extractKeyTerms(title + " " + summary + " " + representativeText);

            blocks.add(ContentBlock.builder()
                    .title(title)
                    .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                    .representativeText(representativeText)
                    .chunkCount(0)
                    .keywords(keywords)
                    .source("toc")
                    .build());
        }

        return blocks;
    }

    /**
     * 判断是否为非正文章节（封面、目录、前言、后记等）
     * <p>
     * 基于规则匹配，比 LLM 更快。如果过滤后数量太少，会回退到 LLM 大纲。
     */
    private boolean isNonContentChapter(String title) {
        if (title == null || title.isBlank()) return true;
        String lower = title.toLowerCase().trim();

        // 常见非正文章节关键词（中英文）
        Set<String> nonContentKeywords = Set.of(
                // 封面/扉页
                "封面", "扉页", "版权页", "版权信息", "版权声明", "封面设计",
                "cover", "front cover", "title page",

                // 目录
                "目录", "目次", "contents", "table of contents", "toc",

                // 前言/序
                "前言", "序", "序言", "自序", "代序", "编者序", "序一", "序二",
                "preface", "foreword", "introduction", "prologue",

                // 致谢
                "致谢", "鸣谢", "感谢", "acknowledgments", "acknowledgement",

                // 附录
                "附录", "附录一", "附录二", "附录a", "附录b",
                "appendix", "appendices", "appendix a", "appendix b",

                // 索引
                "索引", "index", "主题索引", "人名索引",

                // 后记/跋
                "后记", "跋", "postscript", "afterword", "epilogue",

                // 参考文献
                "参考文献", "参考书目", "引用文献", "references", "bibliography",

                // 术语表
                "术语表", "词汇表", "glossary", "terminology",

                // 其他
                "注释", "注", "notes", "footnotes",
                "版权", "copyright", "法律声明", "disclaimer"
        );

        // 完全匹配
        if (nonContentKeywords.contains(lower)) return true;

        // 前缀匹配（如"第一章 目录"）
        for (String keyword : nonContentKeywords) {
            if (lower.startsWith(keyword) || lower.startsWith(keyword + "：") || lower.startsWith(keyword + ":")) {
                return true;
            }
        }

        // 后缀匹配（如"目录页"、"目录索引"）
        for (String keyword : nonContentKeywords) {
            if (lower.endsWith(keyword)) {
                return true;
            }
        }

        // 包含匹配（如"致读者"、"关于本书"）
        Set<String> containsKeywords = Set.of(
                "致读者", "关于本书", "出版说明", "编者的话", "译者序", "译者注",
                "about this book", "translator's note", "editor's note"
        );
        for (String keyword : containsKeywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tier 2: 从 LLM 大纲构建内容块（使用 book.compressedSummary/description/chapterSummary 替代 Qdrant chunk 采样）
     */
    private List<ContentBlock> buildBlocksFromLlmOutline(Book book) {
        // 构建图书摘要信息（替代 chunk 采样）
        StringBuilder contentInfo = new StringBuilder();
        contentInfo.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null) contentInfo.append("作者：").append(book.getAuthor()).append("\n");
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            contentInfo.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 300)).append("\n");
        }
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            contentInfo.append("精炼摘要：").append(book.getCompressedSummary()).append("\n");
        }
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            contentInfo.append("章节摘要：").append(CommonUtils.truncateText(book.getChapterSummary(), 1000)).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String tags = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            contentInfo.append("核心概念：").append(tags).append("\n");
        }
        if (contentInfo.isEmpty()) return new ArrayList<>();

        int targetBlocks = Math.min(MAX_CONTENT_BLOCKS, 30);
        int minBlocks = Math.max(5, targetBlocks / 2);

        String result = callAiForLlmOutline(
                contentInfo.toString().trim(), minBlocks, targetBlocks);
        if (result == null) return new ArrayList<>();

        result = CommonUtils.stripCodeFence(result);
        List<ContentBlock> blocks = new ArrayList<>();
        try {
            // Markdown 解析：按 ### 标题 切分块，标题后到下一个 ### 之间为摘要
            // 兼容 JSON 兜底：LLM 偶发仍输出 JSON 数组时也能解析
            if (result.trim().startsWith("[")) {
                var arr = objectMapper.readTree(result);
                if (arr != null && arr.isArray()) {
                    for (var node : arr) {
                        String title = node.has("title") ? node.get("title").asText() : "未知主题";
                        String summary = node.has("summary") ? node.get("summary").asText() : "";
                        blocks.add(buildOutlineBlock(title, summary));
                    }
                }
            } else {
                // Markdown 行式解析：按 ### 标题 切块
                String[] parts = result.split("(?=\\n\\s*###\\s)");
                Pattern titlePat = Pattern.compile("^\\s*###\\s+(.+?)\\s*$", Pattern.MULTILINE);
                for (String part : parts) {
                    Matcher tm = titlePat.matcher(part);
                    if (!tm.find()) continue;
                    String title = tm.group(1).trim();
                    // 摘要 = 标题行之后的所有内容
                    String summary = part.substring(tm.end()).trim();
                    blocks.add(buildOutlineBlock(title, summary));
                }
            }
        } catch (Exception e) {
            log.warn("LLM 大纲解析失败: {}", e.getMessage());
        }

        return blocks;
    }

    /** 构建大纲块，统一截断长度 */
    private ContentBlock buildOutlineBlock(String title, String summary) {
        Set<String> keywords = extractKeyTerms(title + " " + summary);
        String representativeText = summary.isEmpty() ? title : summary;
        if (representativeText.length() > 300) {
            representativeText = representativeText.substring(0, 300);
        }
        return ContentBlock.builder()
                .title(title.length() > 50 ? title.substring(0, 50) : title)
                .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                .representativeText(representativeText)
                .chunkCount(0)
                .keywords(keywords)
                .source("llm_outline")
                .build();
    }

    /**
     * Tier 3: 从图书描述/精炼摘要兜底构建内容块（无需 Qdrant scroll）
     */
    private List<ContentBlock> buildBlocksFromBookSummary(Book book) {
        StringBuilder allText = new StringBuilder();
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            allText.append(book.getDescription()).append("\n");
        }
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            allText.append(book.getCompressedSummary()).append("\n");
        }
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            allText.append(book.getChapterSummary());
        }
        if (allText.isEmpty()) return new ArrayList<>();

        // 按句子/段落分块
        String[] sentences = allText.toString().split("[。！？\\n]+");
        List<String> validSentences = Arrays.stream(sentences)
                .map(String::trim)
                .filter(s -> s.length() >= 10)
                .toList();

        if (validSentences.isEmpty()) return new ArrayList<>();

        int groupSize = Math.max(1, validSentences.size() / MAX_CONTENT_BLOCKS);
        List<ContentBlock> blocks = new ArrayList<>();

        for (int i = 0; i < validSentences.size(); i += groupSize) {
            int end = Math.min(i + groupSize, validSentences.size());
            String combined = String.join("。", validSentences.subList(i, end));
            String preview = combined.length() > 300 ? combined.substring(0, 300) : combined;
            Set<String> keywords = extractKeyTerms(preview);

            String title = keywords.stream().limit(3).collect(Collectors.joining("·"));
            if (title.isBlank()) title = "主题 " + (blocks.size() + 1);

            blocks.add(ContentBlock.builder()
                    .title(title)
                    .summary(preview.length() > 150 ? preview.substring(0, 150) : preview)
                    .representativeText(preview)
                    .chunkCount(0)
                    .keywords(keywords)
                    .source("book_summary")
                    .build());
        }

        return blocks;
    }

    // ==================== 覆盖度评估（快速，无 LLM） ====================

    /**
     * 评估所有内容块的覆盖度（关键词重叠法，快速无 LLM 调用）
     */
    private List<BlockCoverageDetail> evaluateAllBlocks(List<ContentBlock> blocks,
                                                        Set<String> discussionKeywords) {
        List<BlockCoverageDetail> details = new ArrayList<>();
        for (ContentBlock block : blocks) {
            details.add(evaluateSingleBlock(block, discussionKeywords));
        }
        return details;
    }

    /**
     * 评估单个内容块的覆盖度
     */
    private BlockCoverageDetail evaluateSingleBlock(ContentBlock block, Set<String> discussionKeywords) {
        double keywordOverlap = 0;
        if (!block.keywords.isEmpty() && !discussionKeywords.isEmpty()) {
            long overlap = block.keywords.stream()
                    .filter(discussionKeywords::contains)
                    .count();
            keywordOverlap = (double) overlap / block.keywords.size();
        }

        int coverageLevel;
        double score;
        String evidence;

        if (keywordOverlap >= 0.25) {
            coverageLevel = keywordOverlap >= 0.40 ? 3 : (keywordOverlap >= 0.30 ? 2 : 1);
            score = Math.min(100, keywordOverlap * 200);
            evidence = String.format("关键词重叠率 %.0f%%", keywordOverlap * 100);
        } else if (keywordOverlap > 0.10) {
            coverageLevel = 1;
            score = keywordOverlap * 100;
            evidence = String.format("关键词部分重叠 %.0f%%", keywordOverlap * 100);
        } else if (keywordOverlap > 0) {
            coverageLevel = 0;
            score = keywordOverlap * 100;
            evidence = String.format("关键词重叠不足 %.0f%%", keywordOverlap * 100);
        } else {
            coverageLevel = 0;
            score = 0;
            evidence = "无关键词重叠";
        }

        return BlockCoverageDetail.builder()
                .title(block.title)
                .coverageLevel(coverageLevel)
                .score(score)
                .keywordOverlap(keywordOverlap)
                .judgeMethod("keyword")
                .evidence(evidence)
                .build();
    }

    /**
     * 评估概念标签覆盖度
     */
    private ConceptCoverageResult evaluateConceptCoverage(String discussionText, List<String> conceptTags) {
        List<String> covered = new ArrayList<>();
        List<String> missed = new ArrayList<>();

        for (String tag : conceptTags) {
            if (discussionText.contains(tag)) {
                covered.add(tag);
            } else {
                missed.add(tag);
            }
        }

        return new ConceptCoverageResult(covered, missed);
    }

    // ==================== 得分计算 ====================

    private double computeBlockScore(List<BlockCoverageDetail> details, int totalBlocks) {
        if (totalBlocks == 0) return 0;
        long coveredCount = details.stream().filter(d -> d.coverageLevel >= 1).count();
        long deepCount = details.stream().filter(d -> d.coverageLevel >= 2).count();
        return (deepCount * 1.0 + (coveredCount - deepCount) * 0.5) / totalBlocks * 100;
    }

    private String computeGrade(double overall) {
        if (overall >= 85) return "S";
        if (overall >= 70) return "A";
        if (overall >= 55) return "B";
        if (overall >= 40) return "C";
        if (overall >= 25) return "D";
        return "F";
    }

    // ==================== 工具方法 ====================

    // ==================== LLM 综合评估（算法3） ====================

    /**
     * LLM 综合评估 — 6维度评分 + 强项/不足/建议
     * <p>
     * 消息数 >= 6 时才执行，避免早期无意义的评估。
     */
    private LlmAssessmentResult runLlmAssessment(List<RoundTableMessage> messages, Book book) {
        if (messages.size() < 6) return null;

        // 静态书籍信息（同书复用 KV Cache）
        StringBuilder bookInfo = new StringBuilder();
        bookInfo.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null) bookInfo.append("作者：").append(book.getAuthor()).append("\n");
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            bookInfo.append("简介：").append(CommonUtils.truncateText(book.getDescription(), 500)).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String tags = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            bookInfo.append("核心概念：").append(tags).append("\n");
        }
        if (book.getCompressedSummary() != null && !book.getCompressedSummary().isBlank()) {
            bookInfo.append("精炼摘要：").append(book.getCompressedSummary()).append("\n");
        }

        String discussionSummary = buildDiscussionSummary(messages);

        // 固定角色 + 评估维度作为 SystemMessage
        // 输出格式：Markdown（比 JSON 更稳定，reason 含中文引号/换行时不易破坏结构）
        String systemPrompt = """
                你是一位专业的书籍讨论评估专家。请评估圆桌派讨论对该书内容的覆盖程度，从以下维度评分（每项 0-10 分，可保留一位小数）：
                1. 广度覆盖 (Breadth)：讨论触及了多少个书中的核心主题/概念？
                2. 深度挖掘 (Depth)：对已触及的主题，讨论的深度如何？是否只是表面提及？
                3. 观点碰撞 (Debate)：不同角色的观点是否形成有效碰撞和辩驳？
                4. 文本关联 (TextAnchor)：讨论是否紧密围绕书中的具体内容、案例或论述展开？
                5. 批判思辨 (CriticalThinking)：是否有对书中观点提出质疑、反思或延伸思考？
                6. 整体覆盖度 (Overall)：综合评估，讨论在多大程度上覆盖了此书的核心内容？

                严格按以下 Markdown 格式输出，不要输出 JSON、不要输出代码块围栏、不要额外解释：

                ## 维度评分

                ### 广度覆盖
                - 评分：N
                - 理由：xxx

                ### 深度挖掘
                - 评分：N
                - 理由：xxx

                ### 观点碰撞
                - 评分：N
                - 理由：xxx

                ### 文本关联
                - 评分：N
                - 理由：xxx

                ### 批判思辨
                - 评分：N
                - 理由：xxx

                ### 整体覆盖度
                - 评分：N
                - 理由：xxx

                ## 强项
                - xxx
                - xxx

                ## 不足
                - xxx
                - xxx

                ## 改进建议
                - xxx
                - xxx

                规则：
                - 「### 维度名」必须严格使用上述 6 个维度名，原样输出
                - 「- 评分：」后跟 0-10 之间的数字（可带一位小数），不要带「分」字
                - 「- 理由：」后跟一句话说明，不要换行
                - 「强项/不足/改进建议」每条以「- 」开头独占一行，至少 1 条
                """;

        // 消息顺序优化 KV Cache：SystemMessage(固定指令) → UserMessage(图书信息) → UserMessage(讨论摘要)
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(SystemMessage.from(systemPrompt));
        chatMessages.add(UserMessage.from("【图书信息】\n" + bookInfo.toString().trim()));
        chatMessages.add(UserMessage.from("【讨论实录（摘要）】\n" + discussionSummary));

        String result = chatModelManager.callAiForScene(AiScene.ROUND_TABLE_COVERAGE,
                "圆桌派覆盖度评估",
                "LLM综合评估",
                chatMessages);
        if (result == null) return null;

        result = CommonUtils.stripCodeFence(result);
        try {
            // Markdown 解析：## 维度评分 区段下按 ### 维度名 切分,## 强项/不足/改进建议 按列表项解析
            String dimsSection = CommonUtils.extractMarkdownSection(result, "维度评分");
            Map<String, Double> dimensionScores = new LinkedHashMap<>();
            double totalScore = 0;
            int count = 0;
            if (dimsSection != null) {
                // 按 ### 维度名 切分
                String[] dimBlocks = dimsSection.split("(?=\\n\\s*###\\s)");
                Pattern dimPat = Pattern.compile("^\\s*###\\s+(.+?)\\s*$", Pattern.MULTILINE);
                for (String block : dimBlocks) {
                    Matcher dm = dimPat.matcher(block);
                    if (!dm.find()) continue;
                    // 维度名可能带英文后缀如「广度覆盖 (Breadth)」,取主名
                    String dimName = dm.group(1).trim().replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
                    double score = extractScoreFromMarkdown(block);
                    dimensionScores.put(dimName, score);
                    totalScore += score;
                    count++;
                }
            }

            // 解析不到任何维度 → 视为解析失败,返回 null 让调用方走 fallback
            if (count == 0) {
                log.warn("LLM 评估未解析到维度评分,返回 null: result={}", result);
                return null;
            }

            double overallDimScore = dimensionScores.getOrDefault("整体覆盖度",
                    count > 0 ? totalScore / count : 0);

            List<String> strengths = parseMarkdownListSection(result, "强项");
            List<String> weaknesses = parseMarkdownListSection(result, "不足");
            List<String> suggestions = parseMarkdownListSection(result, "改进建议");

            LlmAssessmentResult assessment = new LlmAssessmentResult();
            assessment.setDimensions(dimensionScores);
            assessment.setStrengths(strengths);
            assessment.setWeaknesses(weaknesses);
            assessment.setSuggestions(suggestions);
            assessment.setAssessmentScore(overallDimScore * 10); // 0-100

            log.debug("LLM 评估完成: overallDim={}, dimensions={}", overallDimScore, dimensionScores);
            return assessment;
        } catch (Exception e) {
            log.warn("LLM 评估结果解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 Markdown 维度块中提取「- 评分：N」的数字。
     * 兼容：
     * - 「- 评分：7.5」「- 评分: 7.5」「- 评分：7.5分」（去掉"分"字）
     * - 「- score: 7.5」英文 key
     * - 无前缀的「评分：7.5」「评分:7.5」
     * 兜底：取块内第一个 0-10 范围的数字（避免取到理由中的数字,只在评分行匹配失败时使用）
     */
    private double extractScoreFromMarkdown(String block) {
        if (block == null) return 0;
        // 主匹配：带列表前缀的「- 评分：N」
        Pattern p = Pattern.compile("(?im)^\\s*[-*]\\s*(?:评分|score)\\s*[:：]\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(block);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        // 次匹配：无列表前缀的「评分：N」（LLM 可能漏写「-」）
        Pattern p2 = Pattern.compile("(?im)^\\s*(?:评分|score)\\s*[:：]\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher m2 = p2.matcher(block);
        if (m2.find()) {
            try {
                return Double.parseDouble(m2.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        // 兜底：取块内第一个 0-10 范围的数字（跳过维度名行,避免误取）
        // 注意：这是最后兜底,如果理由中有数字会取错,但主/次匹配通常能命中
        Pattern numP = Pattern.compile("\\b([0-9]{1,2}(?:\\.[0-9]+)?)\\b");
        Matcher numM = numP.matcher(block);
        // 跳过第一行(维度名行,可能含数字如"5 个维度")
        String[] lines = block.split("\\r?\\n", 2);
        String searchArea = lines.length > 1 ? lines[1] : block;
        numM = numP.matcher(searchArea);
        while (numM.find()) {
            try {
                double v = Double.parseDouble(numM.group(1));
                if (v >= 0 && v <= 10) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * 从 Markdown 文本中提取指定二级标题（## xxx）下的列表项。
     * 兼容「- item」「* item」「• item」以及无前缀的纯行。
     */
    private List<String> parseMarkdownListSection(String text, String header) {
        List<String> result = new ArrayList<>();
        String section = CommonUtils.extractMarkdownSection(text, header);
        if (section == null || section.isBlank()) return result;
        for (String line : section.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 去掉列表前缀
            String item = trimmed.replaceFirst("^[-*•]\\s+", "");
            if (item.isEmpty()) continue;
            // 跳过误入的标题行
            if (item.startsWith("#")) continue;
            result.add(item);
        }
        return result;
    }


    private String buildDiscussionSummary(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank()) {
                String truncated = CommonUtils.truncateText(content, 200);
                sb.append(msg.getRoleName()).append("：").append(truncated).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildDiscussionText(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank()) {
                sb.append(content).append(" ");
            }
        }
        return sb.toString();
    }

    private Set<String> extractKeyTerms(String text) {
        if (text == null || text.isBlank()) return new HashSet<>();
        Set<String> terms = new HashSet<>();
        Pattern p = Pattern.compile("[\\u4e00-\\u9fa5]{2,6}");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (!STOP_WORDS.contains(word)) terms.add(word);
        }
        return terms;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "可以", "一个", "这个", "那个", "什么", "我们", "他们", "你们",
            "就是", "不是", "没有", "如果", "因为", "所以", "但是", "而且",
            "或者", "虽然", "然后", "这样", "那样", "怎么", "如何", "为什么",
            "时候", "方式", "东西", "事情", "地方", "问题", "关系", "情况",
            "部分", "方面", "能力", "水平", "程度", "特点", "开始", "继续",
            "可能", "应该", "需要", "能够", "已经", "正在", "将会",
            "这些", "那些", "这里", "那里", "这种", "那种",
            "这么", "那么", "一些", "很多", "大量", "主要", "基本", "重要",
            "不同", "一样", "完全", "非常", "比较", "相当", "特别", "甚至",
            "以及", "还有", "包括", "关于", "对于", "通过", "根据", "按照"
    );

    private List<String> mergeChapters(List<String> titles) {
        List<String> merged = new ArrayList<>();
        int groupSize = (int) Math.ceil((double) titles.size() / RoundTableCoverageService.MAX_CONTENT_BLOCKS);
        for (int i = 0; i < titles.size(); i += groupSize) {
            int end = Math.min(i + groupSize, titles.size());
            if (end - i == 1) {
                merged.add(titles.get(i));
            } else {
                merged.add(titles.get(i) + " 等" + (end - i) + "节");
            }
        }
        return merged;
    }

    private Map<String, String> parseChapterSummary(String chapterSummary) {
        Map<String, String> map = new LinkedHashMap<>();
        if (chapterSummary == null || chapterSummary.isBlank()) return map;

        String[] parts = chapterSummary.split("\n(?=[第章节]|\\d+[.、])");
        String currentTitle = null;
        StringBuilder currentSummary = new StringBuilder();

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.matches("^第[一二三四五六七八九十百千]+章.*")
                    || part.matches("^\\d+[.、].*")
                    || part.matches("^[A-Z][a-z]+.*")
                    || part.length() < 30) {
                if (currentTitle != null && !currentSummary.isEmpty()) {
                    map.put(currentTitle, currentSummary.toString().trim());
                }
                currentTitle = part.length() > 50 ? part.substring(0, 50) : part;
                currentSummary = new StringBuilder();
            } else {
                currentSummary.append(part).append("\n");
            }
        }
        if (currentTitle != null && !currentSummary.isEmpty()) {
            map.put(currentTitle, currentSummary.toString().trim());
        }
        return map;
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部数据类型 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentBlock {
        private String title;
        private String summary;
        private String representativeText;
        private int chunkCount;
        private Set<String> keywords;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockCoverageDetail {
        private String title;
        /**
         * 覆盖等级: 0=未覆盖, 1=提及, 2=部分讨论, 3=深入讨论
         */
        private int coverageLevel;
        private double score;
        private double keywordOverlap;
        private String judgeMethod;
        private String evidence;
    }

    public record ConceptCoverageResult(List<String> covered, List<String> missed) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmAssessmentResult {
        /**
         * 6维度评分 {"广度覆盖":6.0, "深度挖掘":8.0, ...}
         */
        private Map<String, Double> dimensions;
        /**
         * 强项列表
         */
        private List<String> strengths;
        /**
         * 不足列表
         */
        private List<String> weaknesses;
        /**
         * 改进建议
         */
        private List<String> suggestions;
        /**
         * LLM 综合评估得分 0-100
         */
        private Double assessmentScore;
    }
}
