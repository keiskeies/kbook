use sqlx::SqlitePool;
use kbook_core::entity::Book;
use std::collections::{HashMap, HashSet};

const COVERAGE_FACTOR: [f64; 12] = [
    0.50, 0.60, 0.70, 0.78, 0.83, 0.87, 0.90, 0.93, 0.95, 0.97, 0.99, 1.00,
];

// ==================== Match Weights ====================

#[derive(Debug, Clone)]
struct MatchWeights {
    rating: f64,
    age: f64,
    gender: f64,
    married: f64,
    children: f64,
    mbti: f64,
    occupation: f64,
    education: f64,
    entrepreneurship: f64,
    income: f64,
    intent: f64,
    mood: f64,
    adjacent_decay: f64,
    children_adjacent_decay: f64,
    opposite_penalty: f64,
}

impl Default for MatchWeights {
    fn default() -> Self {
        Self {
            rating: 0.8,
            age: 1.5,
            gender: 0.8,
            married: 0.8,
            children: 0.8,
            mbti: 1.5,
            occupation: 1.2,
            education: 0.8,
            entrepreneurship: 0.6,
            income: 0.5,
            intent: 1.8,
            mood: 1.6,
            adjacent_decay: 0.4,
            children_adjacent_decay: 0.4,
            opposite_penalty: 0.3,
        }
    }
}

// ==================== Fusion Weights ====================

#[derive(Debug, Clone)]
struct FusionWeights {
    weight_rule: f64,
    weight_vector: f64,
    weight_explore: f64,
}

impl Default for FusionWeights {
    fn default() -> Self {
        // COLLAB skipped for desktop, redistributed: rule=0.375, vector=0.50, explore=0.125
        Self {
            weight_rule: 0.375,
            weight_vector: 0.50,
            weight_explore: 0.125,
        }
    }
}

// ==================== Freshness Config ====================

#[derive(Debug, Clone)]
struct FreshnessConfig {
    days_max: f64,
    days_decay: f64,
    bonus_max: f64,
    bonus_min: f64,
}

impl Default for FreshnessConfig {
    fn default() -> Self {
        Self {
            days_max: 7.0,
            days_decay: 30.0,
            bonus_max: 1.12,
            bonus_min: 1.03,
        }
    }
}

// ==================== Preference Config ====================

#[derive(Debug, Clone)]
struct PreferenceConfig {
    tag_bonus: f64,
    author_bonus: f64,
    format_bonus: f64,
}

impl Default for PreferenceConfig {
    fn default() -> Self {
        Self {
            tag_bonus: 0.12,
            author_bonus: 0.15,
            format_bonus: 0.05,
        }
    }
}

// ==================== Quality Config ====================

#[derive(Debug, Clone)]
struct QualityConfig {
    very_low: f64,
    low: f64,
    below_avg: f64,
    good: f64,
    excellent: f64,
    unknown: f64,
}

impl Default for QualityConfig {
    fn default() -> Self {
        Self {
            very_low: 0.40,
            low: 0.70,
            below_avg: 0.95,
            good: 1.15,
            excellent: 1.30,
            unknown: 0.85,
        }
    }
}

// ==================== Other Config ====================

#[derive(Debug, Clone)]
struct OtherConfig {
    mmr_lambda: f64,
    explore_random_count: i64,
    rule_min_score: f64,
}

impl Default for OtherConfig {
    fn default() -> Self {
        Self {
            mmr_lambda: 0.70,
            explore_random_count: 30,
            rule_min_score: -0.50,
        }
    }
}

// ==================== Dimension Contribution ====================

#[derive(Debug, Clone)]
#[allow(dead_code)]
struct DimensionContribution {
    dimension: String,
    raw_score: f64,
    deviation: f64,
    weight: f64,
    weighted_deviation: f64,
}

// ==================== Scored Book ====================

#[derive(Debug, Clone)]
struct ScoredBook {
    book_id: i64,
    final_score: f64,
    match_score: f64,
    vector_score: f64,
    recall_path: String,
}

// ==================== Recommend Service ====================

pub struct RecommendService {
    dimension_means: std::sync::RwLock<HashMap<String, f64>>,
    dimension_stddevs: std::sync::RwLock<HashMap<String, f64>>,
    weights_cache: std::sync::RwLock<Option<MatchWeights>>,
    fusion_cache: std::sync::RwLock<Option<FusionWeights>>,
    freshness_cache: std::sync::RwLock<Option<FreshnessConfig>>,
    preference_cache: std::sync::RwLock<Option<PreferenceConfig>>,
    quality_cache: std::sync::RwLock<Option<QualityConfig>>,
    other_cache: std::sync::RwLock<Option<OtherConfig>>,
}

impl RecommendService {
    pub fn new() -> Self {
        Self {
            dimension_means: std::sync::RwLock::new(HashMap::new()),
            dimension_stddevs: std::sync::RwLock::new(HashMap::new()),
            weights_cache: std::sync::RwLock::new(None),
            fusion_cache: std::sync::RwLock::new(None),
            freshness_cache: std::sync::RwLock::new(None),
            preference_cache: std::sync::RwLock::new(None),
            quality_cache: std::sync::RwLock::new(None),
            other_cache: std::sync::RwLock::new(None),
        }
    }

    pub fn set_stats(&self, means: HashMap<String, f64>, stddevs: HashMap<String, f64>) {
        if let Ok(mut m) = self.dimension_means.write() { *m = means; }
        if let Ok(mut s) = self.dimension_stddevs.write() { *s = stddevs; }
    }

    fn get_mean(&self, key: &str) -> f64 {
        self.dimension_means.read().ok().and_then(|m| m.get(key).copied()).unwrap_or(0.5)
    }

    fn get_stddev(&self, key: &str) -> f64 {
        self.dimension_stddevs.read().ok().and_then(|m| m.get(key).copied()).unwrap_or(0.15)
    }

    // ==================== Coefficient Loading ====================

    /// Load a single coefficient from the recommend_coefficient table with fallback
    async fn get_coefficient(pool: &SqlitePool, category: &str, key: &str, fallback: f64) -> f64 {
        let row: Option<(f64,)> = sqlx::query_as(
            "SELECT coeff_value FROM recommend_coefficient WHERE category = ? AND coeff_key = ?"
        )
        .bind(category)
        .bind(key)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten();
        row.map(|(v,)| v).unwrap_or(fallback)
    }

