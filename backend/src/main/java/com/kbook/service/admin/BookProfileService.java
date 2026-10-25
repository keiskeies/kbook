package com.kbook.service.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.admin.BookAudienceFitVO;
import com.kbook.dto.admin.BookProfileVO;
import com.kbook.dto.admin.ProfileBookVO;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单书读者画像服务：取读者池 -> 分维度计数 -> 交给 BookProfileCalculator 归一化与算指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookProfileService {

    /** 单条 IN 列表的 id 上限，超出后分批查再在 Java 侧合并计数 */
    private static final int ID_CHUNK_SIZE = 2000;

    /** 全站基线只有一个缓存键：基线不随窗口变化（附录 H.7） */
    private static final String SITE_KEY = "site";

    /** 反查榜单一次最多返回多少本 */
    private static final int MAX_QUERY_LIMIT = 50;

    /** 按 lift 排序前先在「人数最多的 N 本」里挑，避免给全站书都算一遍总读者数 */
    private static final int CANDIDATE_POOL_SIZE = 200;

    private final EntityManager em;
    private final BookRepository bookRepository;

    @Value("${kbook.admin.book-profile.min-cohort-size:10}")
    private int minCohortSize;
    @Value("${kbook.admin.book-profile.min-bucket-size:5}")
    private int minBucketSize;
    @Value("${kbook.admin.book-profile.significant-min-count:10}")
    private int significantMinCount;
    @Value("${kbook.admin.book-profile.highlight-min-count:20}")
    private int highlightMinCount;
    /** 两份缓存的寿命（分钟）。§7.4 定的 5 / 10 是默认值，做成配置是为了附录 Q 的复现能把窗口压到 1 分钟 */
    @Value("${kbook.admin.book-profile.profile-cache-minutes:5}")
    private int profileCacheMinutes;
    @Value("${kbook.admin.book-profile.baseline-cache-minutes:10}")
    private int baselineCacheMinutes;

    /**
     * 缓存不能在字段初始化时建：{@code @Value} 的注入发生在构造之后，就地建会读到 0 分钟，
     * 于是每次请求都算一次——表现是"接口变慢了"，而不是任何报错。
     */
    private Cache<String, BookProfileVO> profileCache;
    private Cache<String, BaselineSnapshot> siteCache;

    /**
     * 基线代号：每次基线重算 +1，单书缓存键的第一段就是它。
     * 旧代号的条目从此不可能命中——这就是附录 Q 的方案 A（分子分母同龄）。
     */
    private final AtomicLong baselineGeneration = new AtomicLong(1);

    @PostConstruct
    void buildCaches() {
        profileCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(profileCacheMinutes))
                .build();
        siteCache = Caffeine.newBuilder()
                .maximumSize(4)
                .expireAfterWrite(Duration.ofMinutes(baselineCacheMinutes))
                .build();
    }

    /** 阈值全部来自配置（4.1 校准后要改的是配置，不是代码），三条取数路径共用同一份 */
    /** 快照作业判断候选书用：阈值只在 thresholds() 读一次配置，不在别处再读一份 */
    boolean isCohortProtected(long readers) {
        return thresholds().isSampleProtected(readers);
    }

    private BookProfileCalculator.Thresholds thresholds() {
        return new BookProfileCalculator.Thresholds(minCohortSize, minBucketSize, significantMinCount, highlightMinCount);
    }

    /** 读者口径：三个并列视角，彼此没有包含关系（实测 READ 并不小于 TOUCH） */
    public enum Basis {
        READ, TOUCH, DEEP;

        static Basis parse(String value) {
            if (value == null || value.isBlank()) {
                return READ;
            }
            for (Basis basis : values()) {
                if (basis.name().equalsIgnoreCase(value.trim())) {
                    return basis;
                }
            }
            throw new BusinessException("读者口径不合法，可选值：READ / TOUCH / DEEP");
        }

        String note() {
            return switch (this) {
                case READ -> "口径：翻开的读者（有阅读进度，按最近阅读时间归属）；画像字段取当前值";
                case TOUCH -> "口径：触达的读者（书架收藏 ∪ 阅读记录，含想读未读）；画像字段取当前值";
                case DEEP -> "口径：深度互动的读者（书评 / 笔记 / 与本书的 AI 对话）；画像字段取当前值";
            };
        }
    }

    public BookProfileVO getBookProfile(Long bookId, int days, LocalDate start, LocalDate end, String basisParam) {
        if (bookId == null || bookId <= 0) {
            throw new BusinessException("缺少参数 bookId");
        }
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException("图书不存在"));
        Basis basis = Basis.parse(basisParam);
        Window window = resolveRange(days, start, end);
        // 先取基线快照（缓存读），把代号写进键；基线一旦重算，旧条目自然 miss
        BaselineSnapshot site = siteBaseline();
        String cacheKey = profileCacheKey(site.generation(), bookId, basis.name(), window.key());

        BookProfileVO cached = profileCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        BookProfileCalculator.Thresholds thresholds = thresholds();
        List<Long> readers = readerPool(basis, bookId, window);

        BookProfileVO.BookProfileVOBuilder vo = BookProfileVO.builder()
                .bookId(bookId)
                .bookTitle(book.getTitle())
                .basis(basis.name())
                .rangeDays(window.allTime() ? 0 : (int) (ChronoUnit.DAYS.between(window.startDate(), window.endDate()) + 1))
                .rangeStart(window.allTime() ? null : window.startDate().toString())
                .rangeEnd(window.allTime() ? null : window.endDate().toString())
                .readers(readers.size())
                .minCohortSize(thresholds.minCohortSize())
                .basisNote(basis.note())
                .siteReaders(site.value().readers());

        if (thresholds.isSampleProtected(readers.size())) {
            BookProfileVO protectedVo = vo.sampleProtected(true)
                    .profiledUsers(0)
                    .dimensions(List.of())
                    .highlights(List.of())
                    .build();
            profileCache.put(cacheKey, protectedVo);
            return protectedVo;
        }

        List<BookProfileVO.Dimension> dimensions = new ArrayList<>();
        for (BookProfileDimension dim : BookProfileDimension.values()) {
            BookProfileCalculator.DimCounts bookCounts = BookProfileCalculator.normalize(dim, countBuckets(dim, readers));
            dimensions.add(BookProfileCalculator.buildDimension(dim, bookCounts, site.value().counts().get(dim), thresholds));
        }

        BookProfileVO result = vo.sampleProtected(false)
                .profiledUsers(countProfiledUsers(readers))
                .dimensions(dimensions)
                .highlights(BookProfileCalculator.buildHighlights(dimensions, thresholds))
                .build();
        profileCache.put(cacheKey, result);
        return result;
    }

    // ==================== 反查榜单 ====================

    /**
     * 画像反查：给定「维度 + 分桶」，出这个人群读得最多（或最集中）的书。
     *
     * 分子是「该桶读者 ∩ 该书读者」，分母是该书总读者，lift 再除以全站该桶占比——与单书画像
     * 同一套 ratio/siteRatio 口径，两处数字能互相对上。
     *
     * 两道闸门都与排序无关：人群本身 < min-cohort-size 直接出空榜，段内单书读者数
     * < min-bucket-size 不进榜（§4 规则 5）。
     */
    public List<ProfileBookVO> getProfileBooks(String dimParam, String valueParam, int days, LocalDate start,
                                               LocalDate end, String basisParam, int limit, String sortBy) {
        BookProfileDimension dim = BookProfileDimension.of(dimParam);
        if (dim == null) {
            throw new BusinessException("画像维度不合法，可选值见 /book-profile 返回的 dimensions[].key");
        }
        if (!dim.isQueryable()) {
            throw new BusinessException(dim.getLabel() + "不支持反查榜单：多值维度或有效样本过薄");
        }
        String token = valueParam == null ? "" : valueParam.trim().toUpperCase(Locale.ROOT);
        if (!dim.getAllowed().containsKey(token)) {
            // 传展示名（"女"）或脏值：空榜而不是 500，反查一律用 DB 存储值
            return List.of();
        }
        Basis basis = Basis.parse(basisParam);
        Window window = resolveRange(days, start, end);
        int size = Math.min(Math.max(limit, 1), MAX_QUERY_LIMIT);
        boolean byLift = !"READERS".equalsIgnoreCase(trimOrEmpty(sortBy));

        BaselineSnapshot site = siteBaseline();
        BookProfileCalculator.Thresholds thresholds = thresholds();
        BookProfileCalculator.DimCounts siteDim = site.value().counts().get(dim);
        long bucketUsers = siteDim.buckets().getOrDefault(token, 0L);
        if (thresholds.isSampleProtected(bucketUsers)) {
            // 该人群本身不足 min-cohort，出榜等价于点名
            return List.of();
        }
        Double siteRatio = siteDim.validSample() == 0 ? null
                : round4((double) bucketUsers / siteDim.validSample());

        List<Long> bucketIds = bucketUserIds(dim, token);
        Map<Long, Long> bucketCounts = bucketCountByBook(basis, window, bucketIds);
        List<Long> candidates = BookProfileCalculator.rankingCandidates(bucketCounts, thresholds, CANDIDATE_POOL_SIZE);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> totals = totalReadersByBook(basis, window, candidates);
        Map<Long, Book> books = new LinkedHashMap<>();
        for (Book book : bookRepository.findAllById(candidates)) {
            books.put(book.getId(), book);
        }

        List<ProfileBookVO> ranked = new ArrayList<>();
        for (Long bookId : candidates) {
            Book book = books.get(bookId);
            long count = bucketCounts.getOrDefault(bookId, 0L);
            long readers = totals.getOrDefault(bookId, 0L);
            if (book == null || readers <= 0) {
                continue;
            }
            double ratio = (double) count / readers;
            Double lift = (siteRatio == null || siteRatio <= 0) ? null : round2(ratio / siteRatio);
            ranked.add(ProfileBookVO.builder()
                    .bookId(bookId)
                    .title(book.getTitle())
                    .author(book.getAuthor())
                    .count(count)
                    .readers(readers)
                    .ratio(round4(ratio))
                    .siteRatio(siteRatio)
                    .lift(lift)
                    .build());
        }
        if (byLift) {
            ranked.sort(Comparator.comparingDouble((ProfileBookVO vo) -> vo.getLift() == null ? 0D : vo.getLift())
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ProfileBookVO::getCount).reversed()));
        } else {
            ranked.sort(Comparator.comparingLong(ProfileBookVO::getCount).reversed()
                    .thenComparing(Comparator.comparingDouble(ProfileBookVO::getRatio).reversed()));
        }
        return ranked.stream().limit(size).toList();
    }

    /**
     * 全站基线的维度列表：给「人群选书」页当选择器用。
     *
     * 分桶的可选项必须与反查用的是同一套归一化口径，所以这里直接复用 siteBaseline() 的归一化结果，
     * 前端不再自己维护一份维度表（否则两处会漂）。book 侧与 site 侧传同一份计数，
     * 因此 lift 恒为 1、也不会有显著标记——选择器只取 key / label / buckets 的 value 与 label。
     */
    public List<BookProfileVO.Dimension> getSiteDimensions() {
        BaselineSnapshot site = siteBaseline();
        BookProfileCalculator.Thresholds thresholds = thresholds();
        List<BookProfileVO.Dimension> dimensions = new ArrayList<>();
        for (BookProfileDimension dim : BookProfileDimension.values()) {
            BookProfileCalculator.DimCounts counts = site.value().counts().get(dim);
            BookProfileVO.Dimension built = BookProfileCalculator.buildDimension(dim, counts, counts, thresholds);
            // 选择器只列「点了能出结果」的维度：唯一可发布桶是「其他」（value=null）时点了只会得空榜（H.5 ②）
            boolean hasPublishableValue = built.getBuckets().stream()
                    .anyMatch(b -> b.getValue() != null && thresholds.isCountPublishable(b.getCount()));
            dimensions.add(BookProfileVO.Dimension.builder()
                    .key(built.getKey())
                    .label(built.getLabel())
                    .coverage(built.getCoverage())
                    .unknownCount(built.getUnknownCount())
                    .dirtyCount(built.getDirtyCount())
                    .multiValue(built.isMultiValue())
                    .queryable(built.isQueryable() && hasPublishableValue)
                    .buckets(built.getBuckets())
                    .build());
        }
        return dimensions;
    }

    /**
     * 命中某分桶的读者 id 集合。归一化只在 Java 侧有一份实现，所以这里把 APPROVED 用户的原始值
     * 取回来在内存里判定，避免同一套规则在 SQL 里再写一遍而两边漂移。
     */
    @SuppressWarnings("unchecked")
    private List<Long> bucketUserIds(BookProfileDimension dim, String token) {
        List<Object[]> rows = em.createNativeQuery(ProfileReaderSql.bucketUserIds(dim.getValueSql())).getResultList();
        List<Long> ids = new ArrayList<>();
        for (Object[] row : rows) {
            if (!(row[0] instanceof Number number)) {
                continue;
            }
            if (dim.toTokens(row[1]).contains(token)) {
                ids.add(number.longValue());
            }
        }
        return ids;
    }

    /** 该桶读者在各书上的去重人数（榜单分子） */
    private Map<Long, Long> bucketCountByBook(Basis basis, Window window, List<Long> userIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (List<Long> chunk : partition(userIds)) {
            String sql = ProfileReaderSql.bookUserAggregate(basis, "user_id IN (:ids)", window.condition("x.ts"));
            Query query = em.createNativeQuery(sql).setParameter("ids", chunk);
            window.bind(query);
            mergeBookRows(query.getResultList(), result);
        }
        return result;
    }

    /** 指定这些书的总读者数（榜单分母） */
    private Map<Long, Long> totalReadersByBook(Basis basis, Window window, List<Long> bookIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (List<Long> chunk : partition(bookIds)) {
            String sql = ProfileReaderSql.bookUserAggregate(basis, "book_id IN (:ids)", window.condition("x.ts"));
            Query query = em.createNativeQuery(sql).setParameter("ids", chunk);
            window.bind(query);
            mergeBookRows(query.getResultList(), result);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void mergeBookRows(List<Object[]> rows, Map<Long, Long> target) {
        for (Object[] row : rows) {
            if (row[0] instanceof Number bookId && row[1] instanceof Number count) {
                target.merge(bookId.longValue(), count.longValue(), Long::sum);
            }
        }
    }

    private String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    // ==================== 取数 ====================

    /**
     * 读者池：三个口径各自独立取数，按 user_id 去重，且只算 status=APPROVED 的账号。
     */
    @SuppressWarnings("unchecked")
    private List<Long> readerPool(Basis basis, Long bookId, Window window) {
        String sql = ProfileReaderSql.readerPool(basis, window.condition("x.ts"));
        Query query = em.createNativeQuery(sql);
        query.setParameter("bookId", bookId);
        window.bind(query);
        List<Object> rows = query.getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return ids;
    }

    /**
     * 某维度的原始取值分布。返回的是白名单归一化之前的 token -> 人数，归一化统一在 Calculator 里做，
     * 保证「本书」与「全站基线」走同一套口径。
     */
    private Map<String, Long> countBuckets(BookProfileDimension dim, List<Long> readerIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (List<Long> chunk : partition(readerIds)) {
            String sql = ProfileReaderSql.countBuckets(dim.getValueSql());
            BookProfileCalculator.mergeRows(em.createNativeQuery(sql).setParameter("ids", chunk).getResultList(), result);
        }
        return result;
    }

    private BaselineSnapshot siteBaseline() {
        return siteCache.get(SITE_KEY, key -> computeBaseline());
    }

    private BaselineSnapshot computeBaseline() {
        long siteReaders = numberValue(em.createNativeQuery(ProfileReaderSql.siteReaders()).getSingleResult());
        Map<BookProfileDimension, BookProfileCalculator.DimCounts> counts = new LinkedHashMap<>();
        for (BookProfileDimension dim : BookProfileDimension.values()) {
            Map<String, Long> raw = new LinkedHashMap<>();
            BookProfileCalculator.mergeRows(
                    em.createNativeQuery(ProfileReaderSql.siteBucket(dim.getValueSql())).getResultList(), raw);
            counts.put(dim, BookProfileCalculator.normalize(dim, raw));
        }
        return new BaselineSnapshot(new SiteBaseline(siteReaders, counts), baselineGeneration.getAndIncrement());
    }

    /** 至少填了一个画像维度的读者数 */
    private long countProfiledUsers(List<Long> readerIds) {
        long total = 0;
        for (List<Long> chunk : partition(readerIds)) {
            total += numberValue(em.createNativeQuery(ProfileReaderSql.profiledUsers())
                    .setParameter("ids", chunk).getSingleResult());
        }
        return total;
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private List<List<Long>> partition(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        if (ids.isEmpty()) {
            return chunks;
        }
        for (int i = 0; i < ids.size(); i += ID_CHUNK_SIZE) {
            chunks.add(ids.subList(i, Math.min(i + ID_CHUNK_SIZE, ids.size())));
        }
        return chunks;
    }

    // ==================== P3：受众吻合度（单书版） ====================

    /**
     * 书侧声称受众（{@code target_reader_tags} 经 {@link TargetAudienceRules} 归到画像桶）与实际读者分布的
     * 吻合度。与单书画像共用读者池、维度取值 SQL 与隐私阈值：同一 bookId 在两处的 readers 必然相同，
     * 门槛也不存在第二套（附录 Z）。
     */
    public BookAudienceFitVO getAudienceFit(Long bookId, int days, LocalDate start, LocalDate end, String basisParam) {
        if (bookId == null || bookId <= 0) {
            throw new BusinessException("缺少参数 bookId");
        }
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BusinessException("图书不存在"));
        Basis basis = Basis.parse(basisParam);
        Window window = resolveRange(days, start, end);
        BaselineSnapshot site = siteBaseline();
        BookProfileCalculator.Thresholds thresholds = thresholds();
        List<Long> readers = readerPool(basis, bookId, window);

        List<String> tags = splitTags(book.getTargetReaderTags());
        int interest = 0;
        int generic = 0;
        int noBucket = 0;
        int unrecognized = 0;
        for (String tag : tags) {
            switch (TargetAudienceRules.classify(tag).kind()) {
                case INTEREST -> interest++;
                case GENERIC -> generic++;
                case NO_BUCKET -> noBucket++;
                case UNRECOGNIZED -> unrecognized++;
                default -> { /* AUDIENCE 参与计算，不在这四项里另计 */ }
            }
        }
        List<TargetAudienceRules.Bucket> claimed = TargetAudienceRules.bucketsOf(tags);

        BookAudienceFitVO.BookAudienceFitVOBuilder vo = BookAudienceFitVO.builder()
                .bookId(bookId)
                .bookTitle(book.getTitle())
                .basis(basis.name())
                .rangeDays(window.allTime() ? 0 : (int) (ChronoUnit.DAYS.between(window.startDate(), window.endDate()) + 1))
                .rangeStart(window.allTime() ? null : window.startDate().toString())
                .rangeEnd(window.allTime() ? null : window.endDate().toString())
                .readers(readers.size())
                .minCohortSize(thresholds.minCohortSize())
                .basisNote(basis.note())
                .claimedTags(tags.size())
                .claimedAudience(claimed.size())
                .interestTags(interest)
                .genericTags(generic)
                .noBucketTags(noBucket)
                .unrecognizedTags(unrecognized);

        if (thresholds.isSampleProtected(readers.size())) {
            return vo.sampleProtected(true).fits(List.of()).build();
        }

        if (claimed.isEmpty()) {
            return vo.sampleProtected(false).fits(List.of()).build();
        }
        // 只算声称桶涉及的维度：照抄单书画像那样把 15 个维度全查一遍，多数书会白花十几条 SQL
        Map<BookProfileDimension, BookProfileCalculator.DimCounts> bookCounts = new EnumMap<>(BookProfileDimension.class);
        for (TargetAudienceRules.Bucket bucket : claimed) {
            BookProfileDimension dim = bucket.dim();
            if (!bookCounts.containsKey(dim)) {
                bookCounts.put(dim, BookProfileCalculator.normalize(dim, countBuckets(dim, readers)));
            }
        }
        AudienceFitCalculator.Result result = AudienceFitCalculator.compute(
                claimed, bookCounts, site.value().counts(), readers.size(), thresholds);

        return vo.sampleProtected(false)
                .fits(result.fits().stream().map(BookProfileService::toFitVo).toList())
                .build();
    }

    private static BookAudienceFitVO.Fit toFitVo(AudienceFitCalculator.Fit fit) {
        return BookAudienceFitVO.Fit.builder()
                .dimKey(fit.dimKey())
                .dimLabel(fit.dimLabel())
                .token(fit.token())
                .tokenLabel(fit.tokenLabel())
                .count(fit.count())
                .ratio(fit.ratio())
                .siteRatio(fit.siteRatio())
                .lift(fit.lift())
                .significant(fit.significant())
                .verdict(fit.verdict().name())
                .topTokenLabel(fit.topTokenLabel())
                .topCount(fit.topCount())
                .topRatio(fit.topRatio())
                .build();
    }

    /** 书侧标签分隔符没有统一约定，保守地按常见几种拆开并去空 */
    private static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String part : raw.split("[,，、;；|]")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    // ==================== 时间区间 ====================

    /** 区间语义与报错文案与主看板共用 {@link AdminRangeRules}，不允许出现第二份判定 */
    private Window resolveRange(int days, LocalDate start, LocalDate end) {
        AdminRangeRules.Range range = AdminRangeRules.resolve(start, end, days, LocalDate.now());
        return new Window(range.startDate(), range.endDate());
    }

    private record Window(LocalDate startDate, LocalDate endDate) {
        boolean allTime() {
            return startDate == null;
        }

        String key() {
            return allTime() ? "all" : startDate + "~" + endDate;
        }

        LocalDateTime start() {
            return startDate.atStartOfDay();
        }

        /** 闭区间最后一天 -> 次日零点，配合 < :end 使用 */
        LocalDateTime endExclusive() {
            return endDate.plusDays(1).atStartOfDay();
        }

        String condition(String column) {
            return allTime() ? "" : " AND " + column + " >= :start AND " + column + " < :end";
        }

        void bind(Query query) {
            if (!allTime()) {
                query.setParameter("start", start());
                query.setParameter("end", endExclusive());
            }
        }
    }

    private record SiteBaseline(long readers, Map<BookProfileDimension, BookProfileCalculator.DimCounts> counts) {
    }

    /** 基线值 + 它的代号，必须成对取用；分开取就会退回「新分子 / 旧分母」 */
    private record BaselineSnapshot(SiteBaseline value, long generation) {
    }

    /**
     * 单书缓存键：第一段是基线代号。基线一刷新，所有旧代号的条目直接 miss。
     * 抽成 static 是为了能在单测里直接钉住「代号不同则键不同」这条唯一约束。
     */
    static String profileCacheKey(long baselineGeneration, long bookId, String basis, String windowKey) {
        return baselineGeneration + "|" + bookId + "|" + basis + "|" + windowKey;
    }
}
