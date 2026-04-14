package com.kbook.service;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 向量嵌入服务 — 负责书籍向量生成、Qdrant 存储、RAG 内容检索
 * <p>
 * 两个 Qdrant Collection：
 * 1. kbook_books — 书籍元数据向量（标题+作者+标签+简介 → 1个向量/书），用于推荐召回
 * 2. kbook_content — 书籍内容分块向量（内容段落 → N个向量/书），用于 RAG 语义检索
 * <p>
 * Embedding 模型：qwen3-embedding:4b (1024维)
 */
@Slf4j
@Service
public class EmbeddingService {

    private final QdrantClient qdrantClient;
    private final BookRepository bookRepository;

    /** 使用 @Lazy 打破循环依赖：AiToolService → RecommendService → EmbeddingService → AiProviderConfigService → AiToolService */
    private final AiProviderConfigService aiProviderConfigService;

    public EmbeddingService(QdrantClient qdrantClient,
                            BookRepository bookRepository,
                            @Lazy AiProviderConfigService aiProviderConfigService) {
        this.qdrantClient = qdrantClient;
        this.bookRepository = bookRepository;
        this.aiProviderConfigService = aiProviderConfigService;
    }

    @Value("${kbook.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${kbook.qdrant.port:6334}")
    private int qdrantPort;

    @Value("${kbook.qdrant.api-key:}")
    private String qdrantApiKey;

    @Value("${kbook.qdrant.book-collection:kbook_books}")
    private String bookCollectionName;

    @Value("${kbook.qdrant.content-collection:kbook_content}")
    private String contentCollectionName;

    @Value("${langchain4j.ollama.chat-model.base-url:http://localhost:11434}")
    private String defaultBaseUrl;

    @Value("${langchain4j.ollama.embedding-model.model-name:qwen3-embedding:4b}")
    private String embeddingModelName;

    /** 向量维度（qwen3-embedding:4b = 1024） */
    private static final int VECTOR_DIMENSION = 1024;

    /** RAG 内容分块大小（字符数） */
    private static final int CHUNK_SIZE = 800;

    /** RAG 内容分块重叠大小 */
    private static final int CHUNK_OVERLAP = 200;

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> bookEmbeddingStore;
    private EmbeddingStore<TextSegment> contentEmbeddingStore;

    /**
     * 初始化：创建 Qdrant Collection + 构建 EmbeddingModel 和 Store
     */
    @PostConstruct
    public void init() {
        try {
            // 初始化 Embedding 模型
            initEmbeddingModel();

            // 创建 Qdrant Collection（如果不存在）
            createCollectionIfNotExists(bookCollectionName);
            createCollectionIfNotExists(contentCollectionName);

            // 构建 EmbeddingStore
            buildEmbeddingStores();

            log.info("EmbeddingService 初始化完成: bookCollection={}, contentCollection={}, model={}",
                    bookCollectionName, contentCollectionName, embeddingModelName);
        } catch (Exception e) {
            log.error("EmbeddingService 初始化失败，将在首次使用时重试: {}", e.getMessage());
        }
    }

