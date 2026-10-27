package com.kbook.service.ai.rag;

import com.kbook.service.ai.rag.BookAnswerGuard.QuestionType;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可答性守卫单测：决定"检索为空时能不能不靠材料回答"。
 */
class BookAnswerGuardTest {

    @Test
    @DisplayName("关于书本身的元问题不拒答")
    void metaQuestionsAreAllowedWithoutContext() {
        assertThat(BookAnswerGuard.classify("这本书讲了什么")).isEqualTo(QuestionType.META);
        assertThat(BookAnswerGuard.classify("这本书值得读吗")).isEqualTo(QuestionType.META);
        assertThat(BookAnswerGuard.classify("作者是谁")).isEqualTo(QuestionType.META);
        assertThat(BookAnswerGuard.classify("这本书适合什么人")).isEqualTo(QuestionType.META);

        assertThat(BookAnswerGuard.shouldRefuse("这本书讲了什么")).isFalse();
        assertThat(BookAnswerGuard.shouldRefuse("作者是谁")).isFalse();
    }

    @Test
    @DisplayName("书内事实问题无材料时必须拒答")
    void contentQuestionsRequireContext() {
        assertThat(BookAnswerGuard.classify("第三章讲了什么")).isEqualTo(QuestionType.CONTENT);
        assertThat(BookAnswerGuard.classify("主角最后为什么离开")).isEqualTo(QuestionType.CONTENT);
        assertThat(BookAnswerGuard.classify("作者为什么这样安排结局")).isEqualTo(QuestionType.CONTENT);
        assertThat(BookAnswerGuard.classify("这本书的核心方法有哪些")).isEqualTo(QuestionType.CONTENT);
        assertThat(BookAnswerGuard.classify("结局是什么")).isEqualTo(QuestionType.CONTENT);

        assertThat(BookAnswerGuard.shouldRefuse("第三章讲了什么")).isTrue();
        assertThat(BookAnswerGuard.shouldRefuse("主角最后为什么离开")).isTrue();
    }

    @Test
    @DisplayName("寒暄不拒答，也不会被当成书内事实问题")
    void chitchatIsAllowed() {
        assertThat(BookAnswerGuard.classify("你好")).isEqualTo(QuestionType.CHITCHAT);
        assertThat(BookAnswerGuard.classify("谢谢")).isEqualTo(QuestionType.CHITCHAT);
        assertThat(BookAnswerGuard.classify("好的")).isEqualTo(QuestionType.CHITCHAT);
        assertThat(BookAnswerGuard.classify("   ")).isEqualTo(QuestionType.CHITCHAT);
        assertThat(BookAnswerGuard.classify(null)).isEqualTo(QuestionType.CHITCHAT);

        assertThat(BookAnswerGuard.shouldRefuse("你好")).isFalse();
    }

    @Test
    @DisplayName("分类不确定时按内容问题严格处理")
    void unknownQuestionsDefaultToContent() {
        assertThat(BookAnswerGuard.classify("随便聊聊")).isEqualTo(QuestionType.CONTENT);
        assertThat(BookAnswerGuard.shouldRefuse("随便聊聊")).isTrue();
    }

    @Test
    @DisplayName("拒答话术使用问题主题，且不出现元描述")
    void refusalMessageIsPrewrittenAndGrounded() {
        String message = BookAnswerGuard.refusalMessage("请问，主角最后为什么离开？");

        assertThat(message).contains("主角最后为什么离开");
        assertThat(message).doesNotContain("参考内容");
        assertThat(message).contains("换个说法");
    }

    @Test
    @DisplayName("空白问题回退为通用主题")
    void blankQuestionUsesGenericTopic() {
        assertThat(BookAnswerGuard.refusalMessage("  ")).contains("这个问题");
        assertThat(BookAnswerGuard.refusalMessage(null)).contains("这个问题");
    }

    @Test
    @DisplayName("绝对阈值只对非列表的常规检索生效")
    void lowScoreGuardOnlyAppliesToRegularRetrieval() {
        List<EmbeddingMatch<TextSegment>> low = List.of(match(0.2));

        assertThat(BookAnswerGuard.topScore(low)).isEqualTo(0.2);
        assertThat(BookAnswerGuard.topScore(List.of())).isZero();
        assertThat(BookAnswerGuard.topScore(null)).isZero();

        assertThat(BookAnswerGuard.isLowScore(low, 0.35, false)).isTrue();
        assertThat(BookAnswerGuard.isLowScore(low, 0.0, false)).isFalse();
        assertThat(BookAnswerGuard.isLowScore(low, 0.35, true)).isFalse();
        assertThat(BookAnswerGuard.isLowScore(List.of(match(0.5)), 0.35, false)).isFalse();
    }

    private static EmbeddingMatch<TextSegment> match(double score) {
        return new EmbeddingMatch<>(score, "1", null,
                TextSegment.from("x", new Metadata()));
    }
}
