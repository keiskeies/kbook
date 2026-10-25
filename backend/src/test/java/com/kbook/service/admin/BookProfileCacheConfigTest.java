package com.kbook.service.admin;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7.4 的两条缓存寿命第一次成为可执行断言（设计文档 7.4 节 + 附录 Q）。
 *
 * 之前 5 分钟 / 10 分钟只写在注释里：谁把它们改成一样、或者只改一份，都不会有测试变红，
 * 而"分子与分母不同龄"正是附录 Q 那个 lift 缺陷的来源。这里钉三件事：
 * 默认值仍是文档里的 5 / 10，寿命确实来自配置，以及两份缓存各自独立过期（Q 的结构性前提）。
 */
class BookProfileCacheConfigTest {

    private static Object field(Object target, String name) throws Exception {
        Field f = BookProfileService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = BookProfileService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Caffeine 的 Policy 嵌套类型在不同小版本里换过名字，这里只取它打印出来的时长，避免把测试绑在 API 形状上 */
    private static String expiresAfter(Object cache) {
        var expiry = ((Cache<?, ?>) cache).policy().expireAfterWrite();
        return expiry.isPresent() ? String.valueOf(expiry.get().getExpiresAfter()) : "<无写后过期>";
    }

    private static String maximum(Object cache) {
        var eviction = ((Cache<?, ?>) cache).policy().eviction();
        return eviction.isPresent() ? String.valueOf(eviction.get().getMaximum()) : "<无上限>";
    }

    private static BookProfileService serviceWith(int profileMinutes, int baselineMinutes) throws Exception {
        BookProfileService service = new BookProfileService(null, null);
        setField(service, "profileCacheMinutes", profileMinutes);
        setField(service, "baselineCacheMinutes", baselineMinutes);
        service.buildCaches();
        return service;
    }

    /** 取 @Value 注解里的占位符原文，冒号后那段就是默认值 */
    private static String placeholder(String fieldName) {
        try {
            Field f = BookProfileService.class.getDeclaredField(fieldName);
            Value value = f.getAnnotation(Value.class);
            assertThat(value).as("%s 上没有 @Value，寿命根本没做成配置", fieldName).isNotNull();
            return value.value();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("默认寿命还是 §7.4 写的 5 与 10 分钟，且带 env 覆盖入口")
    void defaultsMatchSectionSevenFour() {
        assertThat(placeholder("profileCacheMinutes"))
                .isEqualTo("${kbook.admin.book-profile.profile-cache-minutes:5}");
        assertThat(placeholder("baselineCacheMinutes"))
                .isEqualTo("${kbook.admin.book-profile.baseline-cache-minutes:10}");
    }

    @Test
    @DisplayName("寿命来自配置：给 3 / 7 就建出 3 / 7 分钟，不是写死的数")
    void expiryComesFromConfiguration() throws Exception {
        BookProfileService service = serviceWith(3, 7);

        assertThat(expiresAfter(field(service, "profileCache"))).contains("PT3M");
        assertThat(expiresAfter(field(service, "siteCache"))).contains("PT7M");
    }

    /**
     * 两条寿命仍然各设各的——附录 Q 的方案 A 不靠对齐时间解决它，而是让基线一重算，
     * 旧代号的单书缓存整体失效（见 {@link ProfileCacheKeyTest}）。所以「两份缓存不同龄」
     * 现在是无害的结构事实，这条保留为结构说明，不再是"缺陷还在"的哨兵。
     */
    @Test
    @DisplayName("两份缓存各自独立：基线过期不会让单书缓存作废")
    void cachesExpireIndependently() throws Exception {
        BookProfileService service = serviceWith(5, 5);
        Object profile = field(service, "profileCache");
        Object baseline = field(service, "siteCache");

        assertThat(expiresAfter(profile)).isEqualTo(expiresAfter(baseline));
        assertThat(profile).isNotSameAs(baseline);
    }

    @Test
    @DisplayName("容量仍按 §7.4：单书 500 条、基线 4 条（单键 site）")
    void maximumSizesUnchanged() throws Exception {
        BookProfileService service = serviceWith(5, 10);

        assertThat(maximum(field(service, "profileCache"))).isEqualTo("500");
        assertThat(maximum(field(service, "siteCache"))).isEqualTo("4");
    }

    @Test
    @DisplayName("缓存字段在 @PostConstruct 之前是 null——这就是不能在字段初始化时读 @Value 的原因")
    void cachesExistOnlyAfterPostConstruct() throws Exception {
        BookProfileService service = new BookProfileService(null, null);

        assertThat(field(service, "profileCache")).isNull();
        assertThat(field(service, "siteCache")).isNull();

        service.buildCaches();
        assertThat(field(service, "profileCache")).isNotNull();
        assertThat(field(service, "siteCache")).isNotNull();
    }

    /**
     * 上一条是自己 new 出来手动调 `buildCaches()` 的，所以它证明不了 Spring 会调这个方法。
     * 真线上跑的是容器：`@PostConstruct` 被删掉时缓存永远是 null，第一个请求就 NPE——
     * 而单测全绿。这条把容器那一侧的接线钉住。
     */
    @Test
    @DisplayName("buildCaches 必须带 @PostConstruct：漏了就是单测全绿、线上第一个请求 NPE")
    void buildCachesIsAnnotatedForTheContainer() throws Exception {
        var method = BookProfileService.class.getDeclaredMethod("buildCaches");

        assertThat(method.getAnnotation(PostConstruct.class))
                .as("buildCaches() 上没有 @PostConstruct，Spring 启动时不会建缓存")
                .isNotNull();
    }

    /**
     * `@Value` 的冒号默认值只在属性缺失时生效，而 application.yml 一定写全这两个键，
     * 所以**真正生效的是 yml 里那个数**。此前没有任何测试读过 yml：把 yml 的默认值改成 `:60`、
     * 或把 env 名改掉（部署清单里的名字就此失效），上面几条断言一条都不会红。
     *
     * 两边的 `${…}` 本来就不该逐字相等——注解里是属性路径、yml 里是 env 名，
     * 所以这里比的是三段真正该一致的东西：默认数字、属性名的最后一段、以及文档里承诺的 env 名。
     */
    @Test
    @DisplayName("yml 生效值 = 注解兜底值，且 env 名与部署清单一致")
    void yamlDefaultsAndEnvNamesMatchTheInjectedOnes() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        assertThat(Files.exists(yml)).as("找不到 %s，这条断言没在看任何东西", yml.toAbsolutePath()).isTrue();

        String source = Files.readString(yml);

        assertYamlAgreesWithAnnotation(source, "profile-cache-minutes", "profileCacheMinutes", "KBOOK_PROFILE_CACHE_MINUTES");
        assertYamlAgreesWithAnnotation(source, "baseline-cache-minutes", "baselineCacheMinutes", "KBOOK_PROFILE_BASELINE_CACHE_MINUTES");
    }

    private static void assertYamlAgreesWithAnnotation(String source, String yamlKey, String fieldName, String expectedEnv) {
        String[] fromYaml = placeholderParts(yamlValue(source, yamlKey), yamlKey);
        String[] fromField = placeholderParts(placeholder(fieldName), fieldName);

        // yml 里那串才是生效值：键名对不上属性路径 = 这个键根本没被读到，注解值反倒成了唯一来源
        assertThat(fromYaml[1]).as("%s 的生效默认值与 @Value 兜底值不同：§7.4 的分钟数只剩文档意义", yamlKey)
                .isEqualTo(fromField[1]);
        assertThat(fromField[0]).as("@Value 的属性路径最后一段与 yml 的键不一致，%s 是无主配置", yamlKey)
                .endsWith("." + yamlKey);
        assertThat(fromYaml[0]).as("%s 的 env 名与部署清单/§7.4 承诺的不一致", yamlKey)
                .isEqualTo(expectedEnv);
    }

    /** `${NAME:default}` → [NAME, default]；不是这个形状就直接失败，而不是悄悄比出一个假绿 */
    private static String[] placeholderParts(String raw, String where) {
        Matcher m = Pattern.compile("^\\$\\{([^:]+):([^}]+)\\}$").matcher(raw);
        if (!m.matches()) {
            throw new AssertionError(where + " 不是 ${NAME:default} 形状，实际是 " + raw);
        }
        return new String[]{m.group(1), m.group(2)};
    }

    private static String yamlValue(String source, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*(.+)$", Pattern.MULTILINE).matcher(source);
        if (!m.find()) {
            throw new AssertionError("application.yml 里没有键 " + key + "，寿命改成只在注解里有默认值");
        }
        return m.group(1).trim();
    }
}
