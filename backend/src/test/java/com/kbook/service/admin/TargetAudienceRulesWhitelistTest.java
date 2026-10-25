package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则表里每个 {@code Rule} 的 (维度, token) 必须落在 {@link BookProfileDimension} 的白名单里。
 *
 * 为什么单开一条：规则表写错一个 token（例如把 "HAS_CHILD" 写成 "HAS_CHILDREN"）不会抛异常，
 * 归一化会把它当"脏值"，于是吻合度那一维永远 NO_DATA——页面上只是"没数据"，没人会怀疑规则表。
 * 反过来，白名单改名而规则没跟，也一样。这条测试把两份表钉在一起。
 */
class TargetAudienceRulesWhitelistTest {

    @Test
    @DisplayName("规则表的每个桶 token 都在维度白名单内，且关键词非空")
    void everyRuleTokenIsWhitelisted() throws Exception {
        int checked = 0;
        for (Field field : TargetAudienceRules.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof List<?> rules) || rules.isEmpty()) {
                continue;
            }
            if (!"Rule".equals(rules.get(0).getClass().getSimpleName())) {
                continue;
            }
            Method dimMethod = rules.get(0).getClass().getDeclaredMethod("dim");
            Method tokenMethod = rules.get(0).getClass().getDeclaredMethod("token");
            Method keywordMethod = rules.get(0).getClass().getDeclaredMethod("keyword");
            dimMethod.setAccessible(true);
            tokenMethod.setAccessible(true);
            keywordMethod.setAccessible(true);

            for (Object rule : rules) {
                BookProfileDimension dim = (BookProfileDimension) dimMethod.invoke(rule);
                String token = (String) tokenMethod.invoke(rule);
                String keyword = (String) keywordMethod.invoke(rule);

                assertThat(keyword).as("规则关键词不能为空").isNotBlank();
                assertThat(dim.getAllowed())
                        .as("规则「%s」指向 %s 的 token「%s」，但该 token 不在白名单里（会被归一化成脏值）",
                                keyword, dim.name(), token)
                        .containsKey(token);
                checked++;
            }
        }
        assertThat(checked).as("一条规则都没读到——反射对不上这份实现，测试等于没跑").isGreaterThan(20);
    }
}
