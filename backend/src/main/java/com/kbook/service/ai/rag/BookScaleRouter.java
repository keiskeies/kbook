package com.kbook.service.ai.rag;

import com.kbook.config.properties.BookQaProperties;
import com.kbook.entity.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按书规模选择上下文供给方式（三档路由）。
 * <p>
 * 判据来自用户：{@code 书字数 << 模型上下文 − 历史问答 − 输出预留} 时整本直塞，
 * 优于检索且能复用 provider 前缀缓存。实现见
 * {@code docs/plans/book-qa-scale-aware-routing-plan.md} §2。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookScaleRouter {

    /** 系统提示 + 画像 + 问题包装 + 回复预留的保守估算（静态/动态语料长度另传实际值） */
    public static final int FIXED_OVERHEAD_CHARS = 2500;

    private final BookQaProperties properties;
    private final ContextBudget contextBudget;
    private final FullTextContextBuilder fullTextContextBuilder;

    /** 会话级档位锁：重启后失效无妨（此时 provider 缓存本来也是冷的） */
    private final ConcurrentMap<String, ScaleDecision> sessionLocks = new ConcurrentHashMap<>();

    public record ScaleDecision(ContextMode mode, int bookChars, int availableChars,
                                String reason, FullTextContextBuilder.ChapterContext chapter) {
    }

    /**
     * @param corpusChars  静态语料 + 动态元信息的实际字符数
     * @param historyChars 当前会话历史（压缩后）的字符数
     */
    public ScaleDecision decide(Book book, String sessionId, String question,
                                int corpusChars, int historyChars) {
        if (!properties.isScaleRoutingEnabled()) {
            return new ScaleDecision(ContextMode.LARGE, 0, 0, "disabled", null);
        }
        String fullText = book.getFullText();
        int bookChars = fullText == null ? 0 : fullText.length();
        if (bookChars == 0) {
            return new ScaleDecision(ContextMode.LARGE, 0, 0, "no_full_text", null);
        }
        if (bookChars > properties.getFullTextMaxChars()) {
            return new ScaleDecision(ContextMode.LARGE, bookChars, 0, "exceeds_hard_cap", null);
        }

        if (properties.isSessionLockEnabled()) {
            ScaleDecision locked = sessionLocks.get(sessionId);
            if (locked != null) {
                log.debug("[ScaleRouter] 会话档位锁定: session={}, mode={}", sessionId, locked.mode());
                return locked;
            }
        }

        int overhead = FIXED_OVERHEAD_CHARS + Math.max(0, corpusChars);
        ContextBudget.Budget budget = contextBudget.resolve(overhead, historyChars);
        int smallThreshold = (int) (budget.availableChars() * properties.getSmallBookRatio());

        ScaleDecision decision;
        if (bookChars <= smallThreshold) {
            decision = new ScaleDecision(ContextMode.SMALL, bookChars, budget.availableChars(),
                    "small_book", null);
        } else {
            FullTextContextBuilder.ChapterContext chapter =
                    fullTextContextBuilder.resolveChapter(book, question, budget.availableChars());
            if (chapter != null) {
                decision = new ScaleDecision(ContextMode.MEDIUM, bookChars, budget.availableChars(),
                        "chapter_located", chapter);
            } else {
                decision = new ScaleDecision(ContextMode.LARGE, bookChars, budget.availableChars(),
                        "requires_retrieval", null);
            }
        }

        log.info("[ScaleRouter] bookId={}, session={}, bookChars={}, ctx={}tok, out={}tok, "
                        + "available={}chars, smallThreshold={} → mode={}, reason={}",
                book.getId(), sessionId, bookChars, budget.contextTokens(), budget.outputTokens(),
                budget.availableChars(), smallThreshold, decision.mode(), decision.reason());

        if (properties.isSessionLockEnabled()) {
            putLock(sessionId, decision);
        }
        return decision;
    }

    /** 会话结束 / 切换书籍时释放锁定；主链路在 regenerate 时也会复用同一会话，无需释放。 */
    public void release(String sessionId) {
        if (sessionId != null) sessionLocks.remove(sessionId);
    }

    private void putLock(String sessionId, ScaleDecision decision) {
        int max = properties.getSessionLockMaxSize();
        if (max > 0 && sessionLocks.size() >= max) {
            int toRemove = Math.max(1, max / 2);
            var it = sessionLocks.keySet().iterator();
            while (toRemove-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        sessionLocks.put(sessionId, decision);
    }
}
