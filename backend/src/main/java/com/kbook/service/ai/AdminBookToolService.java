package com.kbook.service.ai;
import com.kbook.service.book.BookScanService;
import com.kbook.service.book.BookService;

import com.kbook.common.enums.ConditionEnum;
import com.kbook.common.util.CommonUtils;
import com.kbook.dto.stats.ChartRequestDTO;
import com.kbook.dto.stats.ConditionDTO;
import com.kbook.entity.Book;
import com.kbook.common.enums.chart.CalcType;
import com.kbook.common.enums.chart.ColumnType;
import com.kbook.common.enums.chart.TimeDeltaEnum;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
 * <p>
 * 通过 BookService（继承 AbstractServiceImpl）使用泛型基类的统一 CRUD + 图表统计能力。
 */
@Slf4j
@Service
public class AdminBookToolService {

    private final BookService bookService;
    private final BookScanService bookScanService;

    public AdminBookToolService(
            BookService bookService,
            @Lazy BookScanService bookScanService
    ) {
        this.bookService = bookService;
        this.bookScanService = bookScanService;
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

            var condList = parseConditions(conditions);
            var sortInfo = parseSort(sort);

            var books = bookService.findList(condList, sortInfo.ascList(), sortInfo.descList(), p, l);
            if (books.isEmpty()) {
                return "没有找到符合条件的图书。";
            }

            long total = bookService.getCount(condList);
            String title = String.format("查询结果（共 %d 本，显示第 %d-%d 本）",
                    total, (p - 1) * l + 1, Math.min(p * l, total));

            StringBuilder sb = new StringBuilder();
            sb.append(formatBookList(books, title));
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
            java.util.List<ConditionDTO> allConditions = new java.util.ArrayList<>();
            if (conditions != null && !conditions.isBlank()) {
                var condList = parseConditions(conditions);
                allConditions.addAll(condList);
                title += "（筛选：" + conditions + "）";
            }
            requestBuilder.conditions(allConditions);

            ChartRequestDTO request = requestBuilder.build();

            // 非时间字段：SQL 层面已 ORDER BY count DESC，直接传 limit 到 SQL
            int maxResults = 0;
            if (!TIME_FIELDS.contains(field.toLowerCase())) {
                maxResults = limit != null && limit > 0 ? limit : 30;
            }

            Map<String, Map<String, Double>> data = bookService.getChartOptions(request, maxResults);

            return bookService.formatChartResult(data, title);
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

            var condList = parseConditions(conditions);
            Map<String, Object> updateMap = parseUpdates(updates);
            if (updateMap.isEmpty()) return "没有有效的更新字段。";

            var books = bookService.findList(condList);
            if (books.isEmpty()) return "没有找到符合条件的图书。";

            for (Book book : books) {
                for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
                    setFieldValue(book, entry.getKey(), entry.getValue());
                }
            }
            bookService.updateList(books);

            return String.format("已更新 %d 本图书。\n更新的字段：%s", books.size(), updates);
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
            var condList = parseConditions(conditions);
            var books = bookService.findList(condList, null, null, 1, 5);
            if (books.isEmpty()) return "没有找到符合条件的图书，无需删除。";
            if (books.size() == 5) {
                long total = bookService.getCount(condList);
                return String.format("警告：将删除 %d 本图书！\n预览前5本：\n%s\n如果确认删除，请说'确认删除'。",
                        total, formatBookList(books, null));
            }
            return String.format("将删除 %d 本图书：\n%s\n如果确认，请说'确认删除'。",
                    books.size(), formatBookList(books, null));
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
            var condList = parseConditions(conditions);
            var books = bookService.findList(condList);
            if (books.isEmpty()) return "没有找到符合条件的图书。";

            var ids = books.stream().map(Book::getId).toList();
            bookService.deleteListByIds(ids);

            return String.format("已删除 %d 本图书及相关数据。", books.size());
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
            return formatBookDetail(book);
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

    // ==================== 条件解析 ====================

    /**
     * 解析条件字符串为 ConditionDTO 列表
     * 格式: field|op|value,field|op|value
     */
    private java.util.List<ConditionDTO> parseConditions(String conditionStr) {
        if (!StringUtils.hasText(conditionStr)) {
            return java.util.List.of();
        }
        java.util.List<ConditionDTO> conditions = new java.util.ArrayList<>();
        String[] parts = conditionStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] segments = part.split("\\|");
            if (segments.length < 2) {
                conditions.add(ConditionDTO.like("title", segments[0].trim()));
                continue;
            }