    /// Load match weights from database with fallback to defaults
    async fn load_weights(&self, pool: &SqlitePool) -> MatchWeights {
        // Check cache first
        if let Ok(cache) = self.weights_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let w = MatchWeights {
            rating: Self::get_coefficient(pool, "MATCH", "rating_weight", 0.8).await,
            age: Self::get_coefficient(pool, "MATCH", "age_weight", 1.5).await,
            gender: Self::get_coefficient(pool, "MATCH", "gender_weight", 0.8).await,
            married: Self::get_coefficient(pool, "MATCH", "married_weight", 0.8).await,
            children: Self::get_coefficient(pool, "MATCH", "children_weight", 0.8).await,
            mbti: Self::get_coefficient(pool, "MATCH", "mbti_weight", 1.5).await,
            occupation: Self::get_coefficient(pool, "MATCH", "occupation_weight", 1.2).await,
            education: Self::get_coefficient(pool, "MATCH", "education_weight", 0.8).await,
            entrepreneurship: Self::get_coefficient(pool, "MATCH", "entrepreneurship_weight", 0.6).await,
            income: Self::get_coefficient(pool, "MATCH", "income_weight", 0.5).await,
            intent: Self::get_coefficient(pool, "MATCH", "intent_weight", 1.8).await,
            mood: Self::get_coefficient(pool, "MATCH", "mood_weight", 1.6).await,
            adjacent_decay: Self::get_coefficient(pool, "MATCH", "adjacent_decay", 0.4).await,
            children_adjacent_decay: Self::get_coefficient(pool, "MATCH", "children_adjacent_decay", 0.4).await,
            opposite_penalty: Self::get_coefficient(pool, "MATCH", "opposite_penalty", 0.3).await,
        };

        if let Ok(mut cache) = self.weights_cache.write() {
            *cache = Some(w.clone());
        }
        w
    }

    async fn load_fusion_weights(&self, pool: &SqlitePool) -> FusionWeights {
        if let Ok(cache) = self.fusion_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let weight_rule = Self::get_coefficient(pool, "FUSION", "weight_rule", 0.30).await;
        let weight_vector = Self::get_coefficient(pool, "FUSION", "weight_vector", 0.40).await;
        // COLLAB skipped for desktop, redistribute its weight
        let weight_collab = Self::get_coefficient(pool, "FUSION", "weight_collab", 0.20).await;
        let weight_explore = Self::get_coefficient(pool, "FUSION", "weight_explore", 0.10).await;

        let total = weight_rule + weight_vector + weight_explore;
        let fw = FusionWeights {
            weight_rule: weight_rule / total,
            weight_vector: weight_vector / total,
            weight_explore: (weight_explore + weight_collab) / total,
        };

        if let Ok(mut cache) = self.fusion_cache.write() {
            *cache = Some(fw.clone());
        }
        fw
    }

    async fn load_freshness_config(&self, pool: &SqlitePool) -> FreshnessConfig {
        if let Ok(cache) = self.freshness_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let fc = FreshnessConfig {
            days_max: Self::get_coefficient(pool, "FRESHNESS", "days_max", 7.0).await,
            days_decay: Self::get_coefficient(pool, "FRESHNESS", "days_decay", 30.0).await,
            bonus_max: Self::get_coefficient(pool, "FRESHNESS", "bonus_max", 1.12).await,
            bonus_min: Self::get_coefficient(pool, "FRESHNESS", "bonus_min", 1.03).await,
        };

        if let Ok(mut cache) = self.freshness_cache.write() {
            *cache = Some(fc.clone());
        }
        fc
    }

    async fn load_preference_config(&self, pool: &SqlitePool) -> PreferenceConfig {
        if let Ok(cache) = self.preference_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let pc = PreferenceConfig {
            tag_bonus: Self::get_coefficient(pool, "PREFERENCE", "tag_bonus", 0.12).await,
            author_bonus: Self::get_coefficient(pool, "PREFERENCE", "author_bonus", 0.15).await,
            format_bonus: Self::get_coefficient(pool, "PREFERENCE", "format_bonus", 0.05).await,
        };

        if let Ok(mut cache) = self.preference_cache.write() {
            *cache = Some(pc.clone());
        }
        pc
    }

    async fn load_quality_config(&self, pool: &SqlitePool) -> QualityConfig {
        if let Ok(cache) = self.quality_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let qc = QualityConfig {
            very_low: Self::get_coefficient(pool, "QUALITY", "very_low", 0.40).await,
            low: Self::get_coefficient(pool, "QUALITY", "low", 0.70).await,
            below_avg: Self::get_coefficient(pool, "QUALITY", "below_avg", 0.95).await,
            good: Self::get_coefficient(pool, "QUALITY", "good", 1.15).await,
            excellent: Self::get_coefficient(pool, "QUALITY", "excellent", 1.30).await,
            unknown: Self::get_coefficient(pool, "QUALITY", "unknown", 0.85).await,
        };

        if let Ok(mut cache) = self.quality_cache.write() {
            *cache = Some(qc.clone());
        }
        qc
    }

    async fn load_other_config(&self, pool: &SqlitePool) -> OtherConfig {
        if let Ok(cache) = self.other_cache.read() {
            if cache.is_some() {
                return cache.as_ref().unwrap().clone();
            }
        }

        let oc = OtherConfig {
            mmr_lambda: Self::get_coefficient(pool, "OTHER", "mmr_lambda", 0.70).await,
            explore_random_count: Self::get_coefficient(pool, "OTHER", "explore_random_count", 30.0).await as i64,
            rule_min_score: Self::get_coefficient(pool, "OTHER", "rule_min_score", -0.50).await,
        };

        if let Ok(mut cache) = self.other_cache.write() {
            *cache = Some(oc.clone());
        }
        oc
    }

    /// Invalidate all coefficient caches so they will be reloaded on next access
    pub fn invalidate_caches(&self) {
        if let Ok(mut c) = self.weights_cache.write() { *c = None; }
        if let Ok(mut c) = self.fusion_cache.write() { *c = None; }
        if let Ok(mut c) = self.freshness_cache.write() { *c = None; }
        if let Ok(mut c) = self.preference_cache.write() { *c = None; }
        if let Ok(mut c) = self.quality_cache.write() { *c = None; }
        if let Ok(mut c) = self.other_cache.write() { *c = None; }
    }

    // ==================== Core Algorithm ====================

    /// Sigmoid normalization matching Spring Boot's normalizeScore
    fn normalize_score(raw_score: f64) -> f64 {
        if raw_score <= 0.0 { return 0.0; }
        let normalized = 1.0 / (1.0 + (-4.0 * (raw_score - 0.5)).exp());
        (normalized * 10000.0).round() / 10000.0
    }

    fn coverage_factor(matched_dims: usize) -> f64 {
        COVERAGE_FACTOR.get(matched_dims.min(11)).copied().unwrap_or(0.5)
    }

    /// Get deviation for a dimension key from the book's relevanceScores
    /// Uses Z-Score if stats available, otherwise rawScore - 0.5
    fn get_deviation(&self, key: &str, scores: &serde_json::Map<String, serde_json::Value>) -> f64 {
        let raw = match scores.get(key).and_then(|v| v.as_f64()) {
            Some(v) => v,
            None => return 0.0,
        };
        let mean = self.get_mean(key);
        let stddev = self.get_stddev(key);
        if stddev > 0.0 && mean != 0.5 {
            // Z-Score
            (raw - mean) / stddev
        } else {
            // Simple deviation
            raw - 0.5
        }
    }

