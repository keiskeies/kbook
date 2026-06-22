use sqlx::SqlitePool;
use kbook_core::entity::Book;
use kbook_vector::embedding::EmbeddingClient;
use std::collections::{HashMap, HashSet};

const RECALL_SIZE: usize = 100;
const MIN_VECTOR_SCORE: f64 = 0.7;

const SEMANTIC_KEYWORDS: &[&str] = &[
    "推荐", "适合", "类似", "风格", "关于", "有没有", "好看", "什么",
    "帮忙", "求", "想看", "喜欢", "感兴趣", "如何", "怎样", "比较",
];

// ── Search Weights ─────────────────────────────────────────────────────

struct SearchWeights {
    vector_weight: f64,
    keyword_weight: f64,
}

impl SearchWeights {
    fn of(vw: f64, kw: f64) -> Self {
        Self { vector_weight: vw, keyword_weight: kw }
    }
}

// ── Scored Book (fusion intermediate) ──────────────────────────────────

struct ScoredBook {
    book_id: i64,
    fusion_score: f64,
}

// ── Query Intent Analysis ──────────────────────────────────────────────

fn analyze_query_intent(keyword: &str) -> SearchWeights {
    let trimmed = keyword.trim();
    let len = trimmed.chars().count();

    if len <= 4 {
        return SearchWeights::of(0.3, 0.7);
    }

    let has_semantic_intent = SEMANTIC_KEYWORDS.iter().any(|k| trimmed.contains(k));
    if has_semantic_intent {
        return SearchWeights::of(0.8, 0.2);
    }

    if len >= 15 {
        return SearchWeights::of(0.7, 0.3);
    }

    SearchWeights::of(0.5, 0.5)
}

// ── Rank → Score conversion ────────────────────────────────────────────

fn rank_to_score(rank: usize) -> f64 {
    0.8_f64.powi(rank as i32 - 1)
}

// ── Dynamic Weight Adjustment ──────────────────────────────────────────

fn adjust_weights_by_recall(
    prior: &SearchWeights,
    vector_scores: &HashMap<i64, f64>,
    keyword_ranks: &HashMap<i64, usize>,
) -> SearchWeights {
    let vw = prior.vector_weight;
    let _kw = prior.keyword_weight;

    let vector_empty = vector_scores.is_empty();
    let keyword_empty = keyword_ranks.is_empty();

    if vector_empty && keyword_empty {
        return SearchWeights::of(vw, 1.0 - vw);
    }
    if vector_empty {
        return SearchWeights::of(0.2, 0.8);
    }
    if keyword_empty {
        return SearchWeights::of(0.8, 0.2);
    }

    let top_vector_score = vector_scores.values().cloned().fold(0.0_f64, f64::max);
    let vector_confidence = top_vector_score * 0.6
        + (vector_scores.len() as f64 / 20.0).min(1.0) * 0.4;

    let top_keyword_rank = *keyword_ranks.values().min().unwrap_or(&RECALL_SIZE);
    let keyword_confidence = rank_to_score(top_keyword_rank) * 0.6
        + (keyword_ranks.len() as f64 / 20.0).min(1.0) * 0.4;

    let vector_ids: HashSet<i64> = vector_scores.keys().copied().collect();
    let keyword_ids: HashSet<i64> = keyword_ranks.keys().copied().collect();
    let overlap = vector_ids.intersection(&keyword_ids).count() as f64;
    let min_size = vector_ids.len().min(keyword_ids.len()) as f64;
    let overlap_ratio = if min_size > 0.0 { overlap / min_size } else { 0.0 };

    let confidence_diff = vector_confidence - keyword_confidence;
    let mut adjustment = (confidence_diff * 0.2).max(-0.15).min(0.15);

    if overlap_ratio > 0.3 {
        adjustment *= 0.5;
    }

    let new_vw = (vw + adjustment).max(0.2).min(0.9);
    let new_kw = 1.0 - new_vw;

    SearchWeights::of(new_vw, new_kw)
}

// ── Tag / Format filter helper ─────────────────────────────────────────

fn matches_tag(book: &Book, tag: &str) -> bool {
    let pattern = tag.to_lowercase();
    let check = |opt: &Option<String>| -> bool {
        opt.as_ref().map_or(false, |s| s.to_lowercase().contains(&pattern))
    };
    check(&book.concept_tags) || check(&book.reader_need_tags) || check(&book.target_reader_tags)
}

