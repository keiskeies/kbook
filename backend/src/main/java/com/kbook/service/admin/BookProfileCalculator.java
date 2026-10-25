package com.kbook.service.admin;

import com.kbook.dto.admin.BookProfileVO;
import com.kbook.service.admin.BookProfileDimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读者画像的纯计算内核：归一化、并桶、lift、显著性、结论文案。
 *
 * 不碰数据库、不读配置，阈值以 Thresholds 入参传入，便于按 4.1 的校准结果整组替换。
 */
public final class BookProfileCalculator {

    /** 95% 置信度 */
    private static final double Z = 1.959963984540054D;

    private BookProfileCalculator() {
    }

    public record Thresholds(int minCohortSize, int minBucketSize, int significantMinCount, int highlightMinCount) {

        /** 第 4 节的人群闸门：整体样本太小就一份分布都不出。单书与反查共用，避免两处口径漂 */
        public boolean isSampleProtected(long cohortSize) {
            return cohortSize < minCohortSize;
        }

        /**
         * 第 4 节规则 5 的格子下限：人数不够 min-bucket-size 就不单独出现。
         *
         * 单书并桶与反查出榜共用这一个判定，否则「分布里被并进『其他』」和「反查榜上单独成行」
         * 会对同一个人给出两种隐私结论。
         */
        public boolean isCountPublishable(long count) {
            return count >= minBucketSize;
        }
    }

    /** 某一维度的归一化结果：buckets 只含白名单 token */
    public record DimCounts(long total, long validSample, long unknown, long dirty, Map<String, Long> buckets) {
    }

