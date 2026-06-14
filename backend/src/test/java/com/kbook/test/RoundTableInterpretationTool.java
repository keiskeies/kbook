package com.kbook.test;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.entity.RoundTableMessage;
import com.kbook.entity.RoundTableSession;
import com.kbook.repository.BookRepository;
import com.kbook.repository.RoundTableMessageRepository;
import com.kbook.repository.RoundTableSessionRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;

/**
 * 圆桌派会话解读工具 — 用 LLM 深度解读某次圆桌派讨论
 * <p>
 * 像看完一期节目后写一篇回顾：这期聊了什么、精彩看点、角色表现、新知收获、整体评价。
 * 直接输出 markdown 风格分段文本，零解析。
 * <p>
 * 使用方式：修改下方 SESSION_ID，运行本测试即可。
 */
@SpringBootTest
@ActiveProfiles("dev")
public class RoundTableInterpretationTool {

    private static final Logger log = LoggerFactory.getLogger(RoundTableInterpretationTool.class);

    /** 修改此处为实际的圆桌派会话 sessionId */
    private static final String SESSION_ID = "rt-24662-4af5cbec";

    @Autowired
    private RoundTableSessionRepository sessionRepository;
    @Autowired
    private RoundTableMessageRepository messageRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private ChatModelFactory chatModelFactory;

    // ==================== 主入口 ====================

    @Test
    public void interpretRoundTable() {
        long t0 = System.currentTimeMillis();
        printBanner();

        // ---- 加载数据 ----
        log.info("[1/4] 加载会话数据...");
        long t1 = System.currentTimeMillis();
        RoundTableSession session = loadSession();
        Book book = bookRepository.findById(session.getBookId()).orElse(null);
        List<RoundTableMessage> messages = messageRepository.query()
                .where("sessionId", eq(SESSION_ID)).orderBy("id").list();
        System.out.printf("  ✔ 数据加载: %dms%n", System.currentTimeMillis() - t1);

        printSessionInfo(session, book, messages);
        if (messages.isEmpty()) {
            System.out.println("\n  ⚠ 该会话没有发言记录。");
            return;
        }

        // ---- 构建讨论文本 ----
        log.info("[2/4] 构建讨论文本...");
        long t2 = System.currentTimeMillis();
        String fullDiscussion = buildDiscussionText(messages);
        System.out.printf("  ✔ 文本构建: %dms  讨论: %d 字符, %d 条发言, %s%n",
                System.currentTimeMillis() - t2,
                fullDiscussion.length(), messages.size(), getRoleNames(messages));

        // ---- LLM 解读 ----
        log.info("[3/4] LLM 解读中...");
        long t3 = System.currentTimeMillis();
        String report = interpret(fullDiscussion, session, book, messages);
        long llmElapsed = System.currentTimeMillis() - t3;

        // ---- 输出 ----
        log.info("[4/4] 输出报告...");
        if (report != null) {
            System.out.printf("\n  ✔ LLM 解读完成: %dms (%.1f 分钟)%n", llmElapsed, llmElapsed / 60000.0);
            System.out.println("\n" + "=" .repeat(70));
            System.out.println("  解 读 报 告");
            System.out.println("=" .repeat(70) + "\n");
            System.out.println(report);
            System.out.println("\n" + "=" .repeat(70));
            System.out.println("  报告完毕");
            long total = System.currentTimeMillis() - t0;
            System.out.printf("  总用时: %dms (%.1f 分钟)%n", total, total / 60000.0);
            System.out.println("=" .repeat(70));
        } else {
            System.out.println("\n  ⚠ LLM 解读失败。");
        }
    }

    // ==================== LLM 解读 ====================

