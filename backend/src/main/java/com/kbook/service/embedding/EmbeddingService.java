package com.kbook.service.embedding;

import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.embedding.event.RagHitEvent;
import org.springframework.context.ApplicationEventPublisher;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.QuantizationConfigDiff;
import io.qdrant.client.grpc.Collections.QuantizationType;
import io.qdrant.client.grpc.Collections.ScalarQuantization;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ExecutionException;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;


/**
 * 向量服务门面 — 公开 API 原签名全量委托（调用方零改动）。
 * <p>
 * M6 拆分：基础设施/生成维护/检索分别由 {@link EmbeddingStoreManager}、
 * {@link EmbeddingIndexService}、{@link EmbeddingSearchService} 承接。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingIndexService indexService;
    private final EmbeddingSearchService searchService;

    public EmbeddingService(EmbeddingStoreManager storeManager,
                            EmbeddingIndexService indexService,
                            EmbeddingSearchService searchService) {
        this.storeManager = storeManager;
        this.indexService = indexService;
        this.searchService = searchService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        storeManager.init();
    }

    public boolean isAvailable() { return storeManager.isAvailable(); }
    public String getCurrentEmbeddingModelName() { return storeManager.getCurrentEmbeddingModelName(); }
    public Integer getCurrentEmbeddingDim() { return storeManager.getCurrentEmbeddingDim(); }

    public void generateBookEmbedding(Book book) { indexService.generateBookEmbedding(book); }
    public void upsertBookEmbedding(Book book) { indexService.upsertBookEmbedding(book); }
    public int generateContentEmbeddingWithCount(Long bookId, String content) { return indexService.generateContentEmbeddingWithCount(bookId, content); }
    public void removeBookEmbedding(Long bookId) { indexService.removeBookEmbedding(bookId); }
    public void removeContentEmbedding(Long bookId) { indexService.removeContentEmbedding(bookId); }
    public long clearAllBookEmbeddings() { return indexService.clearAllBookEmbeddings(); }
    public long clearAllContentEmbeddings() { return indexService.clearAllContentEmbeddings(); }
    public void rebuildAllBookEmbeddings(java.util.function.BiConsumer<Long, Long> progressCallback) { indexService.rebuildAllBookEmbeddings(progressCallback); }
    public boolean hasBookEmbedding(Long bookId) { return indexService.hasBookEmbedding(bookId); }
    public boolean hasContentEmbedding(Long bookId) { return indexService.hasContentEmbedding(bookId); }
    public boolean detectZeroContentVectors(Long bookId) { return indexService.detectZeroContentVectors(bookId); }
    public long getTotalBookEmbeddingCount() { return indexService.getTotalBookEmbeddingCount(); }
    public long getContentEmbeddedBookCount() { return indexService.getContentEmbeddedBookCount(); }
    public long getTotalContentEmbeddingCount() { return indexService.getTotalContentEmbeddingCount(); }
    public Map<String, Object> selfCheckEmbedding(Long bookId) { return indexService.selfCheckEmbedding(bookId); }
    public Map<String, Object> diagnose() { return indexService.diagnose(); }
    public List<EmbeddingIndexService.ChunkInfo> scrollAllContentChunks(Long bookId) { return indexService.scrollAllContentChunks(bookId); }
    public long countContentChunks(Long bookId) { return indexService.countContentChunks(bookId); }
    public List<EmbeddingIndexService.ChunkInfo> scrollChunksByIndexRange(Long bookId, int minIdx, int maxIdx) { return indexService.scrollChunksByIndexRange(bookId, minIdx, maxIdx); }

    public List<EmbeddingMatch<TextSegment>> searchSimilarBooks(String queryText, int maxResults, double minScore, List<Long> excludeBookIds) { return searchService.searchSimilarBooks(queryText, maxResults, minScore, excludeBookIds); }
    public List<EmbeddingMatch<TextSegment>> searchContent(String query, int maxResults, Book book) { return searchService.searchContent(query, maxResults, book); }
    public List<EmbeddingMatch<TextSegment>> searchContent(String query, int maxResults, Book book,
                                                           boolean appendDomainContext) {
        return searchService.searchContent(query, maxResults, book, appendDomainContext);
    }
    public EmbeddingMatch<TextSegment> searchContentByChunkIndex(Long bookId, int chunkIndex) { return searchService.searchContentByChunkIndex(bookId, chunkIndex); }
}
