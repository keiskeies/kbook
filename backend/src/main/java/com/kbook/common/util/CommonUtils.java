package com.kbook.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/**
 * 通用工具类 - 图片处理、文件服务等
 */
@Slf4j
public class CommonUtils {

    private CommonUtils() {
        // 防止实例化
    }

    /**
     * 估算文本的 token 数量
     * 粗略估算：中文约1字符=1token，英文约4字符=1token
     *
     * @param text 待估算的文本
     * @return 估算的token数量
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        // 中文字符按1:1估算，其他字符按4:1估算
        return chineseChars + (otherChars / 4);
    }

    /**
     * 等比例压缩图片，限制最大宽度
     *
     * @param srcImage 源图片
     * @param format   图片格式（png/jpg/gif/webp）
     * @param maxWidth 最大宽度（像素）
     * @return 压缩后的图片
     */
    public static BufferedImage compressImage(BufferedImage srcImage, String format, int maxWidth) {
        int srcWidth = srcImage.getWidth();
        int srcHeight = srcImage.getHeight();

        if (srcWidth <= maxWidth) {
            return srcImage; // 不需要压缩
        }

        double scale = (double) maxWidth / srcWidth;
        int newHeight = (int) Math.round(srcHeight * scale);

        BufferedImage resized = new BufferedImage(maxWidth, newHeight,
                format.equalsIgnoreCase("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(srcImage, 0, 0, maxWidth, newHeight, null);
        g2d.dispose();

        log.debug("图片压缩: {}x{} -> {}x{}", srcWidth, srcHeight, maxWidth, newHeight);
        return resized;
    }

    /**
     * 等比例压缩封面图片（兼容性方法）
     *
     * @param srcImage 源图片
     * @param format   图片格式
     * @param maxWidth 最大宽度
     * @return 压缩后的图片
     * @deprecated 使用 {@link #compressImage(BufferedImage, String, int)}
     */
    @Deprecated
    public static BufferedImage compressCover(BufferedImage srcImage, String format, int maxWidth) {
        return compressImage(srcImage, format, maxWidth);
    }

    /**
     * 生成缩略图，等比例缩放至 maxWidth×maxHeight 范围内
     *
     * @param originalPath  原图路径
     * @param thumbnailPath 缩略图输出路径
     * @param maxWidth      最大宽度（像素）
     * @param maxHeight     最大高度（像素）
     */
    public static void generateThumbnail(Path originalPath, Path thumbnailPath, int maxWidth, int maxHeight) throws IOException {
        BufferedImage srcImage = ImageIO.read(originalPath.toFile());
        if (srcImage == null) {
            throw new IOException("无法读取图片: " + originalPath);
        }

        int srcWidth = srcImage.getWidth();
        int srcHeight = srcImage.getHeight();

        // 计算缩放比例，保持宽高比，限制在 maxWidth×maxHeight 内
        double scale = Math.min((double) maxWidth / srcWidth, (double) maxHeight / srcHeight);

        // 如果原图已经比缩略图小，不需要放大
        if (scale >= 1.0) {
            Files.copy(originalPath, thumbnailPath);
            log.debug("原图已小于缩略图尺寸，直接复制: {}x{}", srcWidth, srcHeight);
            return;
        }

        int newWidth = (int) Math.round(srcWidth * scale);
        int newHeight = (int) Math.round(srcHeight * scale);

        // 缩略图统一使用 JPEG 格式（体积小、JDK 原生支持），文件名后缀保持不变
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(srcImage, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        // 使用 JPEG 编码，质量 0.65（缩略图场景下画质足够，体积更小）
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.65f);
        try (FileImageOutputStream output = new FileImageOutputStream(thumbnailPath.toFile())) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(resized, null, null), params);
        } finally {
            writer.dispose();
        }
        log.debug("缩略图生成(JPEG q=0.65): {}x{} -> {}x{} ({})", srcWidth, srcHeight, newWidth, newHeight, thumbnailPath.getFileName());
    }

