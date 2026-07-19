package com.kbook.service.book;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiScene;
import com.kbook.entity.Book;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 书籍元数据推断器 — 从纯文本内容中通过 AI 推断作者和简介。
 *
 * <p>从 ChatModelManager 中抽取，适用于 TXT、PDF 等无法自动提取元数据的格式。</p>
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookMetadataInferrer {

    private final ChatModelFactory chatModelFactory;

    private static final int CONTENT_LIMIT = 15000;

    public void infer(Book book, String content) {
        long startTime = System.currentTimeMillis();
        try {
            String prompt = "根据以下书籍内容，推断并提取作者和简介，按系统提示词中的 Markdown 格式输出。\n\n" +
                    "书籍内容：\n" + CommonUtils.truncateText(content, CONTENT_LIMIT);

            var model = chatModelFactory.buildForScene(AiScene.BOOK_METADATA_INFER);
            if (model == null) {
                log.debug("AI 模型未配置，跳过元数据推断: {}", book.getTitle());
                return;
            }
            var response = model.chat(List.of(
                    SystemMessage.from(AiPromptConstants.BOOK_INFO_EXTRACT_SYSTEM_PROMPT),
                    UserMessage.from(prompt)));

            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;

            String result = response.aiMessage().text();
            if (result != null) result = result.trim();
            var ctx = chatModelFactory.buildLogContext(AiScene.BOOK_METADATA_INFER);
            CommonUtils.logAiSummary("元数据推断", ctx.scene(), ctx.modelName(), ctx.configName(),
                    ctx.thinkingMode(), ctx.thinkingEnabled(), ctx.reasoningEffort(),
                    null, result, null, elapsed, inputTokens, outputTokens);

            result = CommonUtils.stripCodeFence(result);
            if (result != null) {
                String author = CommonUtils.extractMarkdownSection(result, "作者");
                if ((book.getAuthor() == null || book.getAuthor().isBlank())
                        && author != null && !"未知".equalsIgnoreCase(author)) {
                    book.setAuthor(author);
                }
                String desc = CommonUtils.extractMarkdownSection(result, "简介");
                if (desc != null) {
                    book.setDescription(desc);
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("从内容推断元数据失败: {} - {} (耗时 {}ms)", book.getTitle(), e.getMessage(), elapsed);
        }
    }
}
