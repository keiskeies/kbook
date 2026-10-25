package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附录 Q 方案 A 的唯一约束：单书缓存键的第一段是「基线代号」。
 *
 * 这个缺陷的表现是「新分子 / 旧分母」——反查榜的分子每次现查，分母来自可能已经过期 10 分钟的
 * 基线缓存。方案 A 不给基线与分子做时间对齐，而是让**基线一重算，旧代号的单书缓存整体失效**：
 * 于是任何一个被返回的单书画像，都是用它那一次取到的基线算出来的。
 *
 * 这里钉两件事：键里确实有代号（纯函数，可直接断言），以及服务里确实把代号传了进去
 * （源码接线，编译器管不到——`profileCacheKey(0, …)` 同样能编译通过）。
 */
class ProfileCacheKeyTest {

    @Test
    @DisplayName("键的第一段就是基线代号")
    void generationIsTheLeadingSegment() {
        assertThat(BookProfileService.profileCacheKey(7, 24715, "READ", "all"))
                .startsWith("7|");
    }

    @Test
    @DisplayName("代号不同 → 键不同：这是方案 A 的全部保证")
    void differentGenerationYieldsDifferentKey() {
        String before = BookProfileService.profileCacheKey(1, 24715, "READ", "all");
        String after = BookProfileService.profileCacheKey(2, 24715, "READ", "all");

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("同参同代号 → 键相同（否则缓存永远命中不了）")
    void sameInputsYieldSameKey() {
        assertThat(BookProfileService.profileCacheKey(3, 1, "DEEP", "2026-01-01~2026-01-31"))
                .isEqualTo(BookProfileService.profileCacheKey(3, 1, "DEEP", "2026-01-01~2026-01-31"));
    }

    @Test
    @DisplayName("书 / 口径 / 窗口任一不同都要区分开")
    void otherSegmentsStillParticipate() {
        String base = BookProfileService.profileCacheKey(1, 1, "READ", "all");

        assertThat(base).isNotEqualTo(BookProfileService.profileCacheKey(1, 2, "READ", "all"));
        assertThat(base).isNotEqualTo(BookProfileService.profileCacheKey(1, 1, "TOUCH", "all"));
        assertThat(base).isNotEqualTo(BookProfileService.profileCacheKey(1, 1, "READ", "7天"));
    }

    /**
     * 纯函数测不到「服务真的用了它」。改回 `bookId + "|" + basis + …` 同样编译通过，
     * 所以这里读源码接线：既要求出现带代号的调用，也禁止旧的拼接写法回来。
     */
    @Test
    @DisplayName("服务里确实把基线代号传进键（源码接线）")
    void serviceWiresGenerationIntoTheKey() throws Exception {
        Path src = Path.of("src/main/java/com/kbook/service/admin/BookProfileService.java");
        assertThat(Files.exists(src)).as("找不到源码：%s", src.toAbsolutePath()).isTrue();
        String code = Files.readString(src);

        assertThat(code).contains("profileCacheKey(site.generation()");
        assertThat(code).doesNotContain("String cacheKey = bookId + \"|\"");
        // 基线值与代号必须成对取用
        assertThat(code).contains("record BaselineSnapshot(SiteBaseline value, long generation)");
        assertThat(code).contains("baselineGeneration.getAndIncrement()");
    }
}
