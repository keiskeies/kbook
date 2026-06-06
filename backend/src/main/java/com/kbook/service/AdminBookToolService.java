package com.kbook.service;

import com.kbook.dto.ChartRequestDTO;
import com.kbook.entity.Book;
import com.kbook.enums.chart.CalcType;
import com.kbook.enums.chart.ColumnType;
import com.kbook.enums.chart.TimeDeltaEnum;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * AI 图书管理员专用工具 — 动态增删改查 + 动态统计
 * <p>
 * 核心设计：所有操作都支持动态条件，AI 只需掌握 3 个核心工具：
 * - queryBooks: 动态查询（条件 + 排序 + 分页）
 * - stats: 动态统计（按字段分组 + 时间范围 + 过滤条件）
 * - updateBooks / deleteBooks: 动态修改/删除
 * <p>
 * 条件语法：field|op|value,field|op|value
 * - op: EQ(=), NE(!=), GT(>), GE(>=), LT(<), LE(<=), LIKE(包含), LL(左匹配), LR(右匹配), IN(在), BT(区间), IS_NULL, NOT_NULL
 */
@Slf4j
@Service
public class AdminBookToolService {

    private final BookService bookService;
    private final BookScanService bookScanService;
    private final DynamicQueryService dynamicQueryService;
    private final ChartEntityToolSupport chartEntityToolSupport;

    public AdminBookToolService(
            BookService bookService,
            @Lazy BookScanService bookScanService,
            DynamicQueryService dynamicQueryService,
            ChartEntityToolSupport chartEntityToolSupport
    ) {
        this.bookService = bookService;
        this.bookScanService = bookScanService;
        this.dynamicQueryService = dynamicQueryService;
        this.chartEntityToolSupport = chartEntityToolSupport;
    }

    // ==================== 核心：动态查询 ====================

