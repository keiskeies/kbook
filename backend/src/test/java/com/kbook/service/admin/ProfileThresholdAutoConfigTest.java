package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cohort 阈值自适应的配置装配（A.3 改成运行时之后，这份配置就是「人工钉阈值」的唯一入口）。
 *
 * 两条容易出错的地方：
 * 1) 生效的是 application.yml 的默认值，@Value 兜底只在属性缺失时用——两处必须一致；
 * 2) min-cohort-size 只能有一个持有者。若 BookProfileService 里又留一份，
 *    自适应改了那份也没用，表现是"阈值配置不生效"。
 */
class ProfileThresholdAutoConfigTest {

    /** 条目 = {字段或方法标识, yml 键, 期望默认值, env 名} */
    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("floor", new String[]{"configuredMinCohortSize", "min-cohort-size", "10", "KBOOK_PROFILE_MIN_COHORT_SIZE"});
        FIELDS.put("auto", new String[]{"auto", "threshold-auto", "true", "KBOOK_PROFILE_THRESHOLD_AUTO"});
        FIELDS.put("cron", new String[]{"scheduledCalibration", "threshold-calibration-cron",
                "0 40 3 * * *", "KBOOK_PROFILE_THRESHOLD_CALIBRATION_CRON"});
    }

    /** 取某个配置项在代码里的占位符文本：两条挂在字段上，一条挂在 @Scheduled 方法上 */
    private static String placeholder(String key) throws Exception {
        if ("threshold-calibration-cron".equals(key)) {
            Method method = ProfileThresholdCalibrationService.class.getMethod("scheduledCalibration");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertThat(scheduled).as("阈值重算没有 @Scheduled，作业永远不会自己跑").isNotNull();
            return scheduled.cron();
        }
        String fieldName = FIELDS.values().stream()
                .filter(spec -> spec[1].equals(key))
                .map(spec -> spec[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("测试里没有配置项 " + key));
        Field field = ProfileThresholdCalibrationService.class.getDeclaredField(fieldName);
        Value value = field.getAnnotation(Value.class);
        assertThat(value).as("%s 上没有 @Value，阈值自适应没做成配置", fieldName).isNotNull();
        return value.value();
    }

    @Test
    @DisplayName("下界、开关与 cron 的默认值就是文档写的 10 / true / 03:40")
    void annotationDefaultsMatchDocumentation() {
        FIELDS.values().forEach(spec -> {
            try {
                assertThat(placeholder(spec[1]))
                        .as("%s 的默认值与文档不一致", spec[1])
                        .isEqualTo("${kbook.admin.book-profile." + spec[1] + ":" + spec[2] + "}");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("yml 里生效的默认值与 env 名同注解一致，且 min-cohort-size 只有一份持有者")
    void yamlMatchesAnnotationAndHasSingleOwner() throws Exception {
        Path ymlPath = Path.of("src/main/resources/application.yml");
        assertThat(Files.exists(ymlPath)).as("找不到 %s", ymlPath.toAbsolutePath()).isTrue();
        String yaml = Files.readString(ymlPath);

        FIELDS.values().forEach(spec -> {
            try {
                String[] fromYaml = braceParts(yamlValue(yaml, spec[1]), spec[1]);
                String[] fromField = braceParts(placeholder(spec[1]), spec[1]);
                assertThat(fromYaml[1]).as("%s 在 yml 的默认值与 @Value 兜底不同", spec[1])
                        .isEqualTo(fromField[1]).isEqualTo(spec[2]);
                assertThat(fromYaml[0]).as("%s 的 env 名与部署清单不一致", spec[1]).isEqualTo(spec[3]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(BookProfileService.class.getDeclaredFields())
                .as("BookProfileService 里不能再留一份 min-cohort-size：自适应改的是校准服务那份，留两份等于阈值不生效")
                .noneMatch(f -> f.getName().equals("minCohortSize"));
    }

    @Test
    @DisplayName("关掉自适应就退回配置下界；作业没跑过时生效值也等于下界")
    void disabledOrUnrunFallsBackToFloor() throws Exception {
        assertThat(newService(10, false).minCohortSize(BookProfileService.Basis.READ)).isEqualTo(10);
        assertThat(newService(7, false).minCohortSize(BookProfileService.Basis.DEEP)).isEqualTo(7);
        assertThat(newService(10, true).minCohortSize(BookProfileService.Basis.TOUCH)).isEqualTo(10);
        // 从未算过的口径也不能得到 0（0 = 闸门消失）
        ProfileThresholdCalibrationService service = new ProfileThresholdCalibrationService(null);
        set(service, "configuredMinCohortSize", 10);
        set(service, "auto", true);
        assertThat(service.minCohortSize(BookProfileService.Basis.READ)).isEqualTo(10);
    }

    @Test
    @DisplayName("重算失败不改生效值：状态里仍是下界，闸门不会因一次异常消失")
    void statusReportsFloorBeforeAnyRun() throws Exception {
        ProfileThresholdCalibrationService service = newService(10, true);
        Map<String, Object> status = service.status();
        assertThat(status.get("auto")).isEqualTo(true);
        assertThat(status.get("configuredMinCohortSize")).isEqualTo(10);
        for (BookProfileService.Basis basis : BookProfileService.Basis.values()) {
            assertThat(status.get(basis.name())).isEqualTo(10);
        }
        assertThat(status.get("minPublishableShare")).isEqualTo(0.20);
    }

    private static ProfileThresholdCalibrationService newService(int floor, boolean auto) throws Exception {
        ProfileThresholdCalibrationService service = new ProfileThresholdCalibrationService(null);
        set(service, "configuredMinCohortSize", floor);
        set(service, "auto", auto);
        service.init();
        return service;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