    /**
     * 初始化 Embedding 模型
     */
    private void initEmbeddingModel() {
        // 尝试使用管理员配置的 Base URL，否则使用默认 Ollama 地址
        var activeConfig = aiProviderConfigService.getActiveConfig();
        String baseUrl = defaultBaseUrl;

        if (activeConfig != null && activeConfig.getBaseUrl() != null && !activeConfig.getBaseUrl().isBlank()) {
            baseUrl = activeConfig.getBaseUrl();
            log.info("使用管理员配置的 AI 模型生成 Embedding: baseUrl={}, model={}", baseUrl, embeddingModelName);
        }

        embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 在 Qdrant 中创建 Collection（如果不存在）
     */
    private void createCollectionIfNotExists(String collectionName) {
        try {
            List<String> collectionNames = qdrantClient.listCollectionsAsync().get();
            boolean exists = collectionNames.contains(collectionName);

            if (!exists) {
                qdrantClient.createCollectionAsync(
                        io.qdrant.client.grpc.Collections.CreateCollection.newBuilder()
                                .setCollectionName(collectionName)
                                .setVectorsConfig(io.qdrant.client.grpc.Collections.VectorsConfig.newBuilder()
                                        .setParams(io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                                                .setSize(VECTOR_DIMENSION)
                                                .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                                .build())
                                        .build())
                                .build()
                ).get();
                log.info("Qdrant Collection 创建成功: {}", collectionName);
            } else {
                log.debug("Qdrant Collection 已存在: {}", collectionName);
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("创建 Qdrant Collection 失败: {} - {}", collectionName, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构建 EmbeddingStore 实例
     */
    private void buildEmbeddingStores() {
        bookEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(bookCollectionName)
                .build();

        contentEmbeddingStore = QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(contentCollectionName)
                .build();
    }

    // ==================== 书籍元数据向量（用于推荐召回） ====================

    /**
     * 异步为书籍生成元数据向量并存储到 Qdrant
     * 元数据 = 标题 + 作者 + 标签 + 简介 → 1个 embedding
     */
    @Async
    public void generateBookEmbeddingAsync(Long bookId) {
        try {
            if (embeddingModel == null || bookEmbeddingStore == null) {
                log.debug("Embedding 模型或 Store 未初始化，跳过书籍向量生成: bookId={}", bookId);
                return;
            }

            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) return;

            // 构建元数据文本
            String metadataText = buildBookMetadataText(book);
            if (metadataText.isBlank()) {
                log.debug("书籍元数据为空，跳过向量生成: bookId={}", bookId);
                return;
            }

            // 生成 embedding
            Embedding embedding = embeddingModel.embed(metadataText).content();

            // 构建带元数据的 TextSegment
            TextSegment segment = TextSegment.from(metadataText,
                    new Metadata().put("bookId", bookId)
                            .put("title", book.getTitle() != null ? book.getTitle() : "")
                            .put("author", book.getAuthor() != null ? book.getAuthor() : "")
                            .put("rating", book.getRating() != null ? book.getRating() : 0.0)
                            .put("readCount", book.getReadCount() != null ? book.getReadCount() : 0L));

            // 存入 Qdrant（先删除该书已有的旧向量，确保幂等）
            removeBookEmbedding(bookId);
            bookEmbeddingStore.add(embedding, segment);

            log.info("书籍元数据向量生成完成: bookId={}, textLen={}", bookId, metadataText.length());
        } catch (Exception e) {
            log.error("书籍元数据向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 通过元数据向量搜索相似书籍（用于推荐召回）
     *
     * @param queryText  查询文本（如用户画像描述或用户兴趣关键词）
     * @param maxResults 最大返回数量
     * @param minScore   最低相似度阈值
     * @param excludeBookIds 需要排除的图书ID
     * @return 匹配结果列表
     */
    public List<EmbeddingMatch<TextSegment>> searchSimilarBooks(String queryText, int maxResults,
                                                                  double minScore, List<Long> excludeBookIds) {
        if (embeddingModel == null || bookEmbeddingStore == null) {
            log.warn("Embedding 模型或 Store 未初始化，无法执行向量搜索");
            return List.of();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = bookEmbeddingStore.search(request)
                    .matches();

            // 排除已读书籍
            if (excludeBookIds != null && !excludeBookIds.isEmpty()) {
                matches = matches.stream()
                        .filter(m -> {
                            if (m.embedded() != null && m.embedded().metadata() != null) {
                                Long bookId = m.embedded().metadata().getLong("bookId");
                                return bookId != null && !excludeBookIds.contains(bookId);
                            }
                            return true;
                        })
                        .toList();
            }

            log.debug("向量搜索完成: query='{}', hits={}, afterFilter={}",
                    queryText.substring(0, Math.min(30, queryText.length())), maxResults, matches.size());

            return matches;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== RAG 内容向量（用于语义检索） ====================

    /**
     * 异步为书籍生成 RAG 内容向量并存储到 Qdrant
     * 将书籍内容按 CHUNK_SIZE 分块，每块生成一个 embedding
     */
    @Async
    public void generateContentEmbeddingAsync(Long bookId, String content) {
        try {
            if (embeddingModel == null || contentEmbeddingStore == null) {
                log.debug("Embedding 模型或 Store 未初始化，跳过内容向量生成: bookId={}", bookId);
                return;
            }

            if (content == null || content.isBlank()) {
                log.debug("书籍内容为空，跳过内容向量生成: bookId={}", bookId);
                return;
            }

            // 分块
            List<String> chunks = splitContent(content);
            log.info("书籍内容分块: bookId={}, totalChars={}, chunks={}", bookId, content.length(), chunks.size());

            // 先删除该书已有的旧内容向量，确保幂等
            removeContentEmbedding(bookId);

            // 批量生成 embedding 并存储
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                Embedding embedding = embeddingModel.embed(chunk).content();

                TextSegment segment = TextSegment.from(chunk,
                        new Metadata().put("bookId", bookId)
                                .put("chunkIndex", i)
                                .put("totalChunks", chunks.size()));

                contentEmbeddingStore.add(embedding, segment);
            }

            log.info("书籍内容向量生成完成: bookId={}, chunks={}", bookId, chunks.size());
        } catch (Exception e) {
            log.error("书籍内容向量生成失败: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * RAG 语义检索：根据查询在书籍内容中搜索相关片段
     *
     * @param query      查询文本
     * @param maxResults 最大返回数量
     * @param bookId     可选，限定在某本书内搜索
     * @return 匹配的内容片段
     */
    public List<EmbeddingMatch<TextSegment>> searchContent(String query, int maxResults, Long bookId) {
        if (embeddingModel == null || contentEmbeddingStore == null) {
            return List.of();
        }

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.5)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = contentEmbeddingStore.search(request).matches();

            // 如果指定了 bookId，过滤结果
            if (bookId != null) {
                matches = matches.stream()
                        .filter(m -> m.embedded() != null && m.embedded().metadata() != null
                                && bookId.equals(m.embedded().metadata().getLong("bookId")))
                        .toList();
            }

            return matches;
        } catch (Exception e) {
            log.error("RAG 内容检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据用户画像搜索相关书籍内容片段（用于 AI 推理辅助）
     */
    public List<EmbeddingMatch<TextSegment>> searchContentByUserProfile(String userProfileDesc, int maxResults) {
        return searchContent(userProfileDesc, maxResults, null);
    }

    // ==================== 管理操作 ====================

    /**
     * 重建所有书籍的元数据向量（管理员操作）
     */
    public int rebuildAllBookEmbeddings() {
        if (embeddingModel == null || bookEmbeddingStore == null) {
            log.warn("Embedding 模型或 Store 未初始化，无法重建向量");
            return 0;
        }

        List<Book> allBooks = bookRepository.findAll();
        log.info("开始重建所有书籍元数据向量: totalBooks={}", allBooks.size());

        int count = 0;
        for (Book book : allBooks) {
            try {
                String metadataText = buildBookMetadataText(book);
                if (metadataText.isBlank()) continue;

                // 先删除该书已有的旧向量
                removeBookEmbedding(book.getId());

                Embedding embedding = embeddingModel.embed(metadataText).content();
                TextSegment segment = TextSegment.from(metadataText,
                        new Metadata().put("bookId", book.getId())
                                .put("title", book.getTitle() != null ? book.getTitle() : "")
                                .put("author", book.getAuthor() != null ? book.getAuthor() : "")
                                .put("rating", book.getRating() != null ? book.getRating() : 0.0)
                                .put("readCount", book.getReadCount() != null ? book.getReadCount() : 0L));

                bookEmbeddingStore.add(embedding, segment);
                count++;

                if (count % 10 == 0) {
                    log.info("重建进度: {}/{}", count, allBooks.size());
                }

                // 避免触发 API 限流
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("重建向量失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }

        log.info("重建完成: {}/{} 成功", count, allBooks.size());
        return count;
    }

    /**
     * 检查 Embedding 功能是否可用
     */
    public boolean isAvailable() {
        return embeddingModel != null && bookEmbeddingStore != null && contentEmbeddingStore != null;
    }

    /**
     * 检查指定书籍是否已有元数据向量（通过 Qdrant 精确查询 bookId）
     */
    public boolean hasBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            var result = qdrantClient.countAsync(io.qdrant.client.grpc.Points.CountPoints.newBuilder()
                    .setCollectionName(bookCollectionName)
                    .setFilter(io.qdrant.client.grpc.Points.Filter.newBuilder()
                            .addMust(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                    .setKey("bookId")
                                    .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                            .setValue(JsonWithInt.Value.newBuilder()
                                                    .setStringValue(String.valueOf(bookId))
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .setExact(true)
                    .build()).get();
            return result.getCount() > 0;
        } catch (Exception e) {
            log.debug("检查书籍向量存在性失败: bookId={} - {}", bookId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定书籍是否已有内容向量
     */
    public boolean hasContentEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return false;
            var result = qdrantClient.countAsync(io.qdrant.client.grpc.Points.CountPoints.newBuilder()
                    .setCollectionName(contentCollectionName)
                    .setFilter(io.qdrant.client.grpc.Points.Filter.newBuilder()
                            .addMust(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                    .setKey("bookId")
                                    .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                            .setValue(JsonWithInt.Value.newBuilder()
                                                    .setStringValue(String.valueOf(bookId))
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .setExact(true)
                    .build()).get();
            return result.getCount() > 0;
        } catch (Exception e) {
            log.debug("检查内容向量存在性失败: bookId={} - {}", bookId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除指定书籍的元数据向量（通过 bookId 精确匹配 payload）
     */
    private void removeBookEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return;
            qdrantClient.deleteAsync(io.qdrant.client.grpc.Points.DeletePoints.newBuilder()
                    .setCollectionName(bookCollectionName)
                    .setFilter(io.qdrant.client.grpc.Points.Filter.newBuilder()
                            .addMust(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                    .setKey("bookId")
                                    .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                            .setValue(JsonWithInt.Value.newBuilder()
                                                    .setStringValue(String.valueOf(bookId))
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .build()).get();
            log.debug("已删除旧书籍元数据向量: bookId={}", bookId);
        } catch (Exception e) {
            log.debug("删除旧书籍元数据向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    /**
     * 删除指定书籍的内容向量（通过 bookId 精确匹配 payload）
     */
    private void removeContentEmbedding(Long bookId) {
        try {
            if (qdrantClient == null) return;
            qdrantClient.deleteAsync(io.qdrant.client.grpc.Points.DeletePoints.newBuilder()
                    .setCollectionName(contentCollectionName)
                    .setFilter(io.qdrant.client.grpc.Points.Filter.newBuilder()
                            .addMust(io.qdrant.client.grpc.Points.FieldCondition.newBuilder()
                                    .setKey("bookId")
                                    .setMatch(io.qdrant.client.grpc.Points.Match.newBuilder()
                                            .setValue(JsonWithInt.Value.newBuilder()
                                                    .setStringValue(String.valueOf(bookId))
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .build()).get();
            log.debug("已删除旧内容向量: bookId={}", bookId);
        } catch (Exception e) {
            log.debug("删除旧内容向量失败（可能不存在）: bookId={} - {}", bookId, e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建书籍元数据文本（用于生成 embedding）
     * 增强策略：标题 + 作者 + 标签 + 简介(1500字) + 目录 + 核心章节摘要
     */
    private String buildBookMetadataText(Book book) {
        StringBuilder sb = new StringBuilder();

        if (book.getTitle() != null && !book.getTitle().isBlank()) {
            sb.append("书名：").append(book.getTitle()).append("\n");
        }
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags()
                    .replaceAll("[\\[\\]\"]", "")
                    .replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            // 简介取前1500字（增强语义信号）
            String desc = book.getDescription().length() > 1500
                    ? book.getDescription().substring(0, 1500)
                    : book.getDescription();
            sb.append("简介：").append(desc).append("\n");
        }
        if (book.getToc() != null && !book.getToc().isBlank()) {
            // 目录结构蕴含主题和组织方式
            sb.append("目录：\n").append(book.getToc()).append("\n");
        }
        if (book.getChapterSummary() != null && !book.getChapterSummary().isBlank()) {
            // 核心章节摘要提供深层语义
            sb.append("核心内容：\n").append(book.getChapterSummary()).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 将书籍内容按固定大小分块（带重叠）
     */
    private List<String> splitContent(String content) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());

            // 尝试在句子结尾处断开
            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf('。', end);
                int lastExcl = content.lastIndexOf('！', end);
                int lastQues = content.lastIndexOf('？', end);
                int lastNewline = content.lastIndexOf('\n', end);
                int bestBreak = Math.max(Math.max(lastPeriod, lastExcl), Math.max(lastQues, lastNewline));

                if (bestBreak > start + CHUNK_SIZE / 2) {
                    end = bestBreak + 1;
                }
            }

            String chunk = content.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            start = end - CHUNK_OVERLAP;
            if (start <= 0) start = end; // 防止无限循环
        }
        return chunks;
    }
}