    /**
     * 根据文件名获取 MediaType
     *
     * @param filename 文件名
     * @return MediaType
     */
    public static MediaType getMediaTypeByFilename(String filename) {
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String name = filename.toLowerCase();
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE.equals(MediaType.IMAGE_PNG.toString()) 
                    ? MediaType.IMAGE_PNG 
                    : MediaType.parseMediaType("image/png");
        } else if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        } else if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.IMAGE_JPEG; // 默认返回 JPEG
    }

    /**
     * 构建图片响应实体
     *
     * @param imagePath 图片路径
     * @param filename  文件名
     * @return ResponseEntity
     */
    public static ResponseEntity<Resource> buildImageResponse(Path imagePath, String filename) {
        MediaType contentType = getMediaTypeByFilename(filename);
        FileSystemResource resource = new FileSystemResource(imagePath);
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    /**
     * 安全地解析文件路径，防止路径穿越攻击
     *
     * @param basePath   基础路径
     * @param targetPath 目标路径
     * @return 规范化后的路径，如果存在路径穿越则返回 null
     */
    public static Path safeResolvePath(Path basePath, String targetPath) {
        if (basePath == null || targetPath == null) {
            return null;
        }
        Path resolved = basePath.resolve(targetPath).normalize();
        if (!resolved.startsWith(basePath.normalize())) {
            log.warn("检测到潜在的路径穿越攻击: {}", targetPath);
            return null;
        }
        return resolved;
    }

    /**
     * 截断文本到指定长度
     *
     * @param text   原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    public static String truncateText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 格式化文件大小为人类可读字符串
     */
    public static String formatFileSize(Long bytes) {
        if (bytes == null || bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ================================================================
    // 统一 AI 调用摘要日志（INFO 单行）—— 一次 LLM 调用只打一条
    // ================================================================

    /** 摘要中各段预览的最大字符数 */
    private static final int SUMMARY_PREVIEW_LEN = 120;
    private static final String AI_LOG_BORDER = "════════════════════════════════════════════════════════════════";

    /**
     * 打印 AI 调用的统一摘要日志（INFO 级别，块状）。
     * <p>
     * 一次 LLM 调用只打一条 INFO 日志，所有信息聚合在一个块里，视觉上一目了然。
     *
     * @param operation       操作名（如"圆桌派发言"）
     * @param scene           场景名（如"ROUND_TABLE_SPEECH"，可为 null）
     * @param modelName       模型名（如"agnes-2.0-flash"，可为 null）
     * @param configName      配置名（如"ai-gateway-agnes-2.0-flash"，可为 null）
     * @param thinkingMode    思考模式（如"SWITCH"/"REASONING_EFFORT"/"NONE"，可为 null）
     * @param thinkingEnabled 思考是否开启
     * @param reasoningEffort reasoning effort（如"medium"，可为 null）
     * @param messages        请求消息列表（每条消息打印一行摘要）
     * @param answerText      回答全文（可为 null）
     * @param thinkingText    思考内容全文（可为 null）
     * @param elapsedMs       耗时毫秒
     * @param inputTokens     输入 token 数
     * @param outputTokens    输出 token 数
     */
    public static void logAiSummary(String operation, String scene, String modelName, String configName,
                                     String thinkingMode, boolean thinkingEnabled, String reasoningEffort,
                                     List<dev.langchain4j.data.message.ChatMessage> messages,
                                     String answerText, String thinkingText,
                                     long elapsedMs, int inputTokens, int outputTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(AI_LOG_BORDER);
        // 第1行：操作名 + 模型 + 场景 + 思考配置
        sb.append("\n║ [").append(operation).append("] ");
        if (modelName != null && !modelName.isBlank()) {
            sb.append(modelName);
            if (configName != null && !configName.isBlank()) {
                sb.append("(").append(configName).append(")");
            }
        } else {
            sb.append("未知模型");
        }
        if (scene != null) sb.append(" | scene=").append(scene);
        String mode = thinkingMode != null ? thinkingMode : "未知";
        sb.append(" | thinking=").append(thinkingEnabled ? "ON" : "OFF")
          .append("(").append(mode).append(",effort=").append(reasoningEffort).append(")");

        // 请求：每条消息一行
        int msgCount = messages != null ? messages.size() : 0;
        sb.append("\n║ 请求: ").append(msgCount).append("条消息");
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                var msg = messages.get(i);
                String type = msg.type().name();
                String text = extractMessageText(msg);
                int len = text != null ? text.length() : 0;
                String preview = text != null ? sanitizePreview(text, SUMMARY_PREVIEW_LEN) : "";
                sb.append("\n║   [").append(i).append("] ").append(type).append("(").append(len).append("字符): ").append(preview);
            }
        }

        // 思考内容
        if (thinkingText != null && !thinkingText.isBlank()) {
            sb.append("\n║ 思考: ").append(thinkingText.length()).append("字符: ")
              .append(sanitizePreview(thinkingText, SUMMARY_PREVIEW_LEN));
        } else {
            sb.append("\n║ 思考: 无");
        }

        // 回答内容
        if (answerText != null && !answerText.isBlank()) {
            sb.append("\n║ 回答: ").append(answerText.length()).append("字符: ")
              .append(sanitizePreview(answerText, SUMMARY_PREVIEW_LEN));
        } else {
            sb.append("\n║ 回答: 无");
        }

        // token 统计
        sb.append("\n║ 统计: 耗时").append(elapsedMs).append("ms");
        if (inputTokens > 0 || outputTokens > 0) {
            int total = inputTokens + outputTokens;
            sb.append(" | 输入").append(inputTokens)
              .append(" | 输出").append(outputTokens)
              .append(" | 总").append(total);
            if (elapsedMs > 0) {
                double tps = outputTokens / (elapsedMs / 1000.0);
                sb.append(" | ").append(String.format("%.2f", tps)).append(" tok/s");
            }
        }
        sb.append("\n").append(AI_LOG_BORDER);

        log.info(sb.toString());
    }

    /** 从 ChatMessage 提取文本内容 */
    private static String extractMessageText(dev.langchain4j.data.message.ChatMessage msg) {
        if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) return sm.text();
        if (msg instanceof dev.langchain4j.data.message.UserMessage um) return um.singleText();
        if (msg instanceof dev.langchain4j.data.message.AiMessage am) return am.text();
        return msg.toString();
    }

    /**
     * 打印流式 AI 调用的统一摘要日志（INFO 级别，块状）。
     * 与 {@link #logAiSummary} 格式一致。
     */
    public static void logAiStreamingSummary(String operation, String scene, String modelName, String configName,
                                              String thinkingMode, boolean thinkingEnabled, String reasoningEffort,
                                              List<dev.langchain4j.data.message.ChatMessage> messages,
                                              String answerText, String thinkingText,
                                              long elapsedMs, int inputTokens, int outputTokens) {
        logAiSummary(operation, scene, modelName, configName,
                thinkingMode, thinkingEnabled, reasoningEffort,
                messages,
                answerText, thinkingText,
                elapsedMs, inputTokens, outputTokens);
    }

    /**
     * 简化版 AI 调用摘要（INFO 级别，块状）— 用于无法获取场景信息的调用点。
     *
     * @param operation      操作名（如"PDF OCR"）
     * @param elapsedMs      耗时毫秒
     * @param inputTokens    输入 token 数
     * @param outputTokens   输出 token 数
     * @param result         调用上下文/描述（如"bookId=1, title=xxx"），可为 null
     * @param answerPreview  回答预览文本，可为 null
     */
    public static void logAiSummarySimple(String operation, long elapsedMs,
                                           int inputTokens, int outputTokens,
                                           String result, String answerPreview) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(AI_LOG_BORDER);
        sb.append("\n║ [").append(operation).append("] 未知模型");
        if (result != null && !result.isBlank()) {
            sb.append(" | ").append(sanitizePreview(result, SUMMARY_PREVIEW_LEN));
        }
        sb.append("\n║ 回答: ");
        if (answerPreview != null && !answerPreview.isBlank()) {
            sb.append(sanitizePreview(answerPreview, SUMMARY_PREVIEW_LEN));
        } else {
            sb.append("无");
        }
        sb.append("\n║ 统计: 耗时").append(elapsedMs).append("ms");
        if (inputTokens > 0 || outputTokens > 0) {
            int total = inputTokens + outputTokens;
            sb.append(" | 输入").append(inputTokens)
              .append(" | 输出").append(outputTokens)
              .append(" | 总").append(total);
            if (elapsedMs > 0) {
                double tps = outputTokens / (elapsedMs / 1000.0);
                sb.append(" | ").append(String.format("%.2f", tps)).append(" tok/s");
            }
        }
        sb.append("\n").append(AI_LOG_BORDER);

        log.info(sb.toString());
    }

    /** 清理预览文本：去除换行、截断到指定长度 */
    private static String sanitizePreview(String text, int maxLen) {
        if (text == null) return "";
        return truncateText(text.replace("\n", " ").replace("\r", " "), maxLen);
    }

    /**
     * HTML 实体编码 — 用于防止存储型 XSS
     * <p>
     * 对 <, >, ", ', & 等特殊字符进行 HTML 实体转义，
     * 配合前端的反序列化（默认不解析 HTML）实现纵深防御。
     * <p>
     * 使用场景：所有用户输入的文本字段在持久化前都应经过此方法处理。
     *
     * @param input 用户输入的原始文本
     * @return HTML 实体编码后的安全文本，input 为 null 时返回 null
     */
    public static String sanitizeHtml(String input) {
        if (input == null) {
            return null;
        }
        return HtmlUtils.htmlEscape(input);
    }

    /**
     * 搜索关键词清理 — 用于防止 ES JSON 查询注入 + 限制输入长度
     * <p>
     * 安全措施：
     * 1. 限制最大长度 100 字符（防止内存耗尽）
     * 2. 移除控制字符（\0-\x1f, \x7f）和 Unicode 全角斜杠（U+FF0F）
     * 3. 转义反斜杠和双引号（防止破坏 ES @Query 的 JSON 结构）
     * 4. 不移除 SQL 关键字（JPA 参数化查询已免疫，移除会破坏正常搜索如"SELECT 语句教程"）
     * <p>
     * 注意：JPA @Query 使用 :param 参数化查询本身安全，但 ES @Query 使用 ?0 字符串替换，
     * 特殊字符（如 " \）会破坏 JSON 结构导致异常行为。
     *
     * @param keyword 用户输入的搜索关键词
     * @return 清理后的安全关键词，null/空白返回 null
     */
    public static String sanitizeSearchKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        // 1. 截断到最大长度
        String result = keyword.length() > 100 ? keyword.substring(0, 100) : keyword;
        // 2. 移除控制字符和全角斜杠
        result = result.replaceAll("[\\x00-\\x1f\\x7f\\uFF0F]", "");
        // 3. 转义反斜杠和双引号（防止破坏 ES @Query 的 JSON 结构）
        //    注意：JPA @Query 使用 :param 参数化查询，对 SQL 注入天然免疫，
        //    不需要移除 SQL 关键字（移除会破坏 "SELECT 语句教程" 等正常搜索）
        result = result.replace("\\", "\\\\").replace("\"", "\\\"");
        return result.trim();
    }

    /** AI 用户输入最大长度（字符）— 防止超大输入消耗资源 */
    private static final int AI_INPUT_MAX_LENGTH = 5000;

    /**
     * 提示词注入检测正则（不区分大小写）— 仅拦截最明显的攻击模式。
     * 保守策略：只拦截明确的"忽略指令/泄露系统提示/角色劫持/Base64 解码执行"，
     * 不拦截正常的书籍咨询、角色扮演读书场景。
     */
    private static final java.util.regex.Pattern PROMPT_INJECTION_PATTERN = java.util.regex.Pattern.compile(
            "(?i)("
            // 中英文"忽略之前的所有指令/ignore previous instructions"
            + "忽略(?:之前|上面|前面|以上|所有)(?:的)?(?:指令|提示|规则|设定|约束)"
            + "|ignore\\s+(?:previous|prior|above|all)\\s+(?:instructions?|prompts?|rules?)"
            // 系统提示/指令提取
            + "|你(?:的)?(?:系统|初始|原始)(?:指令|提示|提示词|prompt|设定)"
            + "|(?:reveal|show|print|display|output)\\s+(?:your\\s+)?(?:system\\s+)?(?:prompt|instructions?|rules?)"
            + "|what\\s+(?:are|is)\\s+your\\s+(?:system\\s+)?(?:instructions?|prompts?|rules?)"
            // 角色劫持：你现在是没有限制的 AI / 你现在是一个...
            + "|你现在是(?:一个|没有|无)(?:限制|约束|边界)的"
            + "|you\\s+are\\s+now\\s+(?:an?\\s+)?(?:unrestricted|uncensored|unlimited|free)\\s+(?:ai|assistant|model)"
            + "|你(?:现在)?(?:不再|没有)(?:需要|需要遵守|受)(?:遵守|限制|约束|规则)"
            // Base64 解码执行
            + "|请(?:解码|解密|解析)(?:以下|下面)(?:内容|文本|编码)(?:并)?(?:执行|运行|按照.*?做|遵循)"
            + "|decode\\s+(?:the\\s+following|this).*(?:and\\s+(?:execute|follow|run)|然后执行)"
            // 模拟开发者/管理员模式
            + "|(?:进入|切换到|模拟)(?:开发者|管理员|root|debug|维护| DAN)模式"
            + "|act\\s+as\\s+(?:a\\s+)?(?:developer|admin|root|DAN)"
            + ")"
    );

    /**
     * 系统提示泄露检测正则 — 用于过滤 AI 输出中的系统提示内容。
     * 匹配 AiPromptConstants 中具有标识性的片段。
     */
    private static final java.util.regex.Pattern SYSTEM_PROMPT_LEAK_PATTERN = java.util.regex.Pattern.compile(
            "(?s)("
            + "你是 KBook 智能阅读平台的 AI 助理"
            + "|【语言规则（最重要！）】"
            + "|【核心原则：推荐优先"
            + "|【工具使用指南】"
            + "|【禁止编造图书（铁律！）】"
            + "|searchBooks\\(keyword,\\s*tag,\\s*excludeBookIds"
            + "|personalizeRecommend\\(userId,\\s*count\\)"
            + "|getUserBookshelf\\(userId\\)"
            + ")"
    );

    /**
     * AI 用户输入清理 — 防御提示词注入（P1 #17）
     * <p>
     * 安全措施：
     * 1. 限制最大长度 {@value #AI_INPUT_MAX_LENGTH} 字符
     * 2. 检测明显的提示词注入模式（忽略指令、系统提示提取、角色劫持、Base64 解码执行）
     * 3. 检测到注入时返回 null，由调用方拒绝请求
     * <p>
     * 设计原则：保守拦截，只拒绝明确的攻击模式，不影响正常书籍咨询对话。
     *
     * @param input 用户输入的原始消息
     * @return 清理后的安全消息；null 表示检测到注入攻击，应拒绝请求
     */
    public static String sanitizeAiInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        // 1. 截断到最大长度
        String result = input.length() > AI_INPUT_MAX_LENGTH
                ? input.substring(0, AI_INPUT_MAX_LENGTH) : input;
        // 2. 检测提示词注入模式
        if (PROMPT_INJECTION_PATTERN.matcher(result).find()) {
            log.warn("检测到 AI 提示词注入尝试: {}",
                    truncateText(result.replace("\n", " "), 80));
            return null;
        }
        return result;
    }

    /**
     * AI 输出审查 — 检测并清除系统提示泄露（P1 #17）
     * <p>
     * 如果 LLM 输出中包含系统提示的标识性片段，将其替换为安全提示。
     * 作为系统提示加固的纵深防御层。
     *
     * @param output AI 的原始输出
     * @return 审查后的安全输出
     */
    public static String sanitizeAiOutput(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        if (SYSTEM_PROMPT_LEAK_PATTERN.matcher(output).find()) {
            log.warn("检测到 AI 输出包含系统提示泄露，已拦截");
            return "抱歉，我无法回答这个问题。我可以帮你推荐书籍或解答阅读相关的疑问。";
        }
        return output;
    }

    /** 去掉 AI 返回文本中的 ```json / ``` 代码围栏 */
    public static String stripCodeFence(String text) {
        if (text == null) return null;
        String result = text.trim();
        if (result.startsWith("```json")) {
            result = result.substring(7);
        } else if (result.startsWith("```")) {
            result = result.substring(3);
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
    }

    /**
     * 从 Markdown 文本中提取指定二级标题（## xxx）下的内容。
     * <p>用于解析 LLM 按行式 Markdown 输出的结构化数据。提取规则：
     * <ul>
     *   <li>匹配以「## 」开头的行，标题等于 header 参数（忽略大小写、首尾空白）</li>
     *   <li>内容为该标题行之后到下一个「## 」标题行（或文本结尾）之间的所有行</li>
     *   <li>返回内容去除首尾空白；找不到返回 null</li>
     * </ul>
     *
     * @param text   LLM 原始返回（建议先 stripCodeFence）
     * @param header 要提取的二级标题文字（不含「## 」前缀），如「作者」「正方观点」
     * @return 字段内容（已 trim）；找不到返回 null
     */
    public static String extractMarkdownSection(String text, String header) {
        if (text == null || header == null || header.isBlank()) return null;
        String[] lines = text.split("\\r?\\n", -1);
        String target = header.trim();
        boolean capturing = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // 只匹配二级标题「## 」,不匹配三级「### 」或更深层级
            // 避免提取含子标题的段时,子标题被误判为段结束
            if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                String title = trimmed.substring(3).trim();
                if (capturing) {
                    // 已经在收集目标段，遇到下一个 ## 标题 → 结束
                    break;
                }
                if (title.equalsIgnoreCase(target)) {
                    capturing = true;
                }
            } else if (capturing) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        if (!capturing) return null;
        String result = sb.toString().trim();
        // LLM 偶尔会填"无"/"未知"/"null" 表示缺失
        if (result.isEmpty() || "无".equals(result) || "未知".equals(result)
                || "null".equalsIgnoreCase(result) || "\"null\"".equalsIgnoreCase(result)) {
            return null;
        }
        return result;
    }

    /**
     * DEBUG 级别打印完整 AI 对话消息（提问 → 思考 → 回答）。
     * 超过 2000 字的内容会被截断。
     */
    public static void logAiMessages(String operation, List<dev.langchain4j.data.message.ChatMessage> messages) {
        if (!log.isDebugEnabled()) return;
        log.debug("========== AI {} 请求消息 ({} 条) ==========", operation, messages.size());
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            String type = msg.type().name();
            String text = null;
            if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
                text = sm.text();
            } else if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                text = um.singleText();
            } else if (msg instanceof dev.langchain4j.data.message.AiMessage am) {
                text = am.text();
            }
            if (text == null) text = msg.toString();
            log.debug("[{}] {}: {}", i, type, truncateText(text, 100).replace("\n", ""));
        }
    }

}

