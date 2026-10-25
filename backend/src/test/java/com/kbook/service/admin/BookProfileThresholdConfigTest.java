package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四个隐私 / 显著性阈值的默认值第一次有测试（§4.1、§3.2、§3.1）。
 *
 * 这些数是"读者不足就不出分布"的闸门：默认值被改错，页面不会报错，只会悄悄多给或少给分布。
 * 与缓存 TTL 一样，生效的是 **application.yml** 里的默认值（@Value 的兜底只在属性缺失时用），
 * 所以两处必须一致，且都要等于文档写的 10 / 5 / 10 / 20。
 */
class BookProfileThresholdConfigTest {

    /** 字段名 → {yml 键, 期望默认值, env 名} */
    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("minCohortSize", new String[]{"min-cohort-size", "10", "KBOOK_PROFILE_MIN_COHORT_SIZE"});
        FIELDS.put("minBucketSize", new String[]{"min-bucket-size", "5", "KBOOK_PROFILE_MIN_BUCKET_SIZE"});
        FIELDS.put("significantMinCount", new String[]{"significant-min-count", "10", "KBOOK_PROFILE_SIGNIFICANT_MIN_COUNT"});
        FIELDS.put("highlightMinCount", new String[]{"highlight-min-count", "20", "KBOOK_PROFILE_HIGHLIGHT_MIN_COUNT"});
    }

    private static String placeholder(String fieldName) throws Exception {
        Field field = BookProfileService.class.getDeclaredField(fieldName);
        Value value = field.getAnnotation(Value.class);
        assertThat(value).as("%s 上没有 @Value，阈值没做成配置", fieldName).isNotNull();
        return value.value();
    }

    @Test
    @DisplayName("四个阈值的 @Value 兜底就是文档里的 10 / 5 / 10 / 20")
    void annotationDefaultsMatchDocumentation() throws Exception {
        FIELDS.forEach((field, spec) -> {
            try {
                assertThat(placeholder(field))
                        .as("%s 的默认值与文档不一致", field)
                        .isEqualTo("${kbook.admin.book-profile." + spec[0] + ":" + spec[1] + "}");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("yml 里生效的默认值、键名与 env 名都与注解一致（否则测试钉的是没人用的值）")
    void yamlMatchesAnnotation() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        assertThat(Files.exists(yml)).as("找不到 %s", yml.toAbsolutePath()).isTrue();
        String yaml = Files.readString(yml);

        FIELDS.forEach((field, spec) -> {
            try {
                String[] fromYaml = braceParts(yamlValue(yaml, spec[0]), spec[0]);
                String[] fromField = braceParts(placeholder(field), field);

                assertThat(fromYaml[1]).as("%s 在 yml 的默认值与注解兜底不同：§4.1 的数字只剩文档意义", spec[0])
                        .isEqualTo(fromField[1]).isEqualTo(spec[1]);
                assertThat(fromField[0]).as("@Value 属性路径末段与 yml 键不一致，%s 是无主配置", spec[0])
                        .endsWith("." + spec[0]);
                assertThat(fromYaml[0]).as("%s 的 env 名与部署清单不一致", spec[0]).isEqualTo(spec[2]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    /** `${NAME:default}` → [NAME, default]；形状不对直接失败，不静默比出假绿 */
    private static String[] braceParts(String raw, String where) {
        Matcher m = Pattern.compile("^\\$\\{([^:]+):([^}]+)\\}$").matcher(raw);
        if (!m.matches()) {
            throw new AssertionError(where + " 不是 ${NAME:default} 形状：" + raw);
        }
        return new String[]{m.group(1), m.group(2)};
    }

    private static String yamlValue(String source, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*(.+)$", Pattern.MULTILINE).matcher(source);
        if (!m.find()) {
            throw new AssertionError("application.yml 里没有键 " + key);
        }
        return m.group(1).trim();
    }
}