    private String interpret(String fullDiscussion, RoundTableSession session,
                             Book book, List<RoundTableMessage> messages) {
        String bookInfo = buildBookInfo(book);
        String roleInfo = buildRoleInfo(messages);

        return callLlm(String.format("""
                你是一位深谙中文谈话节目精髓的评论家，擅长从圆桌派这类多角色讨论中提炼精华。
                请对以下圆桌派讨论进行全面解读。

                要求：
                1. 按 7 个方面逐一详细解读，每个部分用「## 标题」格式分隔
                2. 引用具体发言作为例证，越详细越好
                3. 输出纯文本即可，不要 JSON

                解读框架：
                ## 壹、这期聊了什么 —— 概括核心主题，讨论的切入点是什么
                ## 贰、精彩看点 —— 最值得回味的段落：金句、名场面，为什么精彩
                ## 叁、角色表现 —— 每个角色的发挥如何，有没有高光时刻或意外表现
                ## 肆、观点碰撞 —— 谁和谁在哪儿杠上了，分歧本质是什么，哪方更有道理
                ## 伍、新知收获 —— 刷新认知的观点、冷知识或新视角
                ## 陆、意犹未尽 —— 哪些地方讨论得太浅，希望接着聊什么
                ## 柒、整体评价 —— 这期好看吗，适合什么类型的读者收听

                以下是本次讨论的数据：

                【图书信息】
                %s

                【角色说明】
                %s

                【讨论全文】
                %s
                """, bookInfo, roleInfo, fullDiscussion));
    }

    // ==================== LLM 调用 ====================

    private String callLlm(String prompt) {
        try {
            ChatModel model = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            return model != null ? model.chat(prompt) : null;
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private RoundTableSession loadSession() {
        List<RoundTableSession> sessions = sessionRepository.query()
                .where("sessionId", eq(SESSION_ID)).list(1);
        if (sessions.isEmpty()) throw new IllegalArgumentException("会话不存在: " + SESSION_ID);
        return sessions.get(0);
    }

    private void printBanner() {
        System.out.println("\n  ╔═══════════════════════════════════════╗");
        System.out.println("  ║    圆桌派 · LLM 深度解读工具         ║");
        System.out.println("  ╚═══════════════════════════════════════╝");
    }

    private void printSessionInfo(RoundTableSession session, Book book, List<RoundTableMessage> messages) {
        System.out.println("  会话: " + session.getSessionId() + "  角色: " + session.getRoleKeys());
        if (book != null) System.out.println("  图书: 《" + book.getTitle() + "》" + book.getAuthor());
        Map<String, List<RoundTableMessage>> byRole = messages.stream()
                .collect(Collectors.groupingBy(RoundTableMessage::getRoleKey));
        byRole.forEach((k, msgs) -> {
            String name = msgs.get(0).getRoleName();
            int chars = msgs.stream().mapToInt(m ->
                    (m.getCompressedContent() != null ? m.getCompressedContent() : m.getContent()).length()).sum();
            System.out.printf("    %s(%s): %d条 %d字符%n", k, name, msgs.size(), chars);
        });
    }

    private String buildBookInfo(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：《").append(book.getTitle()).append("》\n");
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append("作者：").append(book.getAuthor()).append("\n");
        }
        if (book.getFormatTags() != null && !book.getFormatTags().isBlank()) {
            String tags = book.getFormatTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("标签：").append(tags).append("\n");
        }
        if (book.getConceptTags() != null && !book.getConceptTags().isBlank()) {
            String concepts = book.getConceptTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("核心概念：").append(concepts).append("\n");
        }
        if (book.getReaderNeedTags() != null && !book.getReaderNeedTags().isBlank()) {
            String needs = book.getReaderNeedTags().replaceAll("[\\[\\]\"]", "").replace(",", "、");
            sb.append("读者关注：").append(needs).append("\n");
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append("简介：").append(book.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String buildRoleInfo(List<RoundTableMessage> messages) {
        Map<String, String> map = new LinkedHashMap<>();
        messages.forEach(m -> map.putIfAbsent(m.getRoleKey(), m.getRoleName()));
        return map.entrySet().stream().map(e -> "  " + e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining("\n"));
    }

    private String buildDiscussionText(List<RoundTableMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (RoundTableMessage msg : messages) {
            String content = msg.getCompressedContent() != null && !msg.getCompressedContent().isBlank()
                    ? msg.getCompressedContent() : msg.getContent();
            if (content != null && !content.isBlank())
                sb.append("【").append(msg.getRoleName()).append("】(第").append(msg.getRound()).append("轮)\n")
                        .append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String getRoleNames(List<RoundTableMessage> messages) {
        return messages.stream().map(RoundTableMessage::getRoleName).distinct().collect(Collectors.joining("、"));
    }
}