            String field = segments[0].trim();
            String op = segments[1].trim();
            String value = segments.length > 2 ? segments[2].trim() : "";

            ConditionEnum opEnum = ConditionEnum.fromString(op);

            if (opEnum == ConditionEnum.BT && segments.length > 2) {
                String[] range = segments[2].split("~");
                if (range.length == 2) {
                    conditions.add(new ConditionDTO(field, opEnum, range[0].trim(), range[1].trim()));
                } else {
                    conditions.add(new ConditionDTO(field, opEnum, segments[2].trim(), ""));
                }
            } else if (opEnum == ConditionEnum.IN && segments.length > 2) {
                String[] values = segments[2].split(",");
                java.util.List<Object> valueList = java.util.Arrays.stream(values)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                conditions.add(new ConditionDTO(field, opEnum, valueList.toArray()));
            } else if (opEnum == ConditionEnum.IS_NULL || opEnum == ConditionEnum.NOT_NULL) {
                conditions.add(new ConditionDTO(field, opEnum));
            } else {
                conditions.add(new ConditionDTO(field, opEnum, value));
            }
        }
        return conditions;
    }

    /**
     * 解析排序字符串
     * 格式: field,asc 或 field1,desc;field2,asc
     */
    private SortInfo parseSort(String sortStr) {
        java.util.List<String> ascList = new java.util.ArrayList<>();
        java.util.List<String> descList = new java.util.ArrayList<>();

        if (!StringUtils.hasText(sortStr)) {
            descList.add("createdAt");
            return new SortInfo(ascList, descList);
        }

        String[] parts = sortStr.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            String[] segments = part.split(",");
            String field = segments[0].trim();
            boolean desc = segments.length > 1 && "desc".equalsIgnoreCase(segments[1].trim());

            if (desc) {
                descList.add(field);
            } else {
                ascList.add(field);
            }
        }
        return new SortInfo(ascList, descList);
    }

    /**
     * 解析更新字段字符串
     * 格式: field1=value1,field2=value2
     */
    private Map<String, Object> parseUpdates(String updateStr) {
        Map<String, Object> updates = new java.util.LinkedHashMap<>();
        if (!StringUtils.hasText(updateStr)) return updates;

        String[] parts = updateStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty() || !part.contains("=")) continue;

            int eqIndex = part.indexOf("=");
            String field = part.substring(0, eqIndex).trim();
            String value = part.substring(eqIndex + 1).trim();

            if (field.isEmpty() || !UPDATABLE_FIELDS.contains(field)) {
                log.warn("字段不允许更新或不存在: {}", field);
                continue;
            }
            updates.put(field, value);
        }
        return updates;
    }

    /** Book 实体允许 AI 动态更新的字段 */
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "title", "author", "description", "formatTags", "conceptTags",
            "readerNeedTags", "targetReaderTags", "toc", "chapterSummary",
            "coverUrl", "rating", "contentEmbedded"
    );

    /**
     * 通过反射设置 Book 实体的指定字段值
     * 自动进行类型转换（通过 BookService.convertToFieldType）
     *
     * @param book  书籍实体
     * @param field 字段名
     * @param value 字段值（字符串类型）
     */
    private void setFieldValue(Book book, String field, Object value) {
        try {
            java.lang.reflect.Field declaredField = Book.class.getDeclaredField(field);
            declaredField.setAccessible(true);
            Object converted = bookService.convertToFieldType(field, value);
            declaredField.set(book, converted);
        } catch (Exception e) {
            log.error("设置字段值失败: field={}, value={}", field, value, e);
        }
    }

    // ==================== 格式化输出 ====================

    /**
     * 将书籍列表格式化为可读的文本列表，包含序号、ID、书名、作者、格式、评分、阅读次数
     *
     * @param books 书籍列表
     * @param title 列表标题（可为 null）
     * @return 格式化后的文本字符串
     */
    private String formatBookList(java.util.List<Book> books, String title) {
        if (books == null || books.isEmpty()) return "没有找到图书。";
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append(":\n");
        }
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            sb.append(String.format("%d. [BOOK:id=%d]《%s》 作者:%s 格式:%s 评分:%.1f 阅读:%d\n",
                    i + 1, b.getId(), b.getTitle(),
                    b.getAuthor() != null ? b.getAuthor() : "未知",
                    b.getFormat() != null ? b.getFormat() : "-",
                    b.getRating() != null ? b.getRating() : 0.0,
                    b.getReadCount() != null ? b.getReadCount() : 0));
            if (b.getFormatTags() != null && !b.getFormatTags().isBlank()) {
                sb.append("   标签: ").append(b.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 将单本书籍格式化为详细信息文本
     *
     * @param b 书籍实体
     * @return 格式化后的详细信息文本
     */
    private String formatBookDetail(Book b) {
        if (b == null) return "图书不存在。";
        StringBuilder sb = new StringBuilder();
        sb.append("[BOOK:id=").append(b.getId()).append("]《").append(b.getTitle()).append("》\n");
        sb.append("作者: ").append(b.getAuthor() != null ? b.getAuthor() : "未知").append("\n");
        sb.append("格式: ").append(b.getFormat() != null ? b.getFormat() : "-").append("\n");
        sb.append("评分: ").append(b.getRating() != null ? String.format("%.1f", b.getRating()) : "0.0").append("\n");
        sb.append("阅读次数: ").append(b.getReadCount() != null ? b.getReadCount() : 0).append("\n");
        if (b.getFileSize() != null) {
            sb.append("文件大小: ").append(CommonUtils.formatFileSize(b.getFileSize())).append("\n");
        }
        if (b.getFormatTags() != null && !b.getFormatTags().isBlank()) {
            sb.append("标签: ").append(b.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、")).append("\n");
        }
        if (b.getDescription() != null && !b.getDescription().isBlank()) {
            sb.append("简介: ").append(CommonUtils.truncateText(b.getDescription(), 200)).append("\n");
        }
        if (b.getChapterSummary() != null && !b.getChapterSummary().isBlank()) {
            sb.append("章节摘要: ").append(CommonUtils.truncateText(b.getChapterSummary(), 200)).append("\n");
        }
        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据字段名返回对应的中文统计标题
     *
     * @param field 字段名（英文）
     * @return 中文统计标题
     */
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

    /**
     * 根据时间范围自动推断合适的聚合粒度
     * ≤90天按天聚合，≤400天按月聚合，否则按年聚合
     *
     * @param range 时间范围
     * @return 时间聚合粒度枚举
     */
    private TimeDeltaEnum resolveTimeDelta(TimeRange range) {
        if (range.start == null) return TimeDeltaEnum.ALL_MONTHS;
        long days = java.time.Duration.between(range.start, range.end != null ? range.end : LocalDateTime.now()).toDays();
        if (days <= 90) return TimeDeltaEnum.ALL_DAYS;
        if (days <= 400) return TimeDeltaEnum.ALL_MONTHS;
        return TimeDeltaEnum.YEAR;
    }

    /**
     * 解析时间范围字符串为 TimeRange 对象
     * 支持预设范围（本周、本月、本年、近7天等）和自定义范围（格式：起始日期~结束日期）
     *
     * @param timeRange 时间范围字符串
     * @return 解析后的时间范围对象
     */
    private TimeRange parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank() || "全部".equals(timeRange)) {
            return new TimeRange(null, null);
        }
        return switch (timeRange.trim()) {
            case "本周" -> new TimeRange(BookService.getWeekStart(), BookService.getWeekEnd());
            case "本月" -> new TimeRange(BookService.getMonthStart(), BookService.getMonthEnd());
            case "本年" -> new TimeRange(BookService.getYearStart(), BookService.getYearEnd());
            case "近7天" -> new TimeRange(BookService.getRecentDaysStart(7), null);
            case "近30天" -> new TimeRange(BookService.getRecentDaysStart(30), null);
            case "近90天" -> new TimeRange(BookService.getRecentDaysStart(90), null);
            case "近6个月" -> new TimeRange(BookService.getRecentMonthsStart(6), null);
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

    /**
     * 时间范围数据类
     *
     * @param start 起始时间（可为 null 表示不限）
     * @param end   结束时间（可为 null 表示不限）
     */
    private record TimeRange(LocalDateTime start, LocalDateTime end) {}

    /**
     * 排序信息数据类
     *
     * @param ascList  升序字段列表
     * @param descList 降序字段列表
     */
    private record SortInfo(java.util.List<String> ascList, java.util.List<String> descList) {}
}