    fn get_raw_score(key: &str, scores: &serde_json::Map<String, serde_json::Value>) -> f64 {
        scores.get(key).and_then(|v| v.as_f64()).unwrap_or(0.0)
    }

    // ==================== Intent Modulation ====================

    fn extract_intent(user: &kbook_core::entity::User) -> Option<String> {
        let mood = user.mood.as_ref()?;
        let pipe_idx = mood.find('|')?;
        if pipe_idx > 0 {
            Some(mood[..pipe_idx].to_lowercase())
        } else {
            None
        }
    }

    fn extract_mood_key(user: &kbook_core::entity::User) -> Option<String> {
        let mood = user.mood.as_ref()?;
        if let Some(pipe_idx) = mood.find('|') {
            if pipe_idx + 1 < mood.len() {
                Some(mood[pipe_idx + 1..].to_lowercase())
            } else {
                None
            }
        } else {
            Some(mood.to_lowercase())
        }
    }

    /// Intent-driven weight modulation matching Spring Boot's getIntentModulation
    fn get_intent_modulation(intent_key: Option<&str>, dimension: &str) -> f64 {
        let intent = match intent_key {
            Some(k) if !k.is_empty() => k,
            _ => return 1.0,
        };

        match intent {
            "growth" => match dimension {
                "children" => 0.25,
                "married" => 0.30,
                "gender" => 0.50,
                "age" => 0.70,
                "income" => 0.60,
                "occupation" => 1.20,
                "entrepreneurship" => 1.30,
                "intent" => 1.30,
                "mood" => 0.90,
                _ => 1.0,
            },
            "excite" => match dimension {
                "children" => 0.25,
                "married" => 0.30,
                "gender" => 0.50,
                "age" => 0.70,
                "income" => 0.60,
                "occupation" => 1.00,
                "entrepreneurship" => 1.00,
                "intent" => 1.20,
                "mood" => 1.20,
                _ => 1.0,
            },
            "escape" => match dimension {
                "children" => 0.40,
                "married" => 0.40,
                "gender" => 0.60,
                "age" => 0.80,
                "income" => 0.70,
                "mood" => 1.30,
                _ => 1.0,
            },
            "comfort" => match dimension {
                "children" => 1.20,
                "married" => 1.20,
                "gender" => 0.80,
                "mood" => 1.30,
                _ => 1.0,
            },
            "insight" => 1.0,
            _ => 1.0,
        }
    }

    // ==================== Dimension Contributions ====================

    fn add_rating_contribution(
        rating: Option<f64>,
        weights: &MatchWeights,
        list: &mut Vec<DimensionContribution>,
    ) {
        let r = match rating {
            Some(r) if r >= 0.0 => r,
            _ => return,
        };
        let weight = weights.rating;
        let normalized = (r / 5.0).min(1.0);
        let deviation = normalized - 0.5;
        list.push(DimensionContribution {
            dimension: "rating".to_string(),
            raw_score: normalized,
            deviation,
            weight,
            weighted_deviation: deviation * weight,
        });
    }

    fn add_age_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let birthday = match &user.birthday {
            Some(b) => b,
            None => return,
        };

        let age = match Self::parse_age(birthday) {
            Some(a) => a,
            None => return,
        };

        let weight = weights.age * Self::get_intent_modulation(intent_key, "age");
        let age_group = Self::get_age_group(age);

        // Main age group contribution
        let dev = self.get_deviation(&age_group, scores);
        let raw_score = Self::get_raw_score(&age_group, scores);
        list.push(DimensionContribution {
            dimension: "age".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: dev * weight,
        });

