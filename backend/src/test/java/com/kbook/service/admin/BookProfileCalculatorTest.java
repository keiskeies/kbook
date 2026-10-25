package com.kbook.service.admin;

import com.kbook.dto.admin.BookProfileVO;
import com.kbook.service.admin.BookProfileCalculator.DimCounts;
import com.kbook.service.admin.BookProfileCalculator.Thresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计文档第 11 节的单测：归一化、分桶、显著性、k-匿名、结论文案。
 * 阈值全部走入参，校准后（4.1）改配置不影响这里。
 */
class BookProfileCalculatorTest {

    /** 默认阈值：cohort 10 / bucket 5 / significant 10 / highlight 20 */
    private static final Thresholds DEFAULT = new Thresholds(10, 5, 10, 20);
    /** 小样本库的降级阈值：验证阈值确实是可调参数而非写死 */
    private static final Thresholds TINY = new Thresholds(3, 2, 3, 5);

    // ==================== 归一化 ====================

    @Test
    @DisplayName("性别：大小写折叠，白名单外的值进脏值")
    void genderNormalization() {
        Map<String, Long> raw = counts("FEMALE", 3, "male", 2, "{{7*7}}", 1, "未填写", 4);
        DimCounts dim = BookProfileCalculator.normalize(BookProfileDimension.GENDER, raw);

        assertThat(dim.buckets()).containsOnlyKeys("FEMALE", "MALE");
        assertThat(dim.buckets().get("MALE")).isEqualTo(2L);
        // 注册接口没做取值校验，注入串必须被挡在展示之外
        assertThat(dim.dirty()).isEqualTo(1L);
        assertThat(dim.unknown()).isEqualTo(4L);
        assertThat(dim.total()).isEqualTo(10L);
        assertThat(dim.validSample()).isEqualTo(5L);
    }

    @Test
    @DisplayName("婚姻：布尔列 1/0 映射展示 token，其他值进脏值")
    void maritalNormalization() {
        assertThat(BookProfileDimension.MARITAL.toToken("1")).isEqualTo("MARRIED");
        assertThat(BookProfileDimension.MARITAL.toToken("0")).isEqualTo("UNMARRIED");
        assertThat(BookProfileDimension.MARITAL.toToken("2")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.MARITAL.toToken(null)).isEqualTo(BookProfileDimension.UNKNOWN);
    }

    // ==================== 年龄分桶 ====================

    @Test
    @DisplayName("年龄：NULL、未来日期、18 岁当天与每个区间上界")
    void ageBuckets() {
        assertThat(BookProfileDimension.AGE.toToken(null)).isEqualTo(BookProfileDimension.UNKNOWN);
        assertThat(BookProfileDimension.AGE.toToken("")).isEqualTo(BookProfileDimension.UNKNOWN);
        // birthday > CURDATE() 时 SQL 产出 -1
        assertThat(BookProfileDimension.AGE.toToken("-1")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.AGE.toToken("abc")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.AGE.toToken("17")).isEqualTo("<18");
        assertThat(BookProfileDimension.AGE.toToken("18")).isEqualTo("18-24");
        assertThat(BookProfileDimension.AGE.toToken("24")).isEqualTo("18-24");
        assertThat(BookProfileDimension.AGE.toToken("25")).isEqualTo("25-30");
        assertThat(BookProfileDimension.AGE.toToken("30")).isEqualTo("25-30");
        assertThat(BookProfileDimension.AGE.toToken("31")).isEqualTo("31-40");
        assertThat(BookProfileDimension.AGE.toToken("41")).isEqualTo("41-50");
        assertThat(BookProfileDimension.AGE.toToken("50")).isEqualTo("41-50");
        assertThat(BookProfileDimension.AGE.toToken("51")).isEqualTo(">50");
        assertThat(BookProfileDimension.AGE.toToken("60")).isEqualTo(">50");
    }

    // ==================== MBTI ====================

