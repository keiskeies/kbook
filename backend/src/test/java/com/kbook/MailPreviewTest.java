package com.kbook;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 邮件模板预览生成器（JUnit 测试方式运行）
 * <p>
 * 运行方式：IDEA 中右键此类 → Run 'MailPreviewTest'
 * 或命令行：mvn test -Dtest=MailPreviewTest -DfailIfNoTests=false
 */
public class MailPreviewTest {

    private static final String TEMPLATE_DIR = "src/main/resources/templates/mail";
    private static final String OUTPUT_DIR = "mail-preview";
    private static final String BASE_URL = "https://book.keiskei.top";

    @Test
    public void generateAllPreviews() throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_34);
        cfg.setDirectoryForTemplateLoading(Path.of(TEMPLATE_DIR).toFile());
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.HTML_DEBUG_HANDLER);

        Path outputDir = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputDir);

        String sendTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        // ---- 验证码 ----
        Map<String, Object> m = model(sendTime);
        m.put("sceneName", "登录"); m.put("sceneIcon", "🔑"); m.put("code", "384729"); m.put("expireMinutes", 5);
        render(cfg, "verification", m, outputDir);

        // ---- 邀请 ----
        m = model(sendTime);
        m.put("inviterName", "小明"); m.put("bookTitle", "三体");
        m.put("inviteLink", BASE_URL + "/invite/ABCD1234"); m.put("inviteCode", "ABCD1234");
        m.put("expireHours", 72); m.put("actionText", "接受邀请");
        render(cfg, "invitation", m, outputDir);

        // ---- 审核通过 ----
        m = model(sendTime); m.put("userName", "读者小王");
        render(cfg, "account-approved", m, outputDir);

        // ---- 封禁 ----
        m = model(sendTime); m.put("userName", "读者小王");
        render(cfg, "account-banned", m, outputDir);

        // ---- 解封 ----
        m = model(sendTime); m.put("userName", "读者小王");
        render(cfg, "account-unbanned", m, outputDir);

        // ---- 评论回复 ----
        m = model(sendTime);
        m.put("userName", "书友小李"); m.put("bookTitle", "百年孤独");
        m.put("content", "写得太好了！我读到奥雷里亚诺上校面对行刑队的那一刻，终于理解了马尔克斯想表达的孤独不是寂寞，而是人注定无法被他人完全理解的宿命感。");
        render(cfg, "comment-reply", m, outputDir);

        // ---- 评论点赞 ----
        m = model(sendTime);
        m.put("userName", "书友小李"); m.put("count", 42); m.put("bookTitle", "百年孤独");
        m.put("content", "写得太好了！我读到奥雷里亚诺上校面对行刑队的那一刻，终于理解了马尔克斯想表达的孤独不是寂寞，而是人注定无法被他人完全理解的宿命感。");
        m.put("actionText", "查看书评");
        render(cfg, "comment-like", m, outputDir);

        // ---- 书评里程碑 ----
        m = model(sendTime);
        m.put("thresholdType", "回复数"); m.put("thresholdValue", 100); m.put("bookTitle", "百年孤独");
        m.put("content", "写得太好了！我读到奥雷里亚诺上校面对行刑队的那一刻，终于理解了马尔克斯想表达的孤独不是寂寞，而是人注定无法被他人完全理解的宿命感。");
        m.put("actionText", "查看书评");
        render(cfg, "book-review-threshold", m, outputDir);

        // ---- 索引页 ----
        generateIndex(outputDir);

        System.out.println("\n✅ 全部邮件预览已生成: " + outputDir.toAbsolutePath());
        System.out.println("   浏览器打开: mail-preview/index.html");
    }

    private Map<String, Object> model(String sendTime) {
        Map<String, Object> m = new HashMap<>();
        m.put("baseUrl", BASE_URL);
        m.put("sendTime", sendTime);
        return m;
    }

    private void render(Configuration cfg, String name, Map<String, Object> data, Path dir) throws Exception {
        Template t = cfg.getTemplate(name + ".ftl");
        StringWriter sw = new StringWriter();
        t.process(data, sw);
        try (FileWriter fw = new FileWriter(dir.resolve(name + ".html").toFile())) {
            fw.write(sw.toString());
        }
        System.out.println("  ✔ " + name + ".html");
    }

    private void generateIndex(Path dir) throws Exception {
        String[][] list = {
                {"verification", "验证码邮件"},
                {"invitation", "邀请邮件"},
                {"account-approved", "审核通过通知"},
                {"account-banned", "账号封禁通知"},
                {"account-unbanned", "账号解封通知"},
                {"comment-reply", "评论回复通知"},
                {"comment-like", "评论点赞通知"},
                {"book-review-threshold", "书评里程碑通知"},
        };
        StringBuilder h = new StringBuilder("""
                <!DOCTYPE html><html><head><meta charset="UTF-8"><title>KBook 邮件预览</title>
                <style>body{font-family:'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;max-width:800px;margin:40px auto;padding:0 20px;background:#f0f2f5;}
                h1{color:#374151;}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px;}
                .card{display:block;background:#fff;border-radius:12px;padding:20px;text-decoration:none;box-shadow:0 1px 3px rgba(0,0,0,.08);transition:.2s;}
                .card:hover{box-shadow:0 4px 12px rgba(0,0,0,.12);transform:translateY(-2px);}
                .card h3{color:#1f2937;margin:0 0 4px;font-size:16px;}.card p{color:#6b7280;margin:0;font-size:13px;}</style></head>
                <body><h1>📧 KBook 邮件模板预览</h1><p style="color:#6b7280;margin-bottom:24px;">共 %d 个模板</p><div class="grid">
                """.formatted(list.length));
        for (String[] item : list) {
            h.append("<a class=\"card\" href=\"").append(item[0]).append(".html\"><h3>").append(item[1]).append("</h3><p>").append(item[0]).append(".ftl</p></a>\n");
        }
        h.append("</div></body></html>");
        Files.writeString(dir.resolve("index.html"), h.toString());
    }
}
