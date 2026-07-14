package com.kbook.service.ai.streaming;

/**
 * 状态机解析器 — 从流式文本中分离 Google AI 的 {@code <thought>} 标签。
 *
 * <p>Google AI (Gemini) 开启 thinking 模式后，思考内容以 {@code <thought>...}</thought>}
 * 包裹在普通流式文本中返回，而非走独立的 thinking channel。此解析器逐 chunk 检测标签，
 * 将思考内容和正常回复分离，支持标签跨 chunk 边界。
 *
 * <p>使用方式：
 * <pre>{@code
 *   ThoughtTagParser parser = new ThoughtTagParser();
 *   for (String chunk : chunks) {
 *       var result = parser.process(chunk);
 *       if (result.hasThinking()) { /* 发送 thinking_content 事件 *​/ }
 *       if (result.hasMessage())  { /* 发送 message 事件 *​/ }
 *   }
 * }</pre>
 *
 * @author kbook
 * @since 1.1.0
 */
public final class ThoughtTagParser {

    private static final String OPEN_TAG = "<thought>";
    private static final String CLOSE_TAG = "</thought>";

    /** 是否正处于 <thought> 标签内部。 */
    private boolean inThought = false;

    /** 跨 chunk 缓存的可能不完整标签片段。 */
    private String pending = "";

    /**
     * 解析结果：thinking 和 message 可能都有值（标签前后的文本），
     * 也可能只有一方有值。
     */
    public record Result(String thinking, String message) {
        public boolean hasThinking() { return thinking != null && !thinking.isEmpty(); }
        public boolean hasMessage() { return message != null && !message.isEmpty(); }
    }

    /**
     * 处理一个流式 chunk，返回分离后的思考内容和正常回复。
     *
     * @param chunk 流式文本片段，不可为 null
     * @return 分离结果
     */
    public Result process(String chunk) {
        if (chunk == null || chunk.isEmpty()) return new Result("", "");

        String input = pending + chunk;
        pending = "";

        StringBuilder thinking = new StringBuilder();
        StringBuilder message = new StringBuilder();

        int i = 0;
        while (i < input.length()) {
            String expectedTag = inThought ? CLOSE_TAG : OPEN_TAG;
            int tagIdx = input.indexOf(expectedTag, i);

            if (tagIdx >= 0) {
                // 找到完整标签 — 标签前的内容归当前通道
                if (!inThought) {
                    message.append(input, i, tagIdx);
                } else {
                    thinking.append(input, i, tagIdx);
                }
                inThought = !inThought;
                i = tagIdx + expectedTag.length();
            } else {
                // 未找到标签 — 输出确定的内容，缓存尾部可能的不完整标签
                String remaining = input.substring(i);
                int partialLen = longestPrefixMatch(remaining, expectedTag);
                if (partialLen > 0) {
                    // 输出不包含尾部的部分
                    if (remaining.length() > partialLen) {
                        if (!inThought) {
                            message.append(remaining, 0, remaining.length() - partialLen);
                        } else {
                            thinking.append(remaining, 0, remaining.length() - partialLen);
                        }
                    }
                    pending = remaining.substring(remaining.length() - partialLen);
                } else {
                    if (!inThought) {
                        message.append(remaining);
                    } else {
                        thinking.append(remaining);
                    }
                }
                break;
            }
        }

        return new Result(thinking.toString(), message.toString());
    }

    /**
     * 是否处于 <thought> 标签内部（流结束后可用于检测未闭合标签）。
     */
    public boolean isInThought() {
        return inThought;
    }

    /**
     * 重置状态（复用解析器时调用）。
     */
    public void reset() {
        inThought = false;
        pending = "";
    }

    /**
     * 检测 {@code str} 尾部是否是 {@code tag} 的前缀，返回匹配长度。
     * 例如 {@code longestPrefixMatch("abc<tho", "<thought>")} 返回 3（"tho"）。
     */
    private static int longestPrefixMatch(String str, String tag) {
        int maxLen = Math.min(str.length(), tag.length() - 1);
        for (int len = maxLen; len >= 1; len--) {
            if (tag.startsWith(str.substring(str.length() - len))) {
                return len;
            }
        }
        return 0;
    }
}
