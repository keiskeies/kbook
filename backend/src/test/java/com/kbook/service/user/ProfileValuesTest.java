package com.kbook.service.user;

import com.kbook.common.exception.BusinessException;
import com.kbook.service.admin.BookProfileDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设计文档第 12 节「users 画像列没有取值校验」的写入端补齐，见附录 F。
 *
 * 要守的不变式只有一条：**写入端放行的值，读取端必须能分桶**。校验与分桶共用
 * {@link BookProfileDimension} 的同一份白名单，所以它不是两句注释，而是逐值可断言的性质。
 */
class ProfileValuesTest {

    @ParameterizedTest(name = "{0} <- {1} = {2}")
    @DisplayName("写入端折叠：与读取端同一套别名规则")
    @CsvSource({
            "GENDER, female, FEMALE",
            "GENDER, OTHER, OTHER",
            "MBTI_TYPE, intj-a, INTJ",
            "CHAT_STYLE, witty, WITTY",
            "EDUCATION, phd, DOCTORATE",
            "INCOME, 100K, 50K_150K",
            "ENTREPRENEURSHIP, true, ENTREPRENEUR_OR_WANT",
            "OCCUPATION, hacker, TECH",
            "OCCUPATION, 'tech,TECH', TECH",
            "OCCUPATION, 'TECH,student', 'TECH,STUDENT'",
            "CHILD_AGE, children_0_2, CHILDREN_0_2",
    })
    void canonicalizesSameAsReadSide(BookProfileDimension dim, String input, String expected) {
        assertThat(ProfileValues.require(dim, input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} 拒绝 {1}")
    @DisplayName("写入端拒绝：历史脏值与注入串不能再写回库里")
    @CsvSource({
            // 左侧这些取值实测存在于 dev 库，属早期版本遗留、语义已无法还原，读取端按脏值处理
            "EDUCATION, SELF",
            "INCOME, MEDIUM",
            "INCOME, '{{7*7}}'",
            "ENTREPRENEURSHIP, Startup",
            "GENDER, '{{7*7}}'",
            "GENDER, 男",
            "MBTI_TYPE, XNTJ",
            "OCCUPATION, 'TECH,LAWYER'",
            "CHILD_AGE, 'children_0_2,adult_99'",
    })
    void rejectsValuesOutsideWhitelist(BookProfileDimension dim, String input) {
        assertThatThrownBy(() -> ProfileValues.require(dim, input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(dim.getLabel());
    }

    @Test
    @DisplayName("空值不报错：null/空白按「该列留空」处理，注册引导可以只填一部分")
    void blankMeansClear() {
        assertThat(ProfileValues.require(BookProfileDimension.GENDER, null)).isNull();
        assertThat(ProfileValues.require(BookProfileDimension.GENDER, "  ")).isNull();
    }

    @ParameterizedTest(name = "放行 {0} 的合法取值必然也是 {0} 的合法分桶")
    @EnumSource(value = BookProfileDimension.class,
            names = {"GENDER", "MBTI_TYPE", "CHILD_AGE", "OCCUPATION", "EDUCATION", "INCOME",
                    "ENTREPRENEURSHIP", "CHAT_STYLE"})
    @DisplayName("不变式：白名单是校验与分桶的唯一来源（逐维度用各自的 allowed 反喂）")
    void acceptedValuesEqualBuckets(BookProfileDimension dim) {
        // AGE/MARITAL/CHILDREN 不在这份列表里：它们由 birthday 与两个布尔列派生，没有字符串写入形态
        for (String token : dim.getAllowed().keySet()) {
            String canonical = ProfileValues.require(dim, token);
            assertThat(canonical).as("%s 的合法 token %s 必须放行", dim, token).isNotNull();
            for (String part : canonical.split(",")) {
                assertThat(dim.toTokens(part)).as("%s=%s 必须成桶", dim, part).containsExactly(part);
            }
        }
    }

    @Test
    @DisplayName("性别 OTHER 是注册引导页的合法选项，读取端不能把它算成脏值")
    void genderOtherIsARealBucket() {
        assertThat(BookProfileDimension.GENDER.toToken("OTHER")).isEqualTo("OTHER");
        assertThat(ProfileValues.require(BookProfileDimension.GENDER, "OTHER")).isEqualTo("OTHER");
    }

    /**
     * 前端两份画像表单（onboarding.tsx 与 ProfileTraitsSheet.tsx）的选项取值逐个喂给写入端。
     * 这条是接口契约，不是重复上面的白名单测试：上面用 allowed 反喂，验的是「放行 ⇒ 可分桶」；
     * 这里钉的是「UI 能选出来的值 ⇒ 放行」。两者都成立，才没人会在提交时吃到 400。
     * 实测漏过一次：hasChildren=no 时前端发裸值 no_children，不在 children_* 命名空间里。
     */
    @ParameterizedTest(name = "{0} <- UI 选项 {1}")
    @DisplayName("契约：前端画像表单的每个选项值都能通过写入端校验")
    @CsvSource({
            "GENDER, MALE", "GENDER, FEMALE", "GENDER, OTHER",
            "MBTI_TYPE, INTJ", "MBTI_TYPE, ESFP",
            "CHILD_AGE, children_0_2", "CHILD_AGE, children_3_6", "CHILD_AGE, children_7_12",
            "CHILD_AGE, children_13_17", "CHILD_AGE, children_18_plus",
            "CHILD_AGE, children_no_children",
            "CHILD_AGE, 'children_0_2,children_13_17'",
            "OCCUPATION, STUDENT", "OCCUPATION, TECH", "OCCUPATION, FINANCE", "OCCUPATION, EDUCATION",
            "OCCUPATION, MEDICAL", "OCCUPATION, ARTS", "OCCUPATION, MANAGEMENT", "OCCUPATION, FREELANCE",
            "OCCUPATION, RETIRED", "OCCUPATION, OTHER",
            "OCCUPATION, 'STUDENT,TECH,ARTS'",
            "EDUCATION, HIGH_SCHOOL", "EDUCATION, COLLEGE", "EDUCATION, BACHELOR",
            "EDUCATION, MASTER", "EDUCATION, DOCTORATE", "EDUCATION, OTHER",
            "ENTREPRENEURSHIP, ENTREPRENEUR_OR_WANT", "ENTREPRENEURSHIP, NOT_INTERESTED",
            "INCOME, UNDER_50K", "INCOME, 50K_150K", "INCOME, 150K_300K", "INCOME, 300K_500K",
            "INCOME, 500K_1M", "INCOME, OVER_1M", "INCOME, PREFER_NOT_TO_SAY",
            "CHAT_STYLE, DEEP", "CHAT_STYLE, CASUAL", "CHAT_STYLE, CONCISE", "CHAT_STYLE, WITTY",
    })
    void uiFormOptionsAreAccepted(BookProfileDimension dim, String uiValue) {
        String canonical = ProfileValues.require(dim, uiValue);
        assertThat(canonical).as("UI 选项 %s=%s 被拒会让用户吃到 400", dim, uiValue).isNotNull();
        for (String part : canonical.split(",")) {
            assertThat(dim.toTokens(part)).as("%s=%s 入库后必须成桶", dim, part).containsExactly(part);
        }
    }

    @Test
    @DisplayName("历史裸值 no_children 折叠进 CHILDREN_NO_CHILDREN，读写两端一致")
    void legacyBareNoChildrenIsAliased() {
        assertThat(ProfileValues.require(BookProfileDimension.CHILD_AGE, "no_children"))
                .isEqualTo("CHILDREN_NO_CHILDREN");
        assertThat(BookProfileDimension.CHILD_AGE.toToken("no_children")).isEqualTo("CHILDREN_NO_CHILDREN");
        assertThat(BookProfileDimension.CHILD_AGE.toToken("children_no_children"))
                .isEqualTo("CHILDREN_NO_CHILDREN");
    }
}