// ── Main Hybrid Search Entry ───────────────────────────────────────────

pub async fn hybrid_search(
    pool: &SqlitePool,
    embedding_client: &EmbeddingClient,
    query: &str,
    format: Option<&str>,
    tag: Option<&str>,
    page: i32,
    size: i32,
) -> anyhow::Result<(Vec<Book>, i64)> {
    if query.trim().is_empty() {
        return crate::repository::book_repo::search(pool, None, format, tag, page, size).await;
    }

    let prior = analyze_query_intent(query);

    // ── Vector recall ───────────────────────────────────────────────
    let mut vector_scores: HashMap<i64, f64> = HashMap::new();
    match embedding_client.embed_single(query).await {
        Ok(query_vector) => {
            match kbook_vector::store::search_similar(pool, &query_vector, RECALL_SIZE, MIN_VECTOR_SCORE).await {
                Ok(results) => {
                    for (book_id, score) in results {
                        vector_scores.insert(book_id, score);
                    }
                }
                Err(e) => tracing::warn!("Vector recall error: {}", e),
            }
        }
        Err(e) => tracing::warn!("Embedding error: {}", e),
    }

    // ── Keyword recall (FTS5) ───────────────────────────────────────
    let mut keyword_ranks: HashMap<i64, usize> = HashMap::new();
    match crate::fts::search_fts_pool(pool, query, RECALL_SIZE as i32).await {
        Ok(ids) => {
            for (i, book_id) in ids.iter().enumerate() {
                keyword_ranks.entry(*book_id).or_insert(i + 1);
            }
        }
        Err(e) => tracing::warn!("FTS5 recall error: {}", e),
    }

    // ── Adjust weights ──────────────────────────────────────────────
    let weights = adjust_weights_by_recall(&prior, &vector_scores, &keyword_ranks);

    // ── Fusion scoring ──────────────────────────────────────────────
    let all_ids: HashSet<i64> = vector_scores.keys().chain(keyword_ranks.keys()).copied().collect();

    if all_ids.is_empty() {
        return Ok((vec![], 0));
    }

    let mut scored: Vec<ScoredBook> = Vec::new();
    for book_id in &all_ids {
        let has_vector = vector_scores.contains_key(book_id);
        let has_keyword = keyword_ranks.contains_key(book_id);

        let vs = vector_scores.get(book_id).copied().unwrap_or(0.0);
        let ks = keyword_ranks.get(book_id).map(|r| rank_to_score(*r)).unwrap_or(0.0);

        let fusion_score = if has_vector && has_keyword {
            weights.vector_weight * vs + weights.keyword_weight * ks
        } else if has_vector {
            vs * weights.vector_weight
        } else {
            ks * weights.keyword_weight
        };

        scored.push(ScoredBook { book_id: *book_id, fusion_score });
    }

    scored.sort_by(|a, b| b.fusion_score.partial_cmp(&a.fusion_score).unwrap_or(std::cmp::Ordering::Equal));

    let total = scored.len() as i64;

    // ── Paginate ────────────────────────────────────────────────────
    let start = ((page - 1).max(0) * size) as usize;
    let paged_ids: Vec<i64> = scored.into_iter()
        .skip(start)
        .take(size as usize)
        .map(|s| s.book_id)
        .collect();

    if paged_ids.is_empty() {
        return Ok((vec![], total));
    }

    // ── Fetch books by IDs ──────────────────────────────────────────
    let books = crate::repository::book_repo::find_by_ids(pool, &paged_ids).await?;

    // ── Apply format / tag filters ──────────────────────────────────
    let filtered: Vec<Book> = books.into_iter().filter(|b| {
        if let Some(f) = format {
            if b.format.as_deref() != Some(f) {
                return false;
            }
        }
        if let Some(t) = tag {
            if !matches_tag(b, t) {
                return false;
            }
        }
        true
    }).collect();

    // ── Preserve fusion order ───────────────────────────────────────
    let mut book_map: HashMap<i64, Book> = HashMap::new();
    for b in filtered {
        book_map.insert(b.id, b);
    }
    let ordered: Vec<Book> = paged_ids.into_iter()
        .filter_map(|id| book_map.remove(&id))
        .collect();

    Ok((ordered, total))
}