    @Tool("""
            查询工具：按条件搜索图书，返回图书列表。conditions空字符串=无筛选。
            条件: field|op|value，多条件逗号分隔
            操作符: EQ(=) NE(!=) GT(>) GE(>=) LT(<) LE(<=) LIKE(包含) LL(左匹配) LR(右匹配) IN(在) BT(区间~) IS_NULL NOT_NULL
            排序: field,asc 或 field,desc（多个用;分隔）
            """)
    public String queryBooks(
            @P("查询条件，格式 field|op|value，多个条件逗号分隔。空字符串表示无筛选") String conditions,
            @P("排序规则，格式 field,asc 或 field,desc，多个用;分隔") String sort,
            @P("页码，从1开始") Integer page,
            @P("每页数量，默认20，最大100") Integer limit
    ) {
        log.debug("[Admin Tool] queryBooks: conditions={}, sort={}, page={}, limit={}", conditions, sort, page, limit);
        try {
            int p = page != null && page > 0 ? page : 1;
            int l = limit != null && limit > 0 ? Math.min(limit, 100) : 20;

            var books = dynamicQueryService.queryBooks(conditions, sort, p, l);
            if (books.isEmpty()) {
                return "没有找到符合条件的图书。";
            }

            long total = dynamicQueryService.countBooks(conditions);
            String title = String.format("查询结果（共 %d 本，显示第 %d-%d 本）",
                    total, (p - 1) * l + 1, Math.min(p * l, total));

            StringBuilder sb = new StringBuilder();
            sb.append(dynamicQueryService.formatBookList(books, title));
            if (total > l) {
                sb.append("\n（使用 page=").append(p + 1).append(" 查看更多）");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[Admin Tool] queryBooks error", e);
            return "查询失败：" + e.getMessage();
        }
    }

    // ==================== 核心：动态统计 ====================

    /** 时间类型字段集合 */
    private static final Set<String> TIME_FIELDS = Set.of("createdat", "updatedat");

    @Tool("""
            统计工具：按指定字段分组计数，直接返回统计结果，无需再调用其他工具。
            field=普通字段(如author)→返回每个值各有多少本书（即排行榜）
            field=时间字段(createdAt/updatedAt)→返回按天/月/年聚合的入库趋势
            timeRange: 本周/本月/本年/近7天/近30天/近90天/近6个月/全部（仅时间字段有效，可null）
            conditions: 额外过滤（可null），语法同queryBooks
            limit: 返回数量上限（可null），如TOP20则传20
            """)
    public String stats(
            @P("统计字段: author, format, rating, formatTags, conceptTags, targetReaderTags, createdAt, updatedAt 等") String field,
            @P("时间范围(仅时间字段有效): 本周, 本月, 本年, 近7天, 近30天, 近90天, 近6个月, 全部") String timeRange,
            @P("过滤条件(可选)，格式同 queryBooks，如 format|EQ|EPUB") String conditions,
            @P("返回数量上限(可选)，如TOP20传20，默认30") Integer limit
    ) {
        log.debug("[Admin Tool] stats: field={}, timeRange={}, conditions={}", field, timeRange, conditions);
        try {
            ChartRequestDTO.ChartRequestDTOBuilder requestBuilder = ChartRequestDTO.builder()
                    .calcType(CalcType.COUNT);

            String title;
            boolean isTimeField = TIME_FIELDS.contains(field.toLowerCase());

            if (isTimeField) {
                TimeRange range = parseTimeRange(timeRange);
                TimeDeltaEnum delta = resolveTimeDelta(range);
                String fieldLabel = "createdAt".equalsIgnoreCase(field) ? "入库" : "更新";
                requestBuilder.field(field).fieldType(ColumnType.TIME)
                        .fieldDelta(delta).start(range.start).end(range.end);
                title = String.format("图书%s趋势（%s，%s）",
                        fieldLabel, timeRange != null ? timeRange : "全部", delta.getLabel());
            } else {
                requestBuilder.field(field).fieldType(ColumnType.FIELD);
                title = "图书" + getFieldLabel(field) + "统计";
            }

            // 解析额外过滤条件（收集后一次性设置，避免 Builder setter 覆盖）
            java.util.List<com.kbook.dto.ConditionDTO> allConditions = new java.util.ArrayList<>();
            if (conditions != null && !conditions.isBlank()) {
                var condList = dynamicQueryService.parseConditions(conditions);
                for (var cond : condList) {
                    allConditions.add(new com.kbook.dto.ConditionDTO(cond.getColumn(), cond.getOp(),
                            cond.getValues().toArray()));
                }
                title += "（筛选：" + conditions + "）";
            }
            requestBuilder.conditions(allConditions);

            ChartRequestDTO request = requestBuilder.build();

            // 非时间字段：SQL 层面已 ORDER BY count DESC，直接传 limit 到 SQL
            int maxResults = 0;
            if (!TIME_FIELDS.contains(field.toLowerCase())) {
                maxResults = limit != null && limit > 0 ? limit : 30;
            }

            Map<String, Map<String, Double>> data = chartEntityToolSupport.getEntityChartOptions(request, maxResults);

            return chartEntityToolSupport.formatChartResult(data, title);
        } catch (Exception e) {
            log.error("[Admin Tool] stats error", e);
            return "统计失败：" + e.getMessage();
        }
    }

    // ==================== 核心：动态更新 ====================

    @Tool("动态批量更新图书。更新语法: field=value,field=value。更新前必须先 queryBooks 确认范围。")
    public String updateBooks(
            @P("查询条件，格式 field|op|value") String conditions,
            @P("更新内容，格式 field=value，多个逗号分隔。可更新: title,author,description,formatTags,conceptTags,readerNeedTags,targetReaderTags,coverUrl,rating") String updates
    ) {
        log.info("[Admin Tool] updateBooks: conditions={}, updates={}", conditions, updates);
        try {
            if (conditions == null || conditions.isBlank()) return "必须指定查询条件，防止全表更新。";
            if (updates == null || updates.isBlank()) return "必须指定要更新的字段和值。";
            int affected = dynamicQueryService.updateBooks(conditions, updates);
            return String.format("已更新 %d 本图书。\n更新的字段：%s", affected, updates);
        } catch (Exception e) {
            log.error("[Admin Tool] updateBooks error", e);
            return "更新失败：" + e.getMessage();
        }
    }

    // ==================== 核心：动态删除 ====================

    @Tool("动态批量删除图书。先预览影响范围，确认后用 confirmDelete 执行。")
    public String deleteBooks(
            @P("查询条件，格式 field|op|value，必须精确到可唯一识别要删除的图书") String conditions
    ) {
        log.info("[Admin Tool] deleteBooks: conditions={}", conditions);
        try {
            if (conditions == null || conditions.isBlank()) return "必须指定查询条件，防止误删全表。";
            var books = dynamicQueryService.queryBooks(conditions, null, 1, 5);
            if (books.isEmpty()) return "没有找到符合条件的图书，无需删除。";
            if (books.size() == 5) {
                long total = dynamicQueryService.countBooks(conditions);
                return String.format("警告：将删除 %d 本图书！\n预览前5本：\n%s\n如果确认删除，请说'确认删除'。",
                        total, dynamicQueryService.formatBookList(books, null));
            }
            return String.format("将删除 %d 本图书：\n%s\n如果确认，请说'确认删除'。",
                    books.size(), dynamicQueryService.formatBookList(books, null));
        } catch (Exception e) {
            log.error("[Admin Tool] deleteBooks error", e);
            return "删除预览失败：" + e.getMessage();
        }
    }

    @Tool("确认执行删除。在 deleteBooks 预览后，管理员确认时调用。")
    public String confirmDelete(
            @P("查询条件，必须与 deleteBooks 时一致") String conditions
    ) {
        log.info("[Admin Tool] confirmDelete: conditions={}", conditions);
        try {
            int deleted = dynamicQueryService.deleteBooks(conditions);
            return String.format("已删除 %d 本图书及相关数据。", deleted);
        } catch (Exception e) {
            log.error("[Admin Tool] confirmDelete error", e);
            return "删除执行失败：" + e.getMessage();
        }
    }

    // ==================== 单条操作 ====================

    @Tool("根据 ID 获取图书详情。")
    public String getBookById(@P("图书ID") Long bookId) {
        log.debug("[Admin Tool] getBookById: bookId={}", bookId);
        try {
            Book book = bookService.getBookById(bookId);
            return dynamicQueryService.formatBookDetail(book);
        } catch (Exception e) {
            log.error("[Admin Tool] getBookById error", e);
            return "获取图书详情失败：" + e.getMessage();
        }
    }

    @Tool("合并同名不同格式的书籍。以 EPUB 为主，合并其他格式后删除。")
    public String mergeBooks(@P("书名关键词") String title) {
        log.info("[Admin Tool] mergeBooks: title={}", title);
        try {
            return bookService.mergeBooksByTitle(title);
        } catch (Exception e) {
            log.error("[Admin Tool] mergeBooks error", e);
            return "合并失败：" + e.getMessage();
        }
    }

    @Tool("获取扫描任务进度和状态。")
    public String getScanStatus() {
        log.debug("[Admin Tool] getScanStatus");
        try {
            Map<String, Object> progress = bookScanService.getScanProgress();
            boolean scanning = (Boolean) progress.get("scanning");
            if (scanning) {
                return String.format("""
                        扫描进行中：进度 %d/%d
                        ├─ 已入库: %d  ├─ 已更新: %d
                        ├─ 跳过: %d    └─ 失败: %d
                        当前: %s""",
                        progress.get("current"), progress.get("total"),
                        progress.get("added"), progress.get("updated"),
                        progress.get("skipped"), progress.get("failed"),
                        progress.get("currentFile"));
            }
            return String.format("""
                    扫描未在进行。最近结果：
                    ├─ 已入库: %d  ├─ 已更新: %d
                    ├─ 跳过: %d    └─ 失败: %d""",
                    progress.get("added"), progress.get("updated"),
                    progress.get("skipped"), progress.get("failed"));
        } catch (Exception e) {
            log.error("[Admin Tool] getScanStatus error", e);
            return "获取扫描状态失败：" + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private String getFieldLabel(String field) {
        return switch (field.toLowerCase()) {
            case "author" -> "作者排行";
            case "format" -> "格式分布";
            case "rating" -> "评分分布";
            case "formattags" -> "格式标签分布";
            case "concepttags" -> "概念标签分布";
            case "targetreadertags" -> "目标读者分布";
            case "readerneedtags" -> "阅读需求分布";
            default -> "按" + field + "统计";
        };
    }

    private TimeDeltaEnum resolveTimeDelta(TimeRange range) {
        if (range.start == null) return TimeDeltaEnum.ALL_MONTHS;
        long days = java.time.Duration.between(range.start, range.end != null ? range.end : LocalDateTime.now()).toDays();
        if (days <= 90) return TimeDeltaEnum.ALL_DAYS;
        if (days <= 400) return TimeDeltaEnum.ALL_MONTHS;
        return TimeDeltaEnum.YEAR;
    }

    private TimeRange parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank() || "全部".equals(timeRange)) {
            return new TimeRange(null, null);
        }
        return switch (timeRange.trim()) {
            case "本周" -> new TimeRange(ChartEntityToolSupport.getWeekStart(), ChartEntityToolSupport.getWeekEnd());
            case "本月" -> new TimeRange(ChartEntityToolSupport.getMonthStart(), ChartEntityToolSupport.getMonthEnd());
            case "本年" -> new TimeRange(ChartEntityToolSupport.getYearStart(), ChartEntityToolSupport.getYearEnd());
            case "近7天" -> new TimeRange(ChartEntityToolSupport.getRecentDaysStart(7), null);
            case "近30天" -> new TimeRange(ChartEntityToolSupport.getRecentDaysStart(30), null);
            case "近90天" -> new TimeRange(ChartEntityToolSupport.getRecentDaysStart(90), null);
            case "近6个月" -> new TimeRange(ChartEntityToolSupport.getRecentMonthsStart(6), null);
            default -> {
                if (timeRange.contains("~")) {
                    String[] parts = timeRange.split("~");
                    try {
                        yield new TimeRange(
                                LocalDateTime.parse(parts[0].trim() + "T00:00:00"),
                                LocalDateTime.parse(parts[1].trim() + "T23:59:59"));
                    } catch (Exception e) {
                        yield new TimeRange(null, null);
                    }
                }
                yield new TimeRange(null, null);
            }
        };
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {}
}