    /**
     * 反查榜的候选书：先过隐私下限，再按人数降序截断到 poolSize。
     *
     * 下限放在这里而不是排序分支里，是因为 sortBy 由客户端传，不能让它决定一个小样本格子
     * 要不要上榜（设计文档附录 H.3）。
     */
    public static List<Long> rankingCandidates(Map<Long, Long> bucketCounts, Thresholds thresholds, long poolSize) {
        return bucketCounts.entrySet().stream()
                .filter(entry -> thresholds.isCountPublishable(entry.getValue()))
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(poolSize)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 白名单归一化：先过 {@link BookProfileDimension#toTokens} 做大小写折叠、多值拆分与年龄分桶，
     * 归一化后仍不在白名单里的一律进脏值。实测库里 annual_income / entrepreneurship 存着
     * {{7*7}}，靠这一层挡在展示之外。
     *
     * validSample 统计的是「人」而不是「桶次数」：多值维度一人进多桶，占比之和可以 > 100%，
     * 但分母仍然是该维度的有效人数。
     */
    public static DimCounts normalize(BookProfileDimension dim, Map<String, Long> raw) {
        Map<String, Long> merged = new LinkedHashMap<>();
        long total = 0;
        long unknown = 0;
        long dirty = 0;
        long valid = 0;
        for (Map.Entry<String, Long> entry : raw.entrySet()) {
            long count = entry.getValue() == null ? 0L : entry.getValue();
            total += count;
            if (count <= 0) {
                continue;
            }
            List<String> tokens = dim.toTokens(entry.getKey());
            if (tokens.size() == 1 && BookProfileDimension.UNKNOWN.equals(tokens.get(0))) {
                unknown += count;
                continue;
            }
            if (tokens.size() == 1 && BookProfileDimension.DIRTY.equals(tokens.get(0))) {
                dirty += count;
                continue;
            }
            valid += count;
            for (String token : tokens) {
                merged.merge(token, count, Long::sum);
            }
        }
        return new DimCounts(total, valid, unknown, dirty, merged);
    }

    /** native 查询的 (bucket, cnt) 行并进度数表，bucket 为 NULL 记成「未填写」 */
    public static void mergeRows(List<Object[]> rows, Map<String, Long> target) {
        for (Object[] row : rows) {
            String bucket = row[0] == null ? BookProfileDimension.UNKNOWN : row[0].toString();
            target.merge(bucket, row[1] instanceof Number number ? number.longValue() : 0L, Long::sum);
        }
    }

    /**
     * 看板「全站用户画像」区块的分布：取数走 {@code ProfileReaderSql.siteBucket}、归一化走
     * {@link #normalize}，与读者画像同一份判定；未填写与脏值各占一行而不是被静默丢掉。
     *
     * 这里原本是 `SELECT 列名, COUNT(*) FROM users WHERE 列名 IS NOT NULL GROUP BY 列名`，
     * 三个后果：未审核账号混进画像、脏值被当桶标签渲染（第 5.1 节第 1 条要挡的事）、
     * 未填写的人消失后各桶之和对不上任何人数。列名也不再是入参，那条拼接注入面一并消失。
     */
    public static Map<String, Long> dashboardDistribution(BookProfileDimension dim, List<Object[]> rows) {
        Map<String, Long> raw = new LinkedHashMap<>();
        mergeRows(rows, raw);
        DimCounts counts = normalize(dim, raw);
        Map<String, Long> display = new LinkedHashMap<>(counts.buckets());
        if (counts.unknown() > 0) {
            display.put(BookProfileDimension.UNKNOWN, counts.unknown());
        }
        if (counts.dirty() > 0) {
            display.put(BookProfileDimension.DIRTY, counts.dirty());
        }
        return display;
    }

    /**
     * 组装一个维度：先按 topN 截断，再做 k-匿名并桶，最后算 ratio / siteRatio / lift / significant。
     * 占比分母统一为该维度有效样本，因此各桶占比之和为 1。
     */
    public static BookProfileVO.Dimension buildDimension(BookProfileDimension dim, DimCounts book, DimCounts site,
                                                         Thresholds thresholds) {
        List<String> ordered = orderTokens(dim, book.buckets());
        List<String> kept = new ArrayList<>(ordered);
        List<String> folded = new ArrayList<>();
        if (dim.getTopN() > 0 && ordered.size() > dim.getTopN()) {
            kept = new ArrayList<>(ordered.subList(0, dim.getTopN()));
            folded.addAll(ordered.subList(dim.getTopN(), ordered.size()));
        }
        kept.removeIf(token -> {
            if (!thresholds.isCountPublishable(book.buckets().getOrDefault(token, 0L))) {
                folded.add(token);
                return true;
            }
            return false;
        });

        long validBook = book.validSample();
        long validSite = site.validSample();
        List<BookProfileVO.Bucket> buckets = new ArrayList<>();
        for (String token : kept) {
            long count = book.buckets().getOrDefault(token, 0L);
            double ratio = validBook == 0 ? 0D : (double) count / validBook;
            // 分母是全站样本，但分子是个全站小格时同样不能发布："全站 1/12 属于 X" 就是一条小格比例
            long siteCount = site.buckets().getOrDefault(token, 0L);
            Double siteRatio = (validSite == 0 || !thresholds.isCountPublishable(siteCount)) ? null
                    : round4((double) siteCount / validSite);
            Double lift = (siteRatio == null || siteRatio <= 0) ? null : round2(ratio / siteRatio);
            boolean significant = count >= thresholds.significantMinCount()
                    && siteRatio != null && siteRatio > 0
                    && isSignificant(count, validBook, siteRatio);
            buckets.add(BookProfileVO.Bucket.builder()
                    .value(token)
                    .label(dim.getAllowed().get(token))
                    .count(count)
                    .ratio(round4(ratio))
                    .siteRatio(siteRatio)
                    .lift(lift)
                    .significant(significant)
                    .build());
        }

        long otherCount = folded.stream().mapToLong(token -> book.buckets().getOrDefault(token, 0L)).sum();
        if (otherCount > 0) {
            buckets.add(BookProfileVO.Bucket.builder()
                    .value(null)
                    .label(BookProfileDimension.OTHER)
                    .count(otherCount)
                    .ratio(round4(validBook == 0 ? 0D : (double) otherCount / validBook))
                    .siteRatio(null)
                    .lift(null)
                    .significant(false)
                    .build());
        }

        return BookProfileVO.Dimension.builder()
                .key(dim.name())
                .label(dim.getLabel())
                .coverage(round4(book.total() == 0 ? 0D : (double) validBook / book.total()))
                .unknownCount(book.unknown())
                .dirtyCount(book.dirty())
                .multiValue(dim.isMultiValue())
                .queryable(dim.isQueryable())
                .buckets(buckets)
                .build();
    }

    /** topN 维度按人数降序，其余按白名单声明顺序；count=0 的桶不出现 */
    private static List<String> orderTokens(BookProfileDimension dim, Map<String, Long> buckets) {
        List<String> tokens = new ArrayList<>(buckets.keySet());
        tokens.removeIf(token -> buckets.getOrDefault(token, 0L) <= 0);
        if (dim.getTopN() > 0) {
            tokens.sort(Comparator.comparingLong((String token) -> buckets.getOrDefault(token, 0L)).reversed());
            return tokens;
        }
        List<String> declared = new ArrayList<>(dim.getAllowed().keySet());
        tokens.sort(Comparator.comparingInt(declared::indexOf));
        return tokens;
    }

    /** Wilson 区间：下界高于基线才算显著偏高，上界低于基线才算显著偏低 */
    static boolean isSignificant(long count, long sampleSize, double siteRatio) {
        if (sampleSize <= 0) {
            return false;
        }
        double[] interval = wilson(count, sampleSize);
        return interval[0] > siteRatio || interval[1] < siteRatio;
    }

    static double[] wilson(long count, long sampleSize) {
        double n = sampleSize;
        double p = (double) count / n;
        double z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double centre = p + z2 / (2 * n);
        double margin = Z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n);
        return new double[]{(centre - margin) / denominator, Math.min(1D, (centre + margin) / denominator)};
    }

    /**
     * 结论文案确定性拼接（不调 LLM）：最多 2 条偏高 + 1 条偏低。
     */
    public static List<String> buildHighlights(List<BookProfileVO.Dimension> dimensions, Thresholds thresholds) {
        List<Line> above = new ArrayList<>();
        List<Line> below = new ArrayList<>();
        for (BookProfileVO.Dimension dimension : dimensions) {
            for (BookProfileVO.Bucket bucket : dimension.getBuckets()) {
                if (!bucket.isSignificant() || bucket.getLift() == null || bucket.getValue() == null) {
                    continue;
                }
                if (bucket.getCount() < thresholds.highlightMinCount()) {
                    continue;
                }
                String text = dimension.getLabel() + "·" + bucket.getLabel() + " 读者占比 "
                        + Math.round(bucket.getRatio() * 100) + "%，";
                if (bucket.getLift() >= 1.3D) {
                    above.add(new Line(bucket.getLift(), text + "为全站 " + liftText(bucket.getLift()) + " 倍"));
                } else if (bucket.getLift() <= 0.7D) {
                    below.add(new Line(bucket.getLift(), text + "仅为全站 " + liftText(bucket.getLift()) + " 倍"));
                }
            }
        }
        above.sort(Comparator.comparingDouble(Line::lift).reversed());
        below.sort(Comparator.comparingDouble(Line::lift));
        List<String> highlights = new ArrayList<>();
        above.stream().limit(2).map(Line::text).forEach(highlights::add);
        below.stream().limit(1).map(Line::text).forEach(highlights::add);
        if (highlights.isEmpty()) {
            highlights.add("读者结构与全站接近，无明显人群偏好");
        }
        return highlights;
    }

    private record Line(double lift, String text) {
    }

    /** 与前端 lift 徽标同格式，避免同一数字在文案里显示成 5.0、徽标里显示成 ×5.00 */
    private static String liftText(double lift) {
        return String.format(Locale.ROOT, "%.2f", lift);
    }

    private static double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private static double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
