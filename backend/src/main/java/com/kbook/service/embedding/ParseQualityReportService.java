package com.kbook.service.embedding;

import com.kbook.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析质量报告的持久化（入库计划 §4.1 `books.parse_quality`）。
 *
 * <p>单独一个 bean 是为了拿到独立事务：内容向量化本身跑几分钟，不能整个包在事务里
 * （会占住数据库连接），但 {@code @Modifying} 更新又必须在事务中执行。
 * 同一形状的既有实现见 {@link TocQualityEvaluator#evaluateAndCache}。</p>
 */
@Slf4j
@Service
public class ParseQualityReportService {

    private final BookRepository bookRepository;

    public ParseQualityReportService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    /**
     * 只更新 parse_quality 单字段：全字段 save 会重写 full_text 这类 MEDIUMTEXT 大字段。
     * 失败不影响向量化结果（报告是旁路诊断，不是回答链路）。
     */
    @Transactional
    public void save(Long bookId, ParseQualityReport.Stats stats) {
        if (bookId == null || stats == null) {
            return;
        }
        String json = ParseQualityReport.toJson(stats);
        try {
            bookRepository.updateParseQuality(bookId, json);
            log.info("解析质量报告已落库: bookId={}, rawChunks={}, accepted={}, rejected={}, "
                            + "garbledRatio={}, chapterCoverage={}, hardCuts={}",
                    bookId, stats.rawChunks(), stats.acceptedChunks(), stats.rejectedChunks(),
                    String.format(java.util.Locale.ROOT, "%.4f", stats.garbledRatio()),
                    String.format(java.util.Locale.ROOT, "%.4f", stats.chapterCoverage()),
                    stats.hardCuts());
        } catch (Exception e) {
            log.warn("解析质量报告落库失败: bookId={} - {}", bookId, e.getMessage());
        }
    }
}
