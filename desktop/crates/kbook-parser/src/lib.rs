pub mod epub;
pub mod pdf;
pub mod txt;

pub enum BookFormat {
    Epub,
    Pdf,
    Txt,
}

pub fn detect_format(path: &str) -> Option<BookFormat> {
    if path.ends_with(".epub") || path.ends_with(".EPUB") {
        Some(BookFormat::Epub)
    } else if path.ends_with(".pdf") || path.ends_with(".PDF") {
        Some(BookFormat::Pdf)
    } else if path.ends_with(".txt") || path.ends_with(".TXT") {
        Some(BookFormat::Txt)
    } else {
        None
    }
}

pub fn parse_book(path: &str) -> anyhow::Result<(String, String, String)> {
    let format = detect_format(path)
        .ok_or_else(|| anyhow::anyhow!("Unsupported format: {}", path))?;

    match format {
        BookFormat::Epub => epub::parse_epub(path),
        BookFormat::Pdf => pdf::parse_pdf(path),
        BookFormat::Txt => txt::parse_txt(path),
    }
}
