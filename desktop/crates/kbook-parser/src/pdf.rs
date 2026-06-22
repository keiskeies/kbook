pub fn parse_pdf(path: &str) -> anyhow::Result<(String, String, String)> {
    let content = std::fs::read_to_string(path)?;
    Ok((content, String::new(), String::new()))
}
