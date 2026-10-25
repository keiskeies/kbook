package com.kbook.service.ai;

/**
 * 「这本书的问答」在 ai_sessions / ai_conversations 里的类型集合。
 *
 * 写入端只有 {@code BookChatService}，它用的就是 {@link #TYPE}；{@link #LEGACY_TYPE} 是历史行的值，
 * 看板与画像都还要算上它。读侧和写侧共用这一处定义，是为了挡住这样一类静默漂移：
 * 哪天有人给助理会话也带上 {@code book_id}，画像的「深度互动读者」不会报错，
 * 只会把"和这本书聊过天的人"悄悄换成"用过 AI 助理的人"，cohort 分母跟着虚高。
 */
public final class BookChatScope {

    /** 当前写入值 */
    public static final String TYPE = "book_chat";

    /** 历史会话行里的旧值 */
    public static final String LEGACY_TYPE = "BOOK";

    private BookChatScope() {
    }

    /** native SQL 片段 {@code type IN ('book_chat', 'BOOK')}；alias 为空表示不带前缀 */
    public static String sqlFilter(String alias) {
        String column = alias == null || alias.isBlank() ? "type" : alias.trim() + ".type";
        return column + " IN ('" + TYPE + "', '" + LEGACY_TYPE + "')";
    }
}