        // Adjacent age group contributions
        for dir in &[-1i32, 1i32] {
            if let Some(adj) = Self::get_adjacent_age_group(age, *dir) {
                if adj != age_group {
                    let adj_dev = self.get_deviation(&adj, scores);
                    let adj_weight = weight * weights.adjacent_decay;
                    list.push(DimensionContribution {
                        dimension: "age".to_string(),
                        raw_score,
                        deviation: adj_dev,
                        weight: adj_weight,
                        weighted_deviation: adj_dev * adj_weight,
                    });
                }
            }
        }
    }

    fn add_gender_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let gender = match &user.gender {
            Some(g) => g,
            None => return,
        };

        let weight = weights.gender * Self::get_intent_modulation(intent_key, "gender");
        let penalty = weights.opposite_penalty;

        let gender_key = if gender.eq_ignore_ascii_case("MALE") { "male" } else { "female" };
        let opposite_key = if gender.eq_ignore_ascii_case("MALE") { "female" } else { "male" };

        let dev = self.get_deviation(gender_key, scores);
        let raw_score = Self::get_raw_score(gender_key, scores);
        let mut weighted_dev = dev * weight;

        // Opposite penalty
        let opp_dev = self.get_deviation(opposite_key, scores);
        if opp_dev > 0.0 {
            weighted_dev -= opp_dev * penalty;
        }

        list.push(DimensionContribution {
            dimension: "gender".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: weighted_dev,
        });
    }

    fn add_married_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let is_married = match &user.is_married {
            Some(m) => m,
            None => return,
        };

        let weight = weights.married * Self::get_intent_modulation(intent_key, "married");
        let penalty = weights.opposite_penalty;

        let marry_key = if *is_married { "married" } else { "unmarried" };
        let opposite_key = if *is_married { "unmarried" } else { "married" };

        let dev = self.get_deviation(marry_key, scores);
        let raw_score = Self::get_raw_score(marry_key, scores);
        let mut weighted_dev = dev * weight;

        let opp_dev = self.get_deviation(opposite_key, scores);
        if opp_dev > 0.0 {
            weighted_dev -= opp_dev * penalty;
        }

        list.push(DimensionContribution {
            dimension: "married".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: weighted_dev,
        });
    }

    fn add_children_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        // New format: childrenAgeRanges (comma-separated like "children_0_2,children_3_6")
        if let Some(ref ranges_str) = user.children_age_ranges {
            if !ranges_str.is_empty() {
                let weight = weights.children * Self::get_intent_modulation(intent_key, "children");
                let child_decay = weights.children_adjacent_decay;

                for range in ranges_str.split(',') {
                    let child_key = range.trim().to_lowercase();
                    if child_key.is_empty() { continue; }
                    if !scores.contains_key(&child_key) { continue; }

                    let dev = self.get_deviation(&child_key, scores);
                    let raw_score = Self::get_raw_score(&child_key, scores);
                    list.push(DimensionContribution {
                        dimension: "children".to_string(),
                        raw_score,
                        deviation: dev,
                        weight,
                        weighted_deviation: dev * weight,
                    });

                    // Adjacent child range decay
                    for adj in Self::get_adjacent_child_ranges(&child_key) {
                        let adj_dev = self.get_deviation(&adj, scores);
                        let adj_weight = weight * child_decay;
                        list.push(DimensionContribution {
                            dimension: "children".to_string(),
                            raw_score,
                            deviation: adj_dev,
                            weight: adj_weight,
                            weighted_deviation: adj_dev * adj_weight,
                        });
                    }
                }
                return;
            }
        }

        // Old format: hasChildren boolean
        if let Some(has_children) = user.has_children {
            let weight = weights.children * Self::get_intent_modulation(intent_key, "children");
            let penalty = weights.opposite_penalty;

            let child_key = if has_children { "hasChildren" } else { "no_children" };
            let opposite_key = if has_children { "no_children" } else { "hasChildren" };

            let raw_score = Self::get_raw_score(child_key, scores);
            let dev = if scores.contains_key(child_key) {
                self.get_deviation(child_key, scores)
            } else {
                0.0
            };
            let mut weighted_dev = dev * weight;

            let opp_dev = self.get_deviation(opposite_key, scores);
            if opp_dev > 0.0 {
                weighted_dev -= opp_dev * penalty;
            }

            list.push(DimensionContribution {
                dimension: "hasChildren".to_string(),
                raw_score,
                deviation: dev,
                weight,
                weighted_deviation: weighted_dev,
            });
        }
    }

    fn add_mbti_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        list: &mut Vec<DimensionContribution>,
    ) {
        let mbti = match &user.mbti {
            Some(m) => m,
            None => return,
        };

        let weight = weights.mbti;
        let mbti_key = mbti.to_uppercase();

        let dev = self.get_deviation(&mbti_key, scores);
        let raw_score = Self::get_raw_score(&mbti_key, scores);
        list.push(DimensionContribution {
            dimension: "mbti".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: dev * weight,
        });
    }

    fn add_occupation_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let occ = match &user.occupation {
            Some(o) if !o.is_empty() => o,
            _ => return,
        };

        let weight = weights.occupation * Self::get_intent_modulation(intent_key, "occupation");

        for user_occ in occ.split(',') {
            let occ_key = user_occ.trim().to_lowercase();
            if occ_key.is_empty() { continue; }

            let dev = self.get_deviation(&occ_key, scores);
            let raw_score = Self::get_raw_score(&occ_key, scores);
            list.push(DimensionContribution {
                dimension: "occupation".to_string(),
                raw_score,
                deviation: dev,
                weight,
                weighted_deviation: dev * weight,
            });

            for adj in Self::get_adjacent_occupations(&occ_key) {
                let adj_dev = self.get_deviation(&adj, scores);
                let adj_weight = weight * weights.adjacent_decay;
                list.push(DimensionContribution {
                    dimension: "occupation".to_string(),
                    raw_score,
                    deviation: adj_dev,
                    weight: adj_weight,
                    weighted_deviation: adj_dev * adj_weight,
                });
            }
        }
    }

    fn add_education_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let edu = match &user.education {
            Some(e) if !e.is_empty() => e,
            _ => return,
        };

        let edu_key = edu.to_lowercase();
        let weight = weights.education * Self::get_intent_modulation(intent_key, "education");

        let dev = self.get_deviation(&edu_key, scores);
        let raw_score = Self::get_raw_score(&edu_key, scores);
        list.push(DimensionContribution {
            dimension: "education".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: dev * weight,
        });

        for adj in Self::get_adjacent_educations(&edu_key) {
            let adj_dev = self.get_deviation(&adj, scores);
            let adj_weight = weight * weights.adjacent_decay;
            list.push(DimensionContribution {
                dimension: "education".to_string(),
                raw_score,
                deviation: adj_dev,
                weight: adj_weight,
                weighted_deviation: adj_dev * adj_weight,
            });
        }
    }

    fn add_entrepreneurship_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let ent = match &user.entrepreneurship {
            Some(e) if !e.is_empty() => e,
            _ => return,
        };

        let entre_key = ent.to_lowercase();
        let weight = weights.entrepreneurship * Self::get_intent_modulation(intent_key, "entrepreneurship");

        let dev = self.get_deviation(&entre_key, scores);
        let raw_score = Self::get_raw_score(&entre_key, scores);
        list.push(DimensionContribution {
            dimension: "entrepreneurship".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: dev * weight,
        });
    }

    fn add_income_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        let inc = match &user.annual_income {
            Some(i) if !i.is_empty() => i,
            _ => return,
        };
        if inc.eq_ignore_ascii_case("PREFER_NOT_TO_SAY") { return; }

        let income_key = inc.to_lowercase();
        let weight = weights.income * Self::get_intent_modulation(intent_key, "income");

        let dev = self.get_deviation(&income_key, scores);
        let raw_score = Self::get_raw_score(&income_key, scores);
        list.push(DimensionContribution {
            dimension: "income".to_string(),
            raw_score,
            deviation: dev,
            weight,
            weighted_deviation: dev * weight,
        });

        for adj in Self::get_adjacent_incomes(&income_key) {
            let adj_dev = self.get_deviation(&adj, scores);
            let adj_weight = weight * weights.adjacent_decay;
            list.push(DimensionContribution {
                dimension: "income".to_string(),
                raw_score,
                deviation: adj_dev,
                weight: adj_weight,
                weighted_deviation: adj_dev * adj_weight,
            });
        }
    }

    fn add_mood_contributions(
        &self,
        user: &kbook_core::entity::User,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
        intent_key: Option<&str>,
        list: &mut Vec<DimensionContribution>,
    ) {
        if user.mood.is_none() || user.mood.as_ref().map_or(true, |m| m.is_empty()) {
            return;
        }

        let mood_key = match Self::extract_mood_key(user) {
            Some(k) if !k.is_empty() => k,
            _ => return,
        };

        // Intent dimension contribution + related intents decay
        if let Some(ref intent) = intent_key {
            if !intent.is_empty() {
                let weight = weights.intent * Self::get_intent_modulation(Some(intent), "intent");
                let dev = self.get_deviation(intent, scores);
                let raw_score = Self::get_raw_score(intent, scores);
                list.push(DimensionContribution {
                    dimension: "intent".to_string(),
                    raw_score,
                    deviation: dev,
                    weight,
                    weighted_deviation: dev * weight,
                });

                for adj in Self::get_related_intents(intent) {
                    let adj_dev = self.get_deviation(&adj, scores);
                    let adj_weight = weight * weights.adjacent_decay;
                    list.push(DimensionContribution {
                        dimension: "intent".to_string(),
                        raw_score,
                        deviation: adj_dev,
                        weight: adj_weight,
                        weighted_deviation: adj_dev * adj_weight,
                    });
                }
            }
        }

        // Mood dimension contribution + related moods decay
        if !mood_key.is_empty() {
            let weight = weights.mood * Self::get_intent_modulation(intent_key, "mood");
            let dev = self.get_deviation(&mood_key, scores);
            let raw_score = Self::get_raw_score(&mood_key, scores);
            list.push(DimensionContribution {
                dimension: "mood".to_string(),
                raw_score,
                deviation: dev,
                weight,
                weighted_deviation: dev * weight,
            });

            for adj in Self::get_related_moods(&mood_key) {
                let adj_dev = self.get_deviation(&adj, scores);
                let adj_weight = weight * weights.adjacent_decay;
                list.push(DimensionContribution {
                    dimension: "mood".to_string(),
                    raw_score,
                    deviation: adj_dev,
                    weight: adj_weight,
                    weighted_deviation: adj_dev * adj_weight,
                });
            }
        }
    }

    /// Collect all dimension contributions (core dispatch method)
    fn collect_contributions(
        &self,
        user: &kbook_core::entity::User,
        rating: Option<f64>,
        scores: &serde_json::Map<String, serde_json::Value>,
        weights: &MatchWeights,
    ) -> Vec<DimensionContribution> {
        let mut list = Vec::new();
        let intent_key = Self::extract_intent(user);

        Self::add_rating_contribution(rating, weights, &mut list);
        self.add_age_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_gender_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_married_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_children_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_mbti_contributions(user, scores, weights, &mut list);
        self.add_occupation_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_education_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_entrepreneurship_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_income_contributions(user, scores, weights, intent_key.as_deref(), &mut list);
        self.add_mood_contributions(user, scores, weights, intent_key.as_deref(), &mut list);

        list
    }

    /// Calculate match score — main entry point matching Spring Boot's calculateMatchScore
    fn calculate_match_score(
        &self,
        user: &kbook_core::entity::User,
        rating: Option<f64>,
        relevance_json: Option<&str>,
        weights: &MatchWeights,
    ) -> f64 {
        let json_str = match relevance_json {
            Some(s) if !s.is_empty() => s,
            _ => return 0.0,
        };

        let scores: serde_json::Map<String, serde_json::Value> = match serde_json::from_str(json_str) {
            Ok(serde_json::Value::Object(map)) => map,
            _ => return 0.0,
        };

        let contributions = self.collect_contributions(user, rating, &scores, weights);

        let mut total_deviation = 0.0f64;
        let mut total_weight = 0.0f64;
        let mut dimension_set: HashSet<&str> = HashSet::new();

        for c in &contributions {
            total_deviation += c.weighted_deviation;
            total_weight += c.weight;
            dimension_set.insert(c.dimension.as_str());
        }

        if total_weight <= 0.0 { return 0.0; }

        let avg_deviation = total_deviation / total_weight;
        let cf = Self::coverage_factor(dimension_set.len());
        Self::normalize_score(avg_deviation * cf)
    }

    // ==================== Adjacent Dimension Mappings ====================

    fn parse_age(birthday: &str) -> Option<i32> {
        let parts: Vec<&str> = birthday.split('-').collect();
        if parts.len() < 3 { return None; }
        let year: i32 = parts[0].parse().ok()?;
        let now = chrono::Utc::now();
        let current_year = now.format("%Y").to_string().parse::<i32>().unwrap_or(2026);
        Some(current_year - year)
    }

    fn get_age_group(age: i32) -> String {
        if age < 10 { "0-9".to_string() }
        else if age < 20 { "10-19".to_string() }
        else if age < 30 { "20-29".to_string() }
        else if age < 40 { "30-39".to_string() }
        else if age < 50 { "40-49".to_string() }
        else if age < 60 { "50-59".to_string() }
        else { "60+".to_string() }
    }

    fn get_adjacent_age_group(age: i32, direction: i32) -> Option<String> {
        let boundaries: [i32; 8] = [0, 10, 20, 30, 40, 50, 60, i32::MAX];
        let mut current_idx: i32 = -1;
        for i in 0..boundaries.len() - 1 {
            if age >= boundaries[i] && age < boundaries[i + 1] {
                current_idx = i as i32;
                break;
            }
        }
        if current_idx < 0 { return None; }
        let adjacent_idx = current_idx + direction;
        if adjacent_idx < 0 || adjacent_idx >= (boundaries.len() - 1) as i32 { return None; }
        Some(Self::get_age_group(boundaries[adjacent_idx as usize]))
    }

    fn get_adjacent_child_ranges(child_key: &str) -> Vec<String> {
        match child_key.to_lowercase().as_str() {
            "children_0_2" => vec!["children_3_6".to_string()],
            "children_3_6" => vec!["children_0_2".to_string(), "children_7_12".to_string()],
            "children_7_12" => vec!["children_3_6".to_string(), "children_13_17".to_string()],
            "children_13_17" => vec!["children_7_12".to_string(), "children_18_plus".to_string()],
            "children_18_plus" => vec!["children_13_17".to_string()],
            _ => vec![],
        }
    }

    fn get_adjacent_occupations(occupation: &str) -> Vec<String> {
        match occupation.to_lowercase().as_str() {
            "student" => vec!["education".to_string()],
            "tech" => vec!["education".to_string(), "freelance".to_string()],
            "finance" => vec!["management".to_string()],
            "education" => vec!["student".to_string(), "tech".to_string()],
            "medical" => vec!["education".to_string()],
            "arts" => vec!["freelance".to_string(), "education".to_string()],
            "management" => vec!["finance".to_string()],
            "freelance" => vec!["arts".to_string(), "tech".to_string()],
            "retired" => vec![],
            "other" => vec![],
            _ => vec![],
        }
    }

    fn get_adjacent_educations(education: &str) -> Vec<String> {
        match education.to_lowercase().as_str() {
            "high_school" => vec!["college".to_string()],
            "college" => vec!["high_school".to_string(), "bachelor".to_string()],
            "bachelor" => vec!["college".to_string(), "master".to_string()],
            "master" => vec!["bachelor".to_string(), "doctorate".to_string()],
            "doctorate" => vec!["master".to_string()],
            "other" => vec![],
            _ => vec![],
        }
    }

    fn get_adjacent_incomes(income: &str) -> Vec<String> {
        match income.to_lowercase().as_str() {
            "under_50k" => vec!["50k_150k".to_string()],
            "50k_150k" => vec!["under_50k".to_string(), "150k_300k".to_string()],
            "150k_300k" => vec!["50k_150k".to_string(), "300k_500k".to_string()],
            "300k_500k" => vec!["150k_300k".to_string(), "500k_1m".to_string()],
            "500k_1m" => vec!["300k_500k".to_string(), "over_1m".to_string()],
            "over_1m" => vec!["500k_1m".to_string()],
            _ => vec![],
        }
    }

    fn get_related_moods(mood: &str) -> Vec<String> {
        match mood.to_lowercase().as_str() {
            "happy" => vec!["calm".to_string()],
            "calm" => vec!["happy".to_string()],
            "anxious" => vec!["sad".to_string(), "tired".to_string(), "frustrated".to_string()],
            "sad" => vec!["anxious".to_string(), "tired".to_string(), "frustrated".to_string()],
            "tired" => vec!["sad".to_string(), "anxious".to_string()],
            "frustrated" => vec!["anxious".to_string(), "sad".to_string()],
            _ => vec![],
        }
    }

    fn get_related_intents(intent: &str) -> Vec<String> {
        match intent.to_lowercase().as_str() {
            "growth" => vec!["insight".to_string()],
            "insight" => vec!["growth".to_string(), "comfort".to_string()],
            "comfort" => vec!["escape".to_string(), "insight".to_string()],
            "escape" => vec!["comfort".to_string(), "excite".to_string()],
            "excite" => vec!["escape".to_string()],
            _ => vec![],
        }
    }

    // ==================== Quality Factor ====================

    fn get_quality_factor(rating: Option<f64>, quality_config: &QualityConfig) -> f64 {
        let r = match rating {
            Some(r) if r > 0.0 => r,
            _ => return quality_config.unknown,
        };
        if r < 2.0 { quality_config.very_low }
        else if r < 3.0 { quality_config.low }
        else if r < 4.0 { quality_config.below_avg }
        else if r < 5.0 { quality_config.good }
        else { quality_config.excellent }
    }

    // ==================== Freshness Bonus ====================

    fn calculate_freshness_bonus(created_at: Option<&str>, config: &FreshnessConfig) -> f64 {
        let created_str = match created_at {
            Some(s) if !s.is_empty() => s,
            _ => return 0.0,
        };

        let created = match chrono::NaiveDateTime::parse_from_str(created_str, "%Y-%m-%d %H:%M:%S") {
            Ok(dt) => dt,
            Err(_) => match chrono::NaiveDate::parse_from_str(created_str, "%Y-%m-%d") {
                Ok(d) => d.and_hms_opt(0, 0, 0).unwrap(),
                Err(_) => return 0.0,
            },
        };

        let now = chrono::Utc::now().naive_utc();
        let days_ago = (now - created).num_days().max(0) as f64;

        if days_ago <= config.days_max {
            config.bonus_max + (1.0 - config.bonus_max) * (days_ago / config.days_max)
        } else if days_ago <= config.days_decay {
            let progress = (days_ago - config.days_max) / (config.days_decay - config.days_max);
            config.bonus_min + (config.bonus_max - config.bonus_min) * (1.0 - progress)
        } else {
            1.0
        }
    }

    // ==================== Preference Bonus ====================

    fn calculate_include_bonus(
        book: &Book,
        included_tags: &[String],
        included_authors: &[String],
        included_formats: &[String],
        config: &PreferenceConfig,
    ) -> f64 {
        let mut bonus = 0.0;

        if !included_tags.is_empty() {
            if let Some(ref tags_str) = book.format_tags {
                let book_tags: Vec<&str> = tags_str.split(',').map(|t| t.trim()).collect();
                for tag in included_tags {
                    if book_tags.iter().any(|bt| bt.eq_ignore_ascii_case(tag)) {
                        bonus += config.tag_bonus;
                    }
                }
            }
        }

        if !included_authors.is_empty() {
            if let Some(ref author) = book.author {
                for inc_author in included_authors {
                    if author.eq_ignore_ascii_case(inc_author) {
                        bonus += config.author_bonus;
                        break;
                    }
                }
            }
        }

        if !included_formats.is_empty() {
            if let Some(ref format) = book.format {
                for inc_format in included_formats {
                    if format.eq_ignore_ascii_case(inc_format) {
                        bonus += config.format_bonus;
                        break;
                    }
                }
            }
        }

        bonus
    }

    // ==================== MMR Diversity ====================

    fn mmr_diversify(scored: &mut Vec<ScoredBook>, _lambda: f64, max_count: usize) {
        if scored.len() <= max_count { return; }

        // Simple top-N truncation for now; full MMR with author dedup
        // can be applied after fetching full book data
        scored.sort_by(|a, b| b.final_score.partial_cmp(&a.final_score).unwrap_or(std::cmp::Ordering::Equal));
        scored.truncate(max_count);
    }

    // ==================== Public API ====================

    pub async fn get_recommendations(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        count: usize,
    ) -> anyhow::Result<Vec<Book>> {
        let result = self.score_all_books(pool, user_id).await?;
        if result.is_empty() {
            let books = sqlx::query_as::<_, Book>(
                "SELECT * FROM books WHERE rating_count > 0 ORDER BY rating DESC, read_count DESC LIMIT ?"
            )
            .bind(count as i32)
            .fetch_all(pool)
            .await?;
            return Ok(books);
        }
        let ids: Vec<i64> = result.into_iter().take(count).map(|sb| sb.book_id).collect();
        if ids.is_empty() { return Ok(vec![]); }

        // Build parameterized query with placeholders
        let placeholders: Vec<&str> = ids.iter().map(|_| "?").collect();
        let sql = format!("SELECT * FROM books WHERE id IN ({})", placeholders.join(","));
        let mut query = sqlx::query_as::<_, Book>(&sql);
        for id in &ids {
            query = query.bind(*id);
        }
        let books = query.fetch_all(pool).await?;

        // Preserve the order from scored results
        let mut book_map: HashMap<i64, Book> = HashMap::new();
        for b in books {
            book_map.insert(b.id, b);
        }
        let ordered: Vec<Book> = ids.into_iter()
            .filter_map(|id| book_map.remove(&id))
            .collect();
        Ok(ordered)
    }

    pub async fn record_feedback(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        book_id: i64,
        event_type: &str,
    ) -> anyhow::Result<()> {
        kbook_db::repository::recommend_repo::record_feedback(pool, user_id, book_id, event_type).await
    }

    pub async fn generate_recommendations(
        &self,
        pool: &SqlitePool,
        user_id: i64,
    ) -> anyhow::Result<Vec<Book>> {
        self.get_recommendations(pool, user_id, 20).await
    }

    // ==================== Score All Books ====================

    async fn score_all_books(
        &self,
        pool: &SqlitePool,
        user_id: i64,
    ) -> anyhow::Result<Vec<ScoredBook>> {
        let user = sqlx::query_as::<_, kbook_core::entity::User>(
            "SELECT * FROM users WHERE id = ?"
        ).bind(user_id).fetch_optional(pool).await?;

        let has_profile = user.as_ref().map(|u| {
            u.birthday.is_some() || u.gender.is_some() || u.is_married.is_some()
                || u.has_children.is_some() || u.children_age_ranges.is_some()
                || u.mbti.is_some() || u.occupation.is_some()
                || u.education.is_some() || u.entrepreneurship.is_some()
                || u.annual_income.is_some() || u.mood.is_some()
        }).unwrap_or(false);

        if !has_profile {
            return self.score_hot_books(pool).await;
        }

        let user = user.unwrap();
        let exclude_ids = self.get_exclude_ids(pool, user_id).await?;

        // Load configs from database
        let weights = self.load_weights(pool).await;
        let fusion = self.load_fusion_weights(pool).await;
        let freshness_config = self.load_freshness_config(pool).await;
        let preference_config = self.load_preference_config(pool).await;
        let quality_config = self.load_quality_config(pool).await;
        let other_config = self.load_other_config(pool).await;

        // Get user preferences
        let (included_tags, included_authors, included_formats, excluded_tags, excluded_authors, excluded_formats) =
            self.get_user_preferences(pool, user_id).await?;

        // Build exclusion clause
        let books: Vec<Book> = if exclude_ids.is_empty() {
            sqlx::query_as::<_, Book>("SELECT * FROM books")
                .fetch_all(pool).await.unwrap_or_default()
        } else {
            let placeholders: Vec<String> = exclude_ids.iter().enumerate()
                .map(|(i, _)| format!("?{}", i + 1))
                .collect();
            let sql = format!("SELECT * FROM books WHERE id NOT IN ({})", placeholders.join(","));
            let mut query = sqlx::query_as::<_, Book>(&sql);
            for id in &exclude_ids {
                query = query.bind(*id);
            }
            query.fetch_all(pool).await.unwrap_or_default()
        };

        // ---- RULE path: match score ----
        let mut rule_scored: Vec<ScoredBook> = Vec::new();
        for book in &books {
            // Check exclusion by preference
            if self.is_excluded_by_preference(book, &excluded_tags, &excluded_authors, &excluded_formats) {
                continue;
            }

            let match_score = self.calculate_match_score(
                &user, book.rating, book.relevance_scores.as_deref(), &weights,
            );

            if match_score <= other_config.rule_min_score { continue; }

            let freshness = Self::calculate_freshness_bonus(book.created_at.as_deref(), &freshness_config);
            let preference = Self::calculate_include_bonus(book, &included_tags, &included_authors, &included_formats, &preference_config);

            // Fusion: match_score * freshness * quality_factor + preference bonus
            let quality_factor = Self::get_quality_factor(book.rating, &quality_config);
            let final_score = (match_score * freshness * quality_factor + preference)
                .max(0.0).min(1.0);

            rule_scored.push(ScoredBook {
                book_id: book.id,
                final_score,
                match_score,
                vector_score: 0.0,
                recall_path: "RULE".to_string(),
            });
        }

        rule_scored.sort_by(|a, b| b.final_score.partial_cmp(&a.final_score).unwrap_or(std::cmp::Ordering::Equal));

        // ---- EXPLORE path: random sampling ----
        let explore_count = other_config.explore_random_count as usize;
        let explore_books = self.get_explore_books(pool, &exclude_ids, explore_count).await;
        let mut explore_scored: Vec<ScoredBook> = Vec::new();
        for book in &explore_books {
            if self.is_excluded_by_preference(book, &excluded_tags, &excluded_authors, &excluded_formats) {
                continue;
            }
            let quality_factor = Self::get_quality_factor(book.rating, &quality_config);
            // Explore uses a base score with quality factor
            let base_score = 0.3 * quality_factor;
            explore_scored.push(ScoredBook {
                book_id: book.id,
                final_score: base_score.max(0.0).min(1.0),
                match_score: 0.0,
                vector_score: 0.0,
                recall_path: "EXPLORE".to_string(),
            });
        }

        // ---- VECTOR path: placeholder for future LuesDb vector search ----
        let vector_scored: Vec<ScoredBook> = Vec::new();

        // ---- Merge paths with fusion weights ----
        let mut merged: HashMap<i64, ScoredBook> = HashMap::new();

        for sb in rule_scored {
            let entry = merged.entry(sb.book_id).or_insert_with(|| ScoredBook {
                book_id: sb.book_id,
                final_score: 0.0,
                match_score: 0.0,
                vector_score: 0.0,
                recall_path: sb.recall_path.clone(),
            });
            entry.match_score = sb.final_score;
        }

        for sb in vector_scored {
            let entry = merged.entry(sb.book_id).or_insert_with(|| ScoredBook {
                book_id: sb.book_id,
                final_score: 0.0,
                match_score: 0.0,
                vector_score: 0.0,
                recall_path: sb.recall_path.clone(),
            });
            entry.vector_score = sb.final_score;
            entry.recall_path = sb.recall_path.clone();
        }

        for sb in explore_scored {
            let entry = merged.entry(sb.book_id).or_insert_with(|| ScoredBook {
                book_id: sb.book_id,
                final_score: 0.0,
                match_score: 0.0,
                vector_score: 0.0,
                recall_path: sb.recall_path.clone(),
            });
            // If already scored by RULE or VECTOR, skip explore score
            if entry.match_score > 0.0 || entry.vector_score > 0.0 {
                continue;
            }
            entry.recall_path = sb.recall_path.clone();
        }

        // Calculate final fused score
        for sb in merged.values_mut() {
            sb.final_score = (sb.match_score * fusion.weight_rule
                + sb.vector_score * fusion.weight_vector
                + if sb.match_score == 0.0 && sb.vector_score == 0.0 { sb.final_score } else { 0.0 } * fusion.weight_explore
            ).max(0.0).min(1.0);

            // If only explore path, use explore score with its weight
            if sb.match_score == 0.0 && sb.vector_score == 0.0 && sb.recall_path == "EXPLORE" {
                sb.final_score = (0.3 * fusion.weight_explore).max(0.0).min(1.0);
            }
        }

        let mut all_scored: Vec<ScoredBook> = merged.into_values().collect();
        all_scored.sort_by(|a, b| b.final_score.partial_cmp(&a.final_score).unwrap_or(std::cmp::Ordering::Equal));

        // MMR diversity
        Self::mmr_diversify(&mut all_scored, other_config.mmr_lambda, 300);

        Ok(all_scored)
    }

    // ==================== Helper Methods ====================

    fn is_excluded_by_preference(
        &self,
        book: &Book,
        excluded_tags: &[String],
        excluded_authors: &[String],
        excluded_formats: &[String],
    ) -> bool {
        if !excluded_formats.is_empty() {
            if let Some(ref format) = book.format {
                if excluded_formats.iter().any(|ef| format.eq_ignore_ascii_case(ef)) {
                    return true;
                }
            }
        }
        if !excluded_authors.is_empty() {
            if let Some(ref author) = book.author {
                if excluded_authors.iter().any(|ea| author.eq_ignore_ascii_case(ea)) {
                    return true;
                }
            }
        }
        if !excluded_tags.is_empty() {
            if let Some(ref tags_str) = book.format_tags {
                let book_tags: Vec<&str> = tags_str.split(',').map(|t| t.trim()).collect();
                for excluded_tag in excluded_tags {
                    let lower_excluded = excluded_tag.to_lowercase();
                    if book_tags.iter().any(|bt| bt.to_lowercase().contains(&lower_excluded)
                        || lower_excluded.contains(&bt.to_lowercase())) {
                        return true;
                    }
                }
            }
        }
        false
    }

    async fn get_explore_books(
        &self,
        pool: &SqlitePool,
        exclude_ids: &[i64],
        count: usize,
    ) -> Vec<Book> {
        // Use RANDOM() for exploration sampling
        if exclude_ids.is_empty() {
            sqlx::query_as::<_, Book>(
                "SELECT * FROM books ORDER BY RANDOM() LIMIT ?"
            )
            .bind(count as i32)
            .fetch_all(pool)
            .await
            .unwrap_or_default()
        } else {
            let placeholders: Vec<String> = exclude_ids.iter().enumerate()
                .map(|(i, _)| format!("?{}", i + 2))
                .collect();
            let sql = format!(
                "SELECT * FROM books WHERE id NOT IN ({}) ORDER BY RANDOM() LIMIT ?",
                placeholders.join(",")
            );
            let mut query = sqlx::query_as::<_, Book>(&sql);
            query = query.bind(count as i32);
            for id in exclude_ids {
                query = query.bind(*id);
            }
            query.fetch_all(pool).await.unwrap_or_default()
        }
    }

    async fn get_exclude_ids(&self, pool: &SqlitePool, user_id: i64) -> anyhow::Result<Vec<i64>> {
        let mut exclude = Vec::new();

        let read_ids: Vec<(i64,)> = sqlx::query_as(
            "SELECT DISTINCT book_id FROM reading_progress WHERE user_id = ?"
        ).bind(user_id).fetch_all(pool).await.unwrap_or_default();
        exclude.extend(read_ids.into_iter().map(|(id,)| id));

        let history_ids: Vec<(i64,)> = sqlx::query_as(
            "SELECT DISTINCT book_id FROM user_read_history WHERE user_id = ?"
        ).bind(user_id).fetch_all(pool).await.unwrap_or_default();
        exclude.extend(history_ids.into_iter().map(|(id,)| id));

        let trash_ids: Vec<(i64,)> = sqlx::query_as(
            "SELECT book_id FROM book_trash WHERE user_id = ?"
        ).bind(user_id).fetch_all(pool).await.unwrap_or_default();
        exclude.extend(trash_ids.into_iter().map(|(id,)| id));

        exclude.sort();
        exclude.dedup();
        Ok(exclude)
    }

    async fn get_user_preferences(
        &self,
        pool: &SqlitePool,
        user_id: i64,
    ) -> anyhow::Result<(Vec<String>, Vec<String>, Vec<String>, Vec<String>, Vec<String>, Vec<String>)> {
        let rows: Vec<(String, String, String)> = sqlx::query_as(
            "SELECT category, value, type FROM user_book_preference WHERE user_id = ? AND type IN ('INCLUDE', 'EXCLUDE')"
        ).bind(user_id).fetch_all(pool).await?;

        let mut included_tags = Vec::new();
        let mut included_authors = Vec::new();
        let mut included_formats = Vec::new();
        let mut excluded_tags = Vec::new();
        let mut excluded_authors = Vec::new();
        let mut excluded_formats = Vec::new();

        for (category, value, pref_type) in rows {
            match (category.as_str(), pref_type.as_str()) {
                ("TAG", "INCLUDE") => included_tags.push(value),
                ("TAG", "EXCLUDE") => excluded_tags.push(value),
                ("AUTHOR", "INCLUDE") => included_authors.push(value),
                ("AUTHOR", "EXCLUDE") => excluded_authors.push(value),
                ("FORMAT", "INCLUDE") => included_formats.push(value),
                ("FORMAT", "EXCLUDE") => excluded_formats.push(value),
                _ => {}
            }
        }

        Ok((included_tags, included_authors, included_formats, excluded_tags, excluded_authors, excluded_formats))
    }

    async fn score_hot_books(&self, pool: &SqlitePool) -> anyhow::Result<Vec<ScoredBook>> {
        let rows: Vec<(i64, Option<i64>, Option<f64>, Option<String>)> = sqlx::query_as(
            "SELECT b.id, b.read_count, b.rating, b.format_tags FROM books b"
        ).fetch_all(pool).await.unwrap_or_default();

        let ai_counts: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT book_id, COUNT(*) FROM ai_sessions GROUP BY book_id"
        ).fetch_all(pool).await.unwrap_or_default();
        let ai_map: HashMap<i64, i64> = ai_counts.into_iter().collect();

        let rt_counts: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT book_id, COUNT(*) FROM round_table_sessions GROUP BY book_id"
        ).fetch_all(pool).await.unwrap_or_default();
        let rt_map: HashMap<i64, i64> = rt_counts.into_iter().collect();

        let db_counts: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT book_id, COUNT(*) FROM debate_sessions GROUP BY book_id"
        ).fetch_all(pool).await.unwrap_or_default();
        let db_map: HashMap<i64, i64> = db_counts.into_iter().collect();

        let bs_counts: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT book_id, COUNT(*) FROM bookshelf GROUP BY book_id"
        ).fetch_all(pool).await.unwrap_or_default();
        let bs_map: HashMap<i64, i64> = bs_counts.into_iter().collect();

        let mut scored: Vec<ScoredBook> = rows.iter().map(|(id, read_count, rating, _tags)| {
            let rc = read_count.unwrap_or(0) as f64;
            let rt = rating.unwrap_or(0.0);
            let ai = *ai_map.get(id).unwrap_or(&0) as f64;
            let rtc = *rt_map.get(id).unwrap_or(&0) as f64;
            let dbc = *db_map.get(id).unwrap_or(&0) as f64;
            let bsc = *bs_map.get(id).unwrap_or(&0) as f64;

            let hotness = rc * 1.0 + ai * 2.0 + rtc * 2.5 + dbc * 3.0 + bsc * 1.5 + rt * 0.2;
            ScoredBook {
                book_id: *id,
                final_score: hotness,
                match_score: hotness,
                vector_score: 0.0,
                recall_path: "HOT".to_string(),
            }
        }).collect();

        scored.sort_by(|a, b| b.final_score.partial_cmp(&a.final_score).unwrap_or(std::cmp::Ordering::Equal));
        scored.truncate(300);

        for (rank, entry) in scored.iter_mut().enumerate() {
            entry.final_score = (0.95 - (rank as f64) * 0.003).max(0.05);
            entry.match_score = entry.final_score;
        }

        Ok(scored)
    }
}