    @Test
    @DisplayName("MBTI 脏数据：后缀、小写、过短、空串")
    void mbtiDirtyData() {
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("INTJ")).isEqualTo("INTJ");
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("INTJ-A")).isEqualTo("INTJ");
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("intj")).isEqualTo("INTJ");
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("IN")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("")).isEqualTo(BookProfileDimension.UNKNOWN);
        // 轴维度对长度不足 4 的记录按未填写处理（文档第 5 节）
        assertThat(BookProfileDimension.MBTI_EI.toToken("IN")).isEqualTo(BookProfileDimension.UNKNOWN);
        assertThat(BookProfileDimension.MBTI_EI.toToken("INTJ")).isEqualTo("I");
        assertThat(BookProfileDimension.MBTI_SN.toToken("INTJ")).isEqualTo("N");
        assertThat(BookProfileDimension.MBTI_TF.toToken("INTJ")).isEqualTo("T");
        assertThat(BookProfileDimension.MBTI_JP.toToken("INTJ")).isEqualTo("J");
        assertThat(BookProfileDimension.MBTI_EI.toToken("XNTJ")).isEqualTo(BookProfileDimension.DIRTY);
    }

    // ==================== P2 归一化：混存折叠 ====================

    @Test
    @DisplayName("职业：同一列的分类 token 与职位名折叠进同一桶（实测 ENGINEER/Engineer/engineer/hacker 并存）")
    void occupationFoldsJobTitlesIntoCategory() {
        assertThat(BookProfileDimension.OCCUPATION.toToken("ENGINEER")).isEqualTo("TECH");
        assertThat(BookProfileDimension.OCCUPATION.toToken("Engineer")).isEqualTo("TECH");
        assertThat(BookProfileDimension.OCCUPATION.toToken("hacker")).isEqualTo("TECH");
        assertThat(BookProfileDimension.OCCUPATION.toToken("TECH")).isEqualTo("TECH");
        assertThat(BookProfileDimension.OCCUPATION.toToken("FINANCE")).isEqualTo("FINANCE");
        assertThat(BookProfileDimension.OCCUPATION.toToken("freelance")).isEqualTo("FREELANCE");
        // 未识别的自由文本进脏值，避免污染榜单
        assertThat(BookProfileDimension.OCCUPATION.toToken("全栈架构师")).isEqualTo(BookProfileDimension.DIRTY);

        DimCounts dim = BookProfileCalculator.normalize(BookProfileDimension.OCCUPATION,
                counts("ENGINEER", 4, "Engineer", 3, "engineer", 1, "hacker", 1, "TECH", 2, "未填写", 5));
        // 三个榜位其实是同一拨人：折叠后 TECH 只有一个桶
        assertThat(dim.buckets()).containsOnlyKeys("TECH");
        assertThat(dim.buckets().get("TECH")).isEqualTo(11L);
        assertThat(dim.validSample()).isEqualTo(11L);
        assertThat(dim.unknown()).isEqualTo(5L);
    }

    @Test
    @DisplayName("学历/收入/创业：大小写与格式变体折叠，历史枚举与注入串进脏值")
    void legacyEnumValuesBecomeDirty() {
        assertThat(BookProfileDimension.EDUCATION.toToken("master")).isEqualTo("MASTER");
        assertThat(BookProfileDimension.EDUCATION.toToken("Bachelor")).isEqualTo("BACHELOR");
        assertThat(BookProfileDimension.EDUCATION.toToken("BACHELOR")).isEqualTo("BACHELOR");
        assertThat(BookProfileDimension.EDUCATION.toToken("PhD")).isEqualTo("DOCTORATE");
        // 实测存着 SELF，如今 onboarding 已无对应选项，语义不可还原
        assertThat(BookProfileDimension.EDUCATION.toToken("SELF")).isEqualTo(BookProfileDimension.DIRTY);

        assertThat(BookProfileDimension.INCOME.toToken("100k")).isEqualTo("50K_150K");
        assertThat(BookProfileDimension.INCOME.toToken("100K")).isEqualTo("50K_150K");
        assertThat(BookProfileDimension.INCOME.toToken("300K_500K")).isEqualTo("300K_500K");
        // 档位制（MEDIUM）与区间制混在同一列，未识别一律脏值
        assertThat(BookProfileDimension.INCOME.toToken("MEDIUM")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.INCOME.toToken("{{7*7}}")).isEqualTo(BookProfileDimension.DIRTY);

        assertThat(BookProfileDimension.ENTREPRENEURSHIP.toToken("true")).isEqualTo("ENTREPRENEUR_OR_WANT");
        assertThat(BookProfileDimension.ENTREPRENEURSHIP.toToken("false")).isEqualTo("NOT_INTERESTED");
        assertThat(BookProfileDimension.ENTREPRENEURSHIP.toToken("NOT_INTERESTED")).isEqualTo("NOT_INTERESTED");
        assertThat(BookProfileDimension.ENTREPRENEURSHIP.toToken("Startup")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.ENTREPRENEURSHIP.toToken("{{7*7}}")).isEqualTo(BookProfileDimension.DIRTY);
    }

    @Test
    @DisplayName("注入串不参与 lift、不进榜单：脏值只以 dirtyCount 暴露")
    void dirtyBucketCarriesNoLift() {
        BookProfileVO.Dimension result = dimension(BookProfileDimension.INCOME,
                counts("50K_150K", 8, "{{7*7}}", 2), counts("50K_150K", 40, "{{7*7}}", 10), TINY);
        assertThat(result.getBuckets()).extracting(BookProfileVO.Bucket::getValue)
                .containsExactly("50K_150K");
        assertThat(result.getDirtyCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("白名单统一收口：折叠完仍不在表里的 token 一律脏值，GENDER/CHAT_STYLE 也不例外")
    void whitelistIsEnforcedCentrally() {
        assertThat(BookProfileDimension.MBTI_TYPE.toToken("XNTJ")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.GENDER.toToken("{{7*7}}")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.CHAT_STYLE.toToken("unknown_style")).isEqualTo(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.CHAT_STYLE.toToken("deep")).isEqualTo("DEEP");
        assertThat(BookProfileDimension.CHILD_AGE.toToken("children_3_6")).isEqualTo("CHILDREN_3_6");
    }

    // ==================== 看板全站画像分布（与读者画像同一口径） ====================

    private static List<Object[]> siteRows(Object[]... rows) {
        return List.of(rows);
    }

    @Test
    @DisplayName("看板分布：脏值不作为桶标签出现，单独进「脏值」一行")
    void dashboardDistributionHidesDirtyValues() {
        Map<String, Long> display = BookProfileCalculator.dashboardDistribution(BookProfileDimension.GENDER,
                siteRows(new Object[]{"MALE", 4L}, new Object[]{"FEMALE", 2L}, new Object[]{"{{7*7}}", 1L}));

        assertThat(display).doesNotContainKey("{{7*7}}");
        assertThat(display).containsEntry(BookProfileDimension.DIRTY, 1L);
        assertThat(display).containsEntry("MALE", 4L);
    }

    @Test
    @DisplayName("看板分布：未填写不再被静默丢掉，各桶之和等于取回的人数")
    void dashboardDistributionKeepsUnfilledVisible() {
        Map<String, Long> display = BookProfileCalculator.dashboardDistribution(BookProfileDimension.GENDER,
                siteRows(new Object[]{"MALE", 4L}, new Object[]{null, 6L}, new Object[]{"FEMALE", 2L}));

        assertThat(display).containsEntry(BookProfileDimension.UNKNOWN, 6L);
        assertThat(display.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(12L);
    }

    @Test
    @DisplayName("看板分布：走归一化而不是原始取值（大小写与空格折叠进同一个桶）")
    void dashboardDistributionNormalizesBeforeRendering() {
        Map<String, Long> display = BookProfileCalculator.dashboardDistribution(BookProfileDimension.GENDER,
                siteRows(new Object[]{" male ", 3L}, new Object[]{"MALE", 1L}, new Object[]{"男", 2L}));

        // 「男」是展示名不是合法存储值，读侧照样进脏值——与 §11「反查展示名返回空榜」同一条口径
        assertThat(display).containsOnly(
                Map.entry("MALE", 4L),
                Map.entry(BookProfileDimension.DIRTY, 2L));
    }

    @Test
    @DisplayName("看板分布：键仍是前端映射用的 token，不改成中文标签")
    void dashboardDistributionKeepsTokenKeysForFrontend() {
        Map<String, Long> gender = BookProfileCalculator.dashboardDistribution(BookProfileDimension.GENDER,
                siteRows(new Object[]{"MALE", 4L}, new Object[]{"OTHER", 1L}));
        Map<String, Long> mbti = BookProfileCalculator.dashboardDistribution(BookProfileDimension.MBTI_TYPE,
                siteRows(new Object[]{"INTJ", 2L}, new Object[]{"ENTJ", 1L}));

        assertThat(gender.keySet()).containsExactlyInAnyOrder("MALE", "OTHER");
        assertThat(mbti.keySet()).containsExactlyInAnyOrder("INTJ", "ENTJ");
    }

    // ==================== P2 归一化：逗号多值 ====================

    @Test
    @DisplayName("孩子年龄段：一人进多桶，无孩 token 覆盖同行其余年龄段")
    void childAgeMultiValue() {
        assertThat(BookProfileDimension.CHILD_AGE.isMultiValue()).isTrue();
        assertThat(BookProfileDimension.CHILD_AGE.toTokens("children_3_6"))
                .containsExactly("CHILDREN_3_6");
        assertThat(BookProfileDimension.CHILD_AGE.toTokens("children_no_children,children_3_6"))
                .containsExactly("CHILDREN_NO_CHILDREN");
        assertThat(BookProfileDimension.CHILD_AGE.toTokens("children_3_6,children_3_6"))
                .containsExactly("CHILDREN_3_6");
        assertThat(BookProfileDimension.CHILD_AGE.toTokens("children_99"))
                .containsExactly(BookProfileDimension.DIRTY);
        assertThat(BookProfileDimension.CHILD_AGE.toTokens(null)).containsExactly(BookProfileDimension.UNKNOWN);
    }

    @Test
    @DisplayName("多值维度占比分母是「人」不是「桶次数」，因此各桶占比之和可以 > 100%")
    void multiValueRatioMayExceedOne() {
        DimCounts book = BookProfileCalculator.normalize(BookProfileDimension.CHILD_AGE,
                counts("children_3_6,children_7_12", 4, "children_3_6", 3));
        assertThat(book.total()).isEqualTo(7L);
        assertThat(book.validSample()).isEqualTo(7L);
        assertThat(book.buckets()).containsEntry("CHILDREN_3_6", 7L).containsEntry("CHILDREN_7_12", 4L);

        BookProfileVO.Dimension dim = BookProfileCalculator.buildDimension(BookProfileDimension.CHILD_AGE, book, book, TINY);
        assertThat(dim.isMultiValue()).isTrue();
        assertThat(dim.getBuckets()).hasSize(2);
        double sum = dim.getBuckets().stream().mapToDouble(BookProfileVO.Bucket::getRatio).sum();
        assertThat(sum).isGreaterThan(1D);
        assertThat(dim.getBuckets()).allSatisfy(item -> assertThat(item.getLift()).isEqualTo(1D));
    }

    @Test
    @DisplayName("反查白名单：多值维度与样本过薄的学历/收入/创业不参与榜单")
    void queryableWhitelist() {
        assertThat(BookProfileDimension.GENDER.isQueryable()).isTrue();
        assertThat(BookProfileDimension.AGE.isQueryable()).isTrue();
        assertThat(BookProfileDimension.MBTI_TYPE.isQueryable()).isTrue();
        assertThat(BookProfileDimension.CHAT_STYLE.isQueryable()).isTrue();
        assertThat(BookProfileDimension.CHILD_AGE.isQueryable()).isFalse();
        assertThat(BookProfileDimension.OCCUPATION.isQueryable()).isFalse();
        assertThat(BookProfileDimension.EDUCATION.isQueryable()).isFalse();
        assertThat(BookProfileDimension.INCOME.isQueryable()).isFalse();
        assertThat(BookProfileDimension.ENTREPRENEURSHIP.isQueryable()).isFalse();
    }

    // ==================== 显著性 ====================

    @Test
    @DisplayName("Wilson 区间：50/100 的 95% 区间为 [0.4038, 0.5962]")
    void wilsonFormula() {
        double[] interval = BookProfileCalculator.wilson(50, 100);
        assertThat(interval[0]).isCloseTo(0.4038, org.assertj.core.data.Offset.offset(0.0005));
        assertThat(interval[1]).isCloseTo(0.5962, org.assertj.core.data.Offset.offset(0.0005));
        // 基线落在区间外才算显著
        assertThat(BookProfileCalculator.isSignificant(50, 100, 0.6D)).isTrue();
        assertThat(BookProfileCalculator.isSignificant(50, 100, 0.55D)).isFalse();
    }

    @Test
    @DisplayName("count 阈值：9 人不参与显著性判定，10 人翻转")
    void significanceCountGate() {
        BookProfileVO.Dimension nine = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 9, "MALE", 1), counts("FEMALE", 500, "MALE", 500), DEFAULT);
        assertThat(bucket(nine, "FEMALE").isSignificant()).isFalse();

        BookProfileVO.Dimension ten = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 10), counts("FEMALE", 500, "MALE", 500), DEFAULT);
        assertThat(bucket(ten, "FEMALE").isSignificant()).isTrue();
    }

    @Test
    @DisplayName("同样的 60% 占比：20 人还不足以判定偏离，100 人才越过 Wilson 下界")
    void significanceFlipsWithSampleSize() {
        assertThat(BookProfileCalculator.isSignificant(12, 20, 0.5D)).isFalse();
        assertThat(BookProfileCalculator.isSignificant(60, 100, 0.5D)).isTrue();
    }

    /**
     * 附录 AH：siteRatio 的分母是全站样本，但分子可能是全站小格——"全站 2/22 属于 X" 同样是单格比例，
     * 而且拿它当 lift 基线本身就不稳。全站该桶不足 min-bucket 时不给 siteRatio/lift。
     */
    @Test
    @DisplayName("全站该桶不足 min-bucket 时 siteRatio/lift 为 null，但本书桶照常发布")
    void smallSiteCellSuppressesBaselineOnly() {
        BookProfileCalculator.DimCounts book =
                new BookProfileCalculator.DimCounts(20, 20, 0, 0, Map.of("FEMALE", 10L, "MALE", 10L));
        BookProfileCalculator.DimCounts site =
                new BookProfileCalculator.DimCounts(22, 22, 0, 0, Map.of("FEMALE", 2L, "MALE", 20L));

        BookProfileVO.Dimension dim = BookProfileCalculator.buildDimension(
                BookProfileDimension.GENDER, book, site, DEFAULT);
        BookProfileVO.Bucket female = dim.getBuckets().stream()
                .filter(b -> "FEMALE".equals(b.getValue())).findFirst().orElseThrow();

        assertThat(female.getCount()).isEqualTo(10L);
        assertThat(female.getRatio()).isEqualTo(0.5);
        assertThat(female.getSiteRatio()).isNull();
        assertThat(female.getLift()).isNull();
        assertThat(female.isSignificant()).isFalse();
    }

    private static BookProfileVO.Bucket hb(String value, String label, double lift) {
        return BookProfileVO.Bucket.builder()
                .value(value).label(label).count(30).ratio(0.3).siteRatio(0.1)
                .lift(lift).significant(true).build();
    }

    /** §3.1：结论最多 3 条（偏高 2 + 偏低 1），且各取最极端的那几条——上限与排序都没有测试钉着 */
    @Test
    @DisplayName("结论上限与排序：偏高按 lift 降序取 2，偏低取 1，共 3 条")
    void highlightsAreCappedAndOrdered() {
        BookProfileVO.Dimension dim = BookProfileVO.Dimension.builder()
                .key("GENDER").label("性别")
                .buckets(List.of(hb("A", "甲", 1.5), hb("B", "乙", 2.0), hb("C", "丙", 3.0),
                        hb("D", "丁", 0.5), hb("E", "戊", 0.3)))
                .build();

        List<String> highlights = BookProfileCalculator.buildHighlights(List.of(dim), DEFAULT);

        assertThat(highlights).hasSize(3);
        assertThat(highlights.get(0)).startsWith("性别·丙");
        assertThat(highlights.get(1)).startsWith("性别·乙");
        assertThat(highlights.get(2)).startsWith("性别·戊");
    }

    @Test
    @DisplayName("没有显著桶时给一句兜底，而不是空列表")
    void highlightsFallBackWhenNothingSignificant() {
        BookProfileVO.Bucket flat = hb("A", "甲", 1.0);
        flat.setSignificant(false);
        BookProfileVO.Dimension dim = BookProfileVO.Dimension.builder()
                .key("GENDER").label("性别").buckets(List.of(flat)).build();

        assertThat(BookProfileCalculator.buildHighlights(List.of(dim), DEFAULT))
                .containsExactly("读者结构与全站接近，无明显人群偏好");
    }

    @Test
    @DisplayName("结论门槛 highlight-min-count：19 人不出结论、20 人出；换 3/2/3/5 阈值后同一份数据直接出")
    void highlightCountBoundary() {
        Map<String, Long> book = counts("FEMALE", 19, "MALE", 1);
        Map<String, Long> site = counts("FEMALE", 500, "MALE", 500);

        BookProfileVO.Dimension nineteen = dimension(BookProfileDimension.GENDER, book, site, DEFAULT);
        // 显著性已经成立，只差人数：文案宁可不写，也不给一条 19 人的结论
        assertThat(bucket(nineteen, "FEMALE").isSignificant()).isTrue();
        assertThat(BookProfileCalculator.buildHighlights(List.of(nineteen), DEFAULT))
                .containsExactly("读者结构与全站接近，无明显人群偏好");

        BookProfileVO.Dimension twenty = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 20), site, DEFAULT);
        assertThat(BookProfileCalculator.buildHighlights(List.of(twenty), DEFAULT)).hasSize(1);

        assertThat(BookProfileCalculator.buildHighlights(List.of(nineteen), TINY)).hasSize(1);
    }

    @Test
    @DisplayName("siteRatio 为 0 时不做除法，lift 返回 null")
    void liftDivideByZeroGuard() {
        BookProfileVO.Dimension result = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 30, "MALE", 20), counts(), DEFAULT);
        assertThat(result.getBuckets()).hasSize(2);
        for (BookProfileVO.Bucket bucket : result.getBuckets()) {
            assertThat(bucket.getSiteRatio()).isNull();
            assertThat(bucket.getLift()).isNull();
            assertThat(bucket.isSignificant()).isFalse();
        }
    }

    // ==================== k-匿名 ====================

    @Test
    @DisplayName("人群闸门：读者少于 min-cohort 就整体不出分布；10/5 与 3/2 两组阈值各验一遍边界")
    void cohortGate() {
        assertThat(DEFAULT.isSampleProtected(9)).isTrue();
        assertThat(DEFAULT.isSampleProtected(10)).isFalse();
        assertThat(TINY.isSampleProtected(2)).isTrue();
        assertThat(TINY.isSampleProtected(3)).isFalse();
    }

    @Test
    @DisplayName("小桶并入其他且不展示 lift；换一组阈值后同一条数据不再并桶")
    void smallBucketMerge() {
        Map<String, Long> book = counts("FEMALE", 4, "MALE", 8);
        Map<String, Long> site = counts("FEMALE", 50, "MALE", 50);

        BookProfileVO.Dimension merged = dimension(BookProfileDimension.GENDER, book, site, DEFAULT);
        assertThat(merged.getBuckets()).extracting(BookProfileVO.Bucket::getLabel)
                .containsExactly("男", BookProfileDimension.OTHER);
        assertThat(bucketByLabel(merged, BookProfileDimension.OTHER).getLift()).isNull();
        assertThat(bucketByLabel(merged, BookProfileDimension.OTHER).getCount()).isEqualTo(4L);

        BookProfileVO.Dimension kept = dimension(BookProfileDimension.GENDER, book, site, TINY);
        // 非 topN 维度按白名单声明顺序输出（男在前），不是按人数排序
        assertThat(kept.getBuckets()).extracting(BookProfileVO.Bucket::getLabel)
                .containsExactly("男", "女");
    }

    @Test
    @DisplayName("MBTI 只保留人数前 6，其余并入其他")
    void mbtiTopNTrim() {
        Map<String, Long> book = counts("INTJ", 90, "ENTJ", 80, "INFJ", 70, "ENFP", 60,
                "ISTP", 50, "INFP", 40, "ENTP", 30, "ESFP", 20);
        BookProfileVO.Dimension result = dimension(BookProfileDimension.MBTI_TYPE, book, book, TINY);

        assertThat(result.getBuckets()).hasSize(7);
        assertThat(result.getBuckets()).extracting(BookProfileVO.Bucket::getLabel)
                .containsExactly("INTJ", "ENTJ", "INFJ", "ENFP", "ISTP", "INFP", BookProfileDimension.OTHER);
        assertThat(bucketByLabel(result, BookProfileDimension.OTHER).getCount()).isEqualTo(50L);
    }

    @Test
    @DisplayName("coverage=0 时返回空桶，而不是 100% 未知")
    void coverageZeroReturnsEmptyBuckets() {
        BookProfileVO.Dimension result = dimension(BookProfileDimension.MARITAL,
                counts("未填写", 12), counts("MARRIED", 40, "UNMARRIED", 60), DEFAULT);
        assertThat(result.getBuckets()).isEmpty();
        assertThat(result.getCoverage()).isZero();
        assertThat(result.getUnknownCount()).isEqualTo(12L);
    }

    // ==================== 结论文案 ====================

    @Test
    @DisplayName("显著偏高且人数够样本才出结论；无显著项时给固定文案")
    void highlightText() {
        BookProfileVO.Dimension gender = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 60, "MALE", 10), counts("FEMALE", 500, "MALE", 500), DEFAULT);
        List<String> highlights = BookProfileCalculator.buildHighlights(List.of(gender), DEFAULT);

        assertThat(highlights).hasSize(1);
        assertThat(highlights.get(0)).startsWith("性别·女 读者占比 86%").contains("为全站 1.71 倍");
        // MALE 显著偏低但 count=10 < highlight-min-count=20，不进结论
        assertThat(highlights.get(0)).doesNotContain("男");

        BookProfileVO.Dimension flat = dimension(BookProfileDimension.GENDER,
                counts("FEMALE", 5, "MALE", 5), counts("FEMALE", 500, "MALE", 500), DEFAULT);
        assertThat(BookProfileCalculator.buildHighlights(List.of(flat), DEFAULT))
                .containsExactly("读者结构与全站接近，无明显人群偏好");
    }

    // ==================== 反查候选（§4 规则 5）====================

    @Test
    @DisplayName("反查候选的 count 下限与排序无关：够不够出榜只由阈值决定")
    void candidateFloorIsNotSortDependent() {
        Map<Long, Long> bookCounts = new LinkedHashMap<>();
        bookCounts.put(1L, 1L);
        bookCounts.put(2L, 5L);
        bookCounts.put(3L, 4L);
        bookCounts.put(4L, 9L);

        // 边界：== min-bucket-size 出榜，差 1 不出
        assertThat(BookProfileCalculator.rankingCandidates(bookCounts, DEFAULT, 200))
                .containsExactly(4L, 2L);
        // 阈值下调后同一批书多出来的只有 3（4 人），1 人那本要降到 bucket=1 才可能上榜
        assertThat(BookProfileCalculator.rankingCandidates(bookCounts, TINY, 200))
                .containsExactly(4L, 2L, 3L);
        assertThat(BookProfileCalculator.rankingCandidates(bookCounts, new Thresholds(10, 1, 10, 20), 200))
                .containsExactly(4L, 2L, 3L, 1L);
    }

    @Test
    @DisplayName("候选池截断发生在下限之后：不会被小样本挤掉名额")
    void candidatePoolKeepsLargestCells() {
        Map<Long, Long> bookCounts = new LinkedHashMap<>();
        for (long bookId = 1; bookId <= 5; bookId++) {
            bookCounts.put(bookId, bookId);
        }

        // 5 本书里只有 5 人那本过下限，池子给到 1 也还是它；不存在「先截断再过滤」截出空榜
        assertThat(BookProfileCalculator.rankingCandidates(bookCounts, DEFAULT, 1)).containsExactly(5L);
        assertThat(BookProfileCalculator.rankingCandidates(bookCounts, new Thresholds(10, 6, 10, 20), 200))
                .isEmpty();
    }

    @Test
    @DisplayName("同一个格子在单书分布与反查榜上得到同一个隐私结论")
    void bucketFloorIsSharedByBothViews() {
        Map<String, Long> bookRaw = counts("FEMALE", 4, "MALE", 6);
        Map<String, Long> siteRaw = counts("FEMALE", 500, "MALE", 500);
        BookProfileVO.Dimension gender = dimension(BookProfileDimension.GENDER, bookRaw, siteRaw, DEFAULT);

        // 单书侧：4 个女性读者被并进「其他」，不单独成行
        assertThat(gender.getBuckets()).extracting(BookProfileVO.Bucket::getLabel)
                .containsExactly("男", BookProfileDimension.OTHER);
        assertThat(bucketByLabel(gender, BookProfileDimension.OTHER).getCount()).isEqualTo(4L);
        assertThat(bucketByLabel(gender, BookProfileDimension.OTHER).getLift()).isNull();
        // 反查侧：同样这 4 个人也不单独成榜——两处都走 Thresholds.isCountPublishable
        assertThat(BookProfileCalculator.rankingCandidates(Map.of(7L, 4L), DEFAULT, 200)).isEmpty();
        assertThat(DEFAULT.isCountPublishable(4L)).isFalse();
        assertThat(DEFAULT.isCountPublishable(5L)).isTrue();
    }

    // ==================== 辅助 ====================

    private static BookProfileVO.Dimension dimension(BookProfileDimension dim, Map<String, Long> bookRaw,
                                                    Map<String, Long> siteRaw, Thresholds thresholds) {
        return BookProfileCalculator.buildDimension(dim,
                BookProfileCalculator.normalize(dim, bookRaw),
                BookProfileCalculator.normalize(dim, siteRaw),
                thresholds);
    }

    private static BookProfileVO.Bucket bucket(BookProfileVO.Dimension dimension, String value) {
        return dimension.getBuckets().stream()
                .filter(b -> value.equals(b.getValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("桶不存在: " + value));
    }

    private static BookProfileVO.Bucket bucketByLabel(BookProfileVO.Dimension dimension, String label) {
        return dimension.getBuckets().stream()
                .filter(b -> label.equals(b.getLabel()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("桶不存在: " + label));
    }

    private static Map<String, Long> counts(Object... keyValues) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), ((Number) keyValues[i + 1]).longValue());
        }
        return map;
    }
}
