package com.kbook.service.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用校验单测：只保留真实片段编号，非法编号剔除并计数。
 */
class CitationValidatorTest {

    @Test
    @DisplayName("合法单编号与多编号都保留，并转成可读 [N]")
    void keepsValidCitations() {
        CitationValidator.Result result = CitationValidator.validate(
                "书中说A[[1]]，又说B[[2,3]]。", 3);

        assertThat(result.answer()).isEqualTo("书中说A[1]，又说B[2,3]。");
        assertThat(result.validCount()).isEqualTo(3);
        assertThat(result.invalidCount()).isZero();
        assertThat(result.citationJson())
                .contains("\"valid\":[1,2,3]")
                .contains("\"invalid\":0")
                .contains("\"included\":3");
    }

    @Test
    @DisplayName("越界编号直接剔除")
    void stripsOutOfRangeCitation() {
        CitationValidator.Result result = CitationValidator.validate("事实A[[4]]", 3);

        assertThat(result.answer()).isEqualTo("事实A");
        assertThat(result.validCount()).isZero();
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("混合编号只保留合法的那个")
    void keepsOnlyValidPartOfGroup() {
        CitationValidator.Result result = CitationValidator.validate("事实A[[1,9]]", 3);

        assertThat(result.answer()).isEqualTo("事实A[1]");
        assertThat(result.validCount()).isEqualTo(1);
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("空白与重复编号容错")
    void toleratesWhitespaceAndDuplicates() {
        CitationValidator.Result result = CitationValidator.validate("A[[ 1 , 2 ]][[1]]", 2);

        assertThat(result.answer()).isEqualTo("A[1,2][1]");
        assertThat(result.validCount()).isEqualTo(2);
        assertThat(result.invalidCount()).isZero();
    }

    @Test
    @DisplayName("没有片段时所有引用都剔除")
    void stripsAllWhenNoContext() {
        CitationValidator.Result result = CitationValidator.validate("A[[1]]B", 0);

        assertThat(result.answer()).isEqualTo("AB");
        assertThat(result.validCount()).isZero();
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("无引用时不产生 citation_json")
    void noCitationNoJson() {
        CitationValidator.Result result = CitationValidator.validate("普通回答", 3);

        assertThat(result.answer()).isEqualTo("普通回答");
        assertThat(result.citationJson()).isNull();
        assertThat(result.validCount()).isZero();
        assertThat(result.invalidCount()).isZero();
    }

    @Test
    @DisplayName("null/空回答安全")
    void handlesNull() {
        assertThat(CitationValidator.validate(null, 3).answer()).isNull();
        assertThat(CitationValidator.validate("", 3).answer()).isEmpty();
    }
}
