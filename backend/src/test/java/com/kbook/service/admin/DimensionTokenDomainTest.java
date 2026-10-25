package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 所有维度的 {@code toToken} 只能产出三类值：白名单 token、{@link BookProfileDimension#UNKNOWN}、{@link BookProfileDimension#DIRTY}。
 *
 * 为什么需要：如果某个映射返回了拼错/过时的 token（例如把 "51-60" 当年龄桶、把 "HAS_CHILDREN" 当子女桶），
 * 下游归一化会把它当脏值，读者就"被消失"在该维度的有效样本里——页面只是比例变小，不会报错。
 * 这条测试用一组典型原始值（含历史别名、脏值、空值）把 15 个维度全扫一遍。
 */
class DimensionTokenDomainTest {

    private static final Map<String, String[]> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("GENDER", new String[]{"MALE", "female", "其他", "x", ""});
        SAMPLES.put("AGE", new String[]{"0", "17", "18", "24", "25", "30", "31", "40", "41", "50", "51", "120", "-1", "abc", ""});
        SAMPLES.put("MBTI_TYPE", new String[]{"INTJ", "intj", "INTJ-A", "IN", "xxxx", ""});
        SAMPLES.put("MBTI_EI", new String[]{"E", "I", "X", ""});
        SAMPLES.put("MBTI_SN", new String[]{"S", "N", "X", ""});
        SAMPLES.put("MBTI_TF", new String[]{"T", "F", "X", ""});
        SAMPLES.put("MBTI_JP", new String[]{"J", "P", "X", ""});
        SAMPLES.put("MARITAL", new String[]{"1", "0", "2", "true", ""});
        SAMPLES.put("CHILDREN", new String[]{"1", "0", "2", ""});
        SAMPLES.put("CHILD_AGE", new String[]{"CHILDREN_0_2", "children_0_2", "0-2", "no_children", ""});
        SAMPLES.put("OCCUPATION", new String[]{"TECH", "engineer", "学生", "a,b", ""});
        SAMPLES.put("EDUCATION", new String[]{"BACHELOR", "Master", "SELF", ""});
        SAMPLES.put("INCOME", new String[]{"100k", "MEDIUM", "{{7*7}}", ""});
        SAMPLES.put("ENTREPRENEURSHIP", new String[]{"true", "Startup", "NOT_INTERESTED", ""});
        SAMPLES.put("CHAT_STYLE", new String[]{"CALM", "x", ""});
    }

    @Test
    @DisplayName("15 个维度的 toToken 只产出白名单/sentinel，不会发明新 token")
    void toTokenStaysInsideTheWhitelist() {
        assertThat(SAMPLES).hasSize(BookProfileDimension.values().length);

        int checked = 0;
        for (BookProfileDimension dim : BookProfileDimension.values()) {
            String[] raws = SAMPLES.get(dim.name());
            assertThat(raws).as("维度 %s 没给样本，扫描会漏掉它", dim.name()).isNotNull();
            for (String raw : raws) {
                String token = dim.toToken(raw);
                assertThat(token).as("%s.toToken(%s) 返回 null", dim.name(), describe(raw)).isNotNull();
                boolean legal = dim.getAllowed().containsKey(token)
                        || BookProfileDimension.UNKNOWN.equals(token)
                        || BookProfileDimension.DIRTY.equals(token);
                assertThat(legal)
                        .as("%s.toToken(%s) = 「%s」既不在白名单，也不是 未填写/脏值", dim.name(), describe(raw), token)
                        .isTrue();
                checked++;
            }
            assertThat(dim.toToken(null)).as("%s.toToken(null) 应为未填写", dim.name())
                    .isEqualTo(BookProfileDimension.UNKNOWN);
            checked++;
        }
        assertThat(checked).isGreaterThan(60);
    }

    /**
     * 域断言（toToken ∈ 白名单 ∪ sentinel）**抓不到内层映射写错**：toTokens 会把白名单外的 token
     * 洗成脏值，于是「年龄桶写成 31-45」在域断言里依然合法。这里用黄金表直接钉住每个年龄段的代表值。
     */
    @Test
    @DisplayName("年龄黄金表：每个年龄段的代表值必须落到指定桶，而不是被洗成脏值")
    void ageBucketsAreExact() {
        Map<String, String> golden = new LinkedHashMap<>();
        golden.put("0", "<18");
        golden.put("17", "<18");
        golden.put("18", "18-24");
        golden.put("24", "18-24");
        golden.put("25", "25-30");
        golden.put("30", "25-30");
        golden.put("31", "31-40");
        golden.put("40", "31-40");
        golden.put("41", "41-50");
        golden.put("50", "41-50");
        golden.put("51", ">50");
        golden.put("120", ">50");
        golden.put("-1", BookProfileDimension.DIRTY);

        golden.forEach((raw, expected) -> assertThat(BookProfileDimension.AGE.toToken(raw))
                .as("AGE.toToken(%s) 应为 %s（若内层桶名写错，会被洗成脏值）", raw, expected)
                .isEqualTo(expected));
    }

    /**
     * 布尔维度（婚姻/子女）此前只有域断言、没有具体取值断言：把 truthy/falsy 写反或桶名拼错，
     * 结果会被清洗成脏值——域断言照样过，但"有孩子"的读者会从该维度的有效样本里消失。
     */
    @Test
    @DisplayName("布尔维度黄金表：1/true 与 0/false 各归其桶，其余脏值，空值未填写")
    void booleanDimensionsAreExact() {
        assertThat(BookProfileDimension.CHILDREN.toToken("1")).isEqualTo("HAS_CHILD");
        assertThat(BookProfileDimension.CHILDREN.toToken("true")).isEqualTo("HAS_CHILD");
        assertThat(BookProfileDimension.CHILDREN.toToken("0")).isEqualTo("NO_CHILD");
        assertThat(BookProfileDimension.CHILDREN.toToken("false")).isEqualTo("NO_CHILD");
        assertThat(BookProfileDimension.CHILDREN.toToken("2")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.CHILDREN.toToken("")).isEqualTo(BookProfileDimension.UNKNOWN);

        assertThat(BookProfileDimension.MARITAL.toToken("1")).isEqualTo("MARRIED");
        assertThat(BookProfileDimension.MARITAL.toToken("0")).isEqualTo("UNMARRIED");
        assertThat(BookProfileDimension.MARITAL.toToken("2")).isEqualTo(BookProfileDimension.DIRTY);

        // 规则表把「有孩子」写成 HAS_CHILD；两处必须指同一个 token
        assertThat(BookProfileDimension.CHILDREN.getAllowed()).containsKey("HAS_CHILD");
        assertThat(BookProfileDimension.CHILDREN.getAllowed()).containsKey("NO_CHILD");
    }

    private static String describe(String raw) {
        return raw.isEmpty() ? "(空串)" : "「" + raw + "」";
    }
}
