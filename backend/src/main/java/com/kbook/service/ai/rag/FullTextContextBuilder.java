package com.kbook.service.ai.rag;

import com.kbook.config.properties.BookQaProperties;
import com.kbook.entity.Book;
import com.kbook.service.book.BookStructureService;
import com.kbook.service.book.TextStructureExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 {@code books.full_text} 直接构造静态书籍语料。
 * <p>
 * 优先使用持久化的全书原文，而不是从 Qdrant 拼 chunk：后者有 200 字 overlap、
 * ContentQualityGate 丢块、MAX_FULL_SCAN_CHUNKS 截断三处失真，无法 1:1 还原原文。
 * 章节结构由 {@link TextStructureExtractor} 从全文现算并缓存；P2 的 structure_json
 * 落库只是把这份计算结果持久化，不改变语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullTextContextBuilder {

    /** 「第X章 / Chapter N」形式的显式章节引用 */
    private static final Pattern EXPLICIT_CHAPTER = Pattern.compile(
            "第\\s*([一二三四五六七八九十百千零\\d]+)\\s*[章节卷部篇]");
    /** 标题与问题的 bigram 重合率门槛 */
    private static final double TITLE_MATCH_THRESHOLD = 0.34;
    private static final int CHAPTER_CACHE_MAX = 200;

    private final BookQaProperties properties;
    private final BookStructureService bookStructureService;

    private final ConcurrentMap<String, List<TextStructureExtractor.Chapter>> chapterCache =
            new ConcurrentHashMap<>();

    /** 定位到的章节原文及其出处。 */
    public record ChapterContext(String text, String title, int index, int startChar, int endChar) {
    }

    /** SMALL 档：静态语料 + 全书原文。同一本书内容恒定，可直接作为缓存前缀。 */
    public String buildSmallCorpus(String staticCorpus, Book book) {
        return staticCorpus + "\n【全书原文】\n" + book.getFullText() + "\n";
    }

    /** MEDIUM 档：静态语料 + 定位到的整章原文。 */
    public String buildMediumCorpus(String staticCorpus, ChapterContext chapter) {
        return staticCorpus + "\n【相关章节原文｜" + chapter.title() + "】\n" + chapter.text() + "\n";
    }

    /**
     * 尝试为问题定位一个可整章直塞的章节。找不到 / 章节超预算时返回 {@code null}，
     * 由路由降级到 LARGE（向量检索），保证「不比现状差」。
     */
    public ChapterContext resolveChapter(Book book, String question, int availableChars) {
        String fullText = book.getFullText();
        if (fullText == null || fullText.isBlank()) return null;
        List<TextStructureExtractor.Chapter> chapters = chaptersOf(book, fullText);
        int limit = Math.min(properties.getMediumChapterMaxChars(), availableChars);
        if (limit <= 0) return null;
        if (chapters.size() <= 1) {
            // 正文标题必须「独立成行 + 前置空行」才会被 TextStructureExtractor 识别；
            // 解析质量一般的书常不满足。改用 books.toc 的标题在全文里直接定位。
            return resolveByToc(book, fullText, question, limit);
        }

        TextStructureExtractor.Chapter best = pickChapter(chapters, question);
        if (best == null) return null;

        int start = Math.max(0, Math.min(best.startChar(), fullText.length()));
        int end = Math.max(start, Math.min(best.endChar(), fullText.length()));
        String text = fullText.substring(start, end);
        if (text.isBlank()) return null;

        if (text.length() > limit) {
            log.debug("[ScaleRouter] 章节 {} 长度 {} 超过预算 {}，降级检索",
                    best.title(), text.length(), limit);
            return null;
        }
        return new ChapterContext(text, best.title() == null ? "第" + best.index() + "节" : best.title(),
                best.index(), start, end);
    }

    private List<TextStructureExtractor.Chapter> chaptersOf(Book book, String fullText) {
        // 索引阶段已把结构写进 books.structure_json，优先直接用，避免每次重新扫描全书
        List<TextStructureExtractor.Chapter> persisted = bookStructureService.parse(book.getStructureJson());
        if (!persisted.isEmpty()) return persisted;
        if (chapterCache.size() > CHAPTER_CACHE_MAX) chapterCache.clear();
        String key = book.getId() + ":" + fullText.length() + ":" + fullText.hashCode();
        return chapterCache.computeIfAbsent(key, k -> TextStructureExtractor.extract(fullText));
    }

    /** 先按显式章节号，再按标题重合度；都没有则放弃整章直塞。 */
    private TextStructureExtractor.Chapter pickChapter(
            List<TextStructureExtractor.Chapter> chapters, String question) {
        if (question == null || question.isBlank()) return null;
        Matcher m = EXPLICIT_CHAPTER.matcher(question);
        if (m.find()) {
            int ordinal = parseChineseNumber(m.group(1));
            if (ordinal > 0) {
                for (TextStructureExtractor.Chapter c : chapters) {
                    if (c.index() == ordinal) return c;
                }
                // 章节编号与识别出的序号不一定同起点，退而取最接近的一章
                if (ordinal <= chapters.size()) return chapters.get(ordinal - 1);
            }
        }
        TextStructureExtractor.Chapter best = null;
        double bestScore = TITLE_MATCH_THRESHOLD;
        for (TextStructureExtractor.Chapter c : chapters) {
            if (c.title() == null || c.title().isBlank()) continue;
            double score = titleScore(question, c.title());
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /**
     * 目录回退定位：用 {@code books.toc} 里的标题在全书原文中找起点，
     * 下一个目录标题（或全文末尾）作为终点。适用于正文章节没有空行、
     * {@link TextStructureExtractor} 识别不出结构的书。
     */
    private ChapterContext resolveByToc(Book book, String fullText, String question, int limit) {
        String toc = book.getToc();
        if (toc == null || toc.isBlank()) return null;
        List<String> titles = toc.lines()
                .map(String::trim)
                .filter(s -> !s.isBlank() && s.length() <= 80)
                .distinct()
                .toList();
        if (titles.isEmpty()) return null;

        String chosen = null;
        double bestScore = TITLE_MATCH_THRESHOLD;
        for (String title : titles) {
            double score = titleScore(question, title);
            if (score > bestScore) {
                bestScore = score;
                chosen = title;
            }
        }
        if (chosen == null) return null;
        int start = fullText.indexOf(chosen);
        if (start < 0) return null;

        int end = fullText.length();
        for (String title : titles) {
            if (title.equals(chosen)) continue;
            int pos = fullText.indexOf(title, start + chosen.length());
            if (pos > start && pos < end) end = pos;
        }
        String text = fullText.substring(start, end);
        if (text.isBlank() || text.length() > limit) return null;
        log.debug("[ScaleRouter] 目录回退定位章节: title={}, chars={}", chosen, text.length());
        return new ChapterContext(text, chosen, 0, start, end);
    }

    private static double titleScore(String question, String title) {
        // 去掉「第X章」序数前缀后，剩余核心词完整出现在问题里 → 强命中。
        // 例如标题「第二章 正文」遇「请讲讲正文章节的内容」，bigram 只有 0.25 但语义明确。
        String core = title.replaceAll("^第[一二三四五六七八九十百千万零\\d]+[章节卷部篇]\\s*", "")
                .replaceAll("^Chapter\\s+\\d+\\s*", "").trim();
        if (core.length() >= 2 && question.contains(core)) return 1.0;
        Set<String> q = bigrams(question);
        Set<String> t = bigrams(title);
        if (q.isEmpty() || t.isEmpty()) return 0;
        long hit = t.stream().filter(q::contains).count();
        return (double) hit / t.size();
    }

    private static Set<String> bigrams(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> out = new HashSet<>();
        if (s.length() < 2) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i < s.length() - 1; i++) out.add(s.substring(i, i + 2));
        return out;
    }

    private static int parseChineseNumber(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        if (raw.matches("\\d+")) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (char c : raw.toCharArray()) {
            int digit = switch (c) {
                case '零' -> 0;
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                number = digit;
            } else {
                int unit = switch (c) {
                    case '十' -> 10;
                    case '百' -> 100;
                    case '千' -> 1000;
                    default -> -1;
                };
                if (unit > 0) {
                    section += (number == 0 ? 1 : number) * unit;
                    number = 0;
                }
            }
        }
        return total + section + number;
    }
}
