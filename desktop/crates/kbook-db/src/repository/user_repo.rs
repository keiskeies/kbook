use sqlx::SqlitePool;
use kbook_core::entity::User;

pub async fn find_by_id(pool: &SqlitePool, id: i64) -> anyhow::Result<Option<User>> {
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE id = ?")
        .bind(id)
        .fetch_optional(pool)
        .await?;
    Ok(user)
}

pub async fn find_by_email(pool: &SqlitePool, email: &str) -> anyhow::Result<Option<User>> {
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE email = ?")
        .bind(email)
        .fetch_optional(pool)
        .await?;
    Ok(user)
}

pub async fn create(
    pool: &SqlitePool,
    email: &str,
    password: &str,
    nickname: &str,
) -> anyhow::Result<User> {
    let result = sqlx::query(
        "INSERT INTO users (email, password, nickname) VALUES (?, ?, ?)"
    )
    .bind(email)
    .bind(password)
    .bind(nickname)
    .execute(pool)
    .await?;

    let user = find_by_id(pool, result.last_insert_rowid()).await?;
    Ok(user.unwrap())
}

pub async fn update_nickname(
    pool: &SqlitePool,
    user_id: i64,
    nickname: &str,
) -> anyhow::Result<()> {
    sqlx::query("UPDATE users SET nickname = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(nickname)
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_avatar(
    pool: &SqlitePool,
    user_id: i64,
    avatar: &str,
) -> anyhow::Result<()> {
    sqlx::query("UPDATE users SET avatar = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(avatar)
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_password(
    pool: &SqlitePool,
    user_id: i64,
    password: &str,
) -> anyhow::Result<()> {
    sqlx::query("UPDATE users SET password = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(password)
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn update_traits(
    pool: &SqlitePool,
    user_id: i64,
    birthday: Option<&str>,
    gender: Option<&str>,
    married: Option<bool>,
    has_children: Option<bool>,
    children_age_ranges: Option<&str>,
    mbti: Option<&str>,
    occupation: Option<&str>,
    education: Option<&str>,
    entrepreneurship: Option<&str>,
    annual_income: Option<&str>,
) -> anyhow::Result<()> {
    sqlx::query(
        "UPDATE users SET
            birthday = COALESCE(?, birthday),
            gender = COALESCE(?, gender),
            is_married = COALESCE(?, is_married),
            has_children = COALESCE(?, has_children),
            children_age_ranges = COALESCE(?, children_age_ranges),
            mbti = COALESCE(?, mbti),
            occupation = COALESCE(?, occupation),
            education = COALESCE(?, education),
            entrepreneurship = COALESCE(?, entrepreneurship),
            annual_income = COALESCE(?, annual_income),
            updated_at = datetime('now')
         WHERE id = ?"
    )
    .bind(birthday)
    .bind(gender)
    .bind(married)
    .bind(has_children)
    .bind(children_age_ranges)
    .bind(mbti)
    .bind(occupation)
    .bind(education)
    .bind(entrepreneurship)
    .bind(annual_income)
    .bind(user_id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn count(pool: &SqlitePool) -> anyhow::Result<i64> {
    let row: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users")
        .fetch_one(pool)
        .await?;
    Ok(row.0)
}
