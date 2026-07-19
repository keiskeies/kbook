package com.kbook.config.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 配置加载器 — 支持外部文件 + classpath 兜底 + 热重载
 *
 * <p>加载优先级（高→低）：
 * <ol>
 *   <li>环境变量 {@code KBOOK_AI_CONFIG_PATH} 或 spring 属性 {@code kbook.ai-config.path} 指定的路径</li>
 *   <li>classpath 默认文件 {@code config/ai-config.json}</li>
 * </ol>
 */
@Slf4j
@Service
public class AiConfigProvider {

    private final ObjectMapper objectMapper;
    private final String configPath;

    /** 当前生效的配置（volatile 保证 reload 后其它线程立即可见） */
    private volatile AiConfig config;

    public AiConfigProvider(
            ObjectMapper objectMapper,
            @Value("${kbook.ai-config.path:}") String configPath) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.configPath = configPath;
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    // ==================== 公开方法 ====================

    /** 获取完整配置对象 */
    public AiConfig getConfig() {
        return config;
    }

    /** 热重载：重新从文件加载配置 */
    public synchronized void reload() {
        loadConfig();
        log.info("AI 配置已热加载");
    }

    // ==================== 图书问答风格 ====================

    /** 获取默认风格 key */
    public String getDefaultChatStyle() {
        var styles = config.getBookChat();
        return styles != null ? styles.getDefaultStyle() : "DEEP";
    }

    /** 根据风格 key 查找提示词，找不到返回默认 */
    public String getChatStylePrompt(String styleKey) {
        var styles = config.getBookChat();
        if (styles == null || styles.getStyles() == null) return "";

        String key = styleKey != null ? styleKey.toUpperCase() : null;
        for (var s : styles.getStyles()) {
            if (s.getKey().equalsIgnoreCase(key)) {
                return s.getPrompt();
            }
        }
        // 兜底：返回默认风格
        String defaultKey = styles.getDefaultStyle();
        for (var s : styles.getStyles()) {
            if (s.getKey().equalsIgnoreCase(defaultKey)) {
                return s.getPrompt();
            }
        }
        return styles.getStyles().isEmpty() ? "" : styles.getStyles().get(0).getPrompt();
    }

    /** 获取所有风格列表 */
    public List<AiConfig.ChatStyle> getAllChatStyles() {
        var styles = config.getBookChat();
        if (styles == null) return List.of();
        return styles.getStyles() != null ? styles.getStyles() : List.of();
    }

    // ==================== 圆桌派角色 ====================

    /** 获取主持人配置 */
    public AiConfig.RoundTableHost getRoundTableHost() {
        var rt = config.getRoundTable();
        return rt != null ? rt.getHost() : null;
    }

    /** 获取所有嘉宾角色列表 */
    public List<AiConfig.RoundTableRole> getRoundTableRoles() {
        var rt = config.getRoundTable();
        if (rt == null) return List.of();
        return rt.getRoles() != null ? rt.getRoles() : List.of();
    }

    /** 根据 key 查找角色（HOST 在单独字段，需特殊处理） */
    public AiConfig.RoundTableRole getRoundTableRole(String key) {
        if ("HOST".equalsIgnoreCase(key)) {
            return hostToRole(config.getRoundTable() != null ? config.getRoundTable().getHost() : null);
        }
        return getRoundTableRoles().stream()
                .filter(r -> r.getKey().equalsIgnoreCase(key))
                .findFirst().orElse(null);
    }

    /** 将 RoundTableHost 转为 RoundTableRole（统一接口） */
    private AiConfig.RoundTableRole hostToRole(AiConfig.RoundTableHost host) {
        if (host == null) return null;
        AiConfig.RoundTableRole r = new AiConfig.RoundTableRole();
        r.setKey(host.getKey());
        r.setName(host.getName());
        r.setTitle(host.getTitle());
        r.setGroup(host.getGroup());
        r.setColor(host.getColor());
        r.setIcon(host.getIcon());
        r.setTts(host.getTts());
        r.setPrompt(host.getPrompt());
        r.setCatchphrase(host.getCatchphrase());
        r.setParams(host.getParams());
        return r;
    }

    /** 获取默认选中角色列表（含主持人） */
    public List<String> getRoundTableDefaultSelectedKeys() {
        var rt = config.getRoundTable();
        if (rt == null || rt.getSettings() == null) return List.of("HOST");
        return rt.getSettings().getDefaultSelectedKeys();
    }

    /** 获取每场最大角色数 */
    public int getRoundTableMaxRoles() {
        var rt = config.getRoundTable();
        if (rt == null || rt.getSettings() == null) return 20;
        return rt.getSettings().getMaxRolesPerSession();
    }

    /** 获取圆桌派完整角色列表（包含主持人，用于前端的精选列表） */
    public List<AiConfig.RoundTableRole> getRoundTableAllRoles() {
        List<AiConfig.RoundTableRole> all = new ArrayList<>();
        var rt = config.getRoundTable();
        if (rt == null) return all;
        // 主持人作为特殊角色，但返回时不混入，仅返回嘉宾
        if (rt.getRoles() != null) {
            all.addAll(rt.getRoles());
        }
        return all;
    }

    // ==================== 圆桌派角色列表构建 ====================

    /** 分组中文名映射 */
    private static final Map<String, String> GROUP_NAMES = Map.of(
            "CORE", "核心思辨组",
            "ART", "文艺视角组",
            "BUSINESS", "商业视角组",
            "LIFE", "生活视角组",
            "TECH", "技术/专业组",
            "SOCIAL", "社会/公共组"
    );

    /**
     * 从配置动态构建角色列表文本，用于 ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT_TEMPLATE。
     * 按分组输出，用 prompt 中的"身份与视角"第一句作为描述（比 title 更具体）。
     */
    public String buildRoundTableRoleListForPrompt() {
        List<AiConfig.RoundTableRole> roles = getRoundTableRoles();
        if (roles.isEmpty()) return "（无可用角色）";

        // 按 group 分组，保持插入顺序
        LinkedHashMap<String, List<AiConfig.RoundTableRole>> grouped = new LinkedHashMap<>();
        for (AiConfig.RoundTableRole role : roles) {
            String group = role.getGroup() != null ? role.getGroup() : "CORE";
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(role);
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (var entry : grouped.entrySet()) {
            String groupLabel = GROUP_NAMES.getOrDefault(entry.getKey(), entry.getKey());
            sb.append(groupLabel).append("：\n");
            for (AiConfig.RoundTableRole role : entry.getValue()) {
                // 第一行：序号 + key(中文名) - 身份描述
                sb.append(index).append(". ")
                  .append(role.getKey()).append("(").append(role.getName()).append(")");
                // 优先用 prompt 中的身份描述，比 title 更有辨识度
                String identity = extractIdentityFromPrompt(role.getPrompt());
                if (identity != null) {
                    sb.append(" - ").append(identity);
                } else if (role.getTitle() != null && !role.getTitle().isBlank()) {
                    sb.append(" - ").append(role.getTitle());
                }
                sb.append("\n");

                // 第二行：学科分支地图（从 prompt 提取，告诉 LLM 这个角色的理论工具箱覆盖范围）
                String branches = extractPromptSection(role.getPrompt(), "【学科分支地图】", 200);
                if (branches != null) {
                    sb.append("   分支：").append(branches).append("\n");
                }

                // 第三行：跨书类型适配（选角最关键——角色自己说明适合什么类型的书）
                String adaptation = extractPromptSection(role.getPrompt(), "【跨书类型适配】", 200);
                if (adaptation != null) {
                    sb.append("   擅长书类：").append(adaptation).append("\n");
                }

                // 第四行：标签（可直接和书的概念标签匹配）
                if (role.getTags() != null && !role.getTags().isEmpty()) {
                    sb.append("   标签：").append(String.join("、", role.getTags())).append("\n");
                }

                // 第五行：性格参数（让 LLM 考虑碰撞组合）
                AiConfig.RoleParams params = role.getParams();
                if (params != null) {
                    sb.append(String.format("   性格：挑战%d/5 共情%d/5 主见%d/5 话量%d/5 幽默%d/5%n",
                            params.getChallenge(), params.getEmpathy(),
                            params.getOpinionated(), params.getVerbosity(), params.getHumor()));
                }

                index++;
            }
        }
        return sb.toString();
    }

    /**
     * 从角色 prompt 中提取"身份与视角"段的第一句作为身份描述。
     * 格式：【身份与视角】你是XXX。...
     * 返回：XXX（如"概念拆解者"、"动机挖掘机"）
     */
    private String extractIdentityFromPrompt(String prompt) {
        if (prompt == null) return null;
        int markerStart = prompt.indexOf("【身份与视角】");
        if (markerStart < 0) return null;
        String after = prompt.substring(markerStart + "【身份与视角】".length()).trim();
        // 匹配 "你是XXX。" 或 "你是XXX，"
        if (after.startsWith("你是")) {
            after = after.substring("你是".length());
        }
        // 取到第一个句号或逗号
        int end = -1;
        for (int i = 0; i < Math.min(after.length(), 50); i++) {
            char c = after.charAt(i);
            if (c == '。' || c == '，' || c == ',' || c == '.') {
                end = i;
                break;
            }
        }
        if (end > 0) {
            return "你是" + after.substring(0, end).trim();
        }
        return null;
    }

    /**
     * 从角色 prompt 中提取指定【段落标记】后的内容，到下一个【标记】或字符串末尾。
     * <p>
     * 用于提取【学科分支地图】【跨书类型适配】等段落，给角色推荐 LLM 提供更丰富的选角依据。
     *
     * @param prompt     角色 prompt 原文
     * @param sectionTag 段落标记（如"【学科分支地图】"）
     * @param maxLength  最大提取长度（避免某段过长污染 prompt）
     * @return 段落正文（去除换行和多余空白），不存在则 null
     */
    private String extractPromptSection(String prompt, String sectionTag, int maxLength) {
        if (prompt == null || sectionTag == null) return null;
        int start = prompt.indexOf(sectionTag);
        if (start < 0) return null;
        String after = prompt.substring(start + sectionTag.length()).trim();
        // 找下一个【标记】作为段落结束
        int nextSection = after.indexOf("【");
        String section = nextSection > 0 ? after.substring(0, nextSection).trim() : after;
        // 压缩空白和换行，方便单行展示
        section = section.replaceAll("\\s+", " ").trim();
        if (section.isEmpty()) return null;
        return section.length() > maxLength ? section.substring(0, maxLength) + "…" : section;
    }

    // ==================== 奇葩说性格 ====================

    /** 获取主持人配置 */
    public AiConfig.DebateHost getDebateHost() {
        var dc = config.getDebate();
        return dc != null ? dc.getHost() : null;
    }

    /** 获取所有辩手性格列表 */
    public List<AiConfig.DebatePersonality> getDebatePersonalities() {
        var dc = config.getDebate();
        if (dc == null) return List.of();
        return dc.getPersonalities() != null ? dc.getPersonalities() : List.of();
    }

    /** 根据 key 查找性格 */
    public AiConfig.DebatePersonality getDebatePersonality(String key) {
        return getDebatePersonalities().stream()
                .filter(p -> p.getKey().equalsIgnoreCase(key))
                .findFirst().orElse(null);
    }

    // ==================== 摘要（管理端前端使用） ====================

    /** 生成配置摘要（不含完整 prompt，仅元信息） */
    public AiConfigSummary buildSummary() {
        return AiConfigSummary.builder()
                .bookChat(buildBookChatSummary())
                .roundTable(buildRoundTableSummary())
                .debate(buildDebateSummary())
                .build();
    }

    private AiConfigSummary.BookChatSummary buildBookChatSummary() {
        var styles = config.getBookChat();
        if (styles == null) return null;
        return AiConfigSummary.BookChatSummary.builder()
                .defaultStyle(styles.getDefaultStyle())
                .styles(styles.getStyles().stream()
                        .map(s -> AiConfigSummary.StyleItem.builder()
                                .key(s.getKey()).name(s.getName()).title(s.getTitle())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private AiConfigSummary.RoundTableSummary buildRoundTableSummary() {
        var rt = config.getRoundTable();
        if (rt == null) return null;

        var hostItem = AiConfigSummary.HostItem.builder()
                .key(rt.getHost().getKey())
                .name(rt.getHost().getName())
                .title(rt.getHost().getTitle())
                .color(rt.getHost().getColor())
                .icon(rt.getHost().getIcon())
                .params(rt.getHost().getParams())
                .catchphrase(rt.getHost().getCatchphrase())
                .build();

        List<AiConfigSummary.RoleItem> roleItems = rt.getRoles().stream()
                .map(r -> AiConfigSummary.RoleItem.builder()
                        .key(r.getKey()).name(r.getName()).title(r.getTitle())
                        .group(r.getGroup()).color(r.getColor()).icon(r.getIcon())
                        .params(r.getParams()).catchphrase(r.getCatchphrase())
                        .build())
                .collect(Collectors.toList());

        return AiConfigSummary.RoundTableSummary.builder()
                .host(hostItem)
                .maxRolesPerSession(rt.getSettings().getMaxRolesPerSession())
                .defaultSelectedKeys(rt.getSettings().getDefaultSelectedKeys())
                .roles(roleItems)
                .build();
    }

    private AiConfigSummary.DebateSummary buildDebateSummary() {
        var dc = config.getDebate();
        if (dc == null) return null;

        var hostItem = AiConfigSummary.HostItem.builder()
                .key(dc.getHost().getKey())
                .name(dc.getHost().getName())
                .title(dc.getHost().getTitle())
                .color(dc.getHost().getColor())
                .icon(dc.getHost().getIcon())
                .params(dc.getHost().getParams())
                .catchphrase(dc.getHost().getCatchphrase())
                .build();

        List<AiConfigSummary.PersonalityItem> personalityItems = dc.getPersonalities().stream()
                .map(p -> AiConfigSummary.PersonalityItem.builder()
                        .key(p.getKey()).name(p.getName()).title(p.getTitle())
                        .color(p.getColor()).icon(p.getIcon())
                        .params(p.getParams()).catchphrase(p.getCatchphrase())
                        .build())
                .collect(Collectors.toList());

        return AiConfigSummary.DebateSummary.builder()
                .host(hostItem)
                .personalities(personalityItems)
                .build();
    }

    // ==================== 内部加载逻辑 ====================

    private void loadConfig() {
        // 1. 外部路径（环境变量 / 启动参数）
        String resolvedPath = resolveConfigPath();
        if (resolvedPath != null && !resolvedPath.startsWith("classpath:")) {
            // 去掉可选的 "file:" 前缀，兼容 Windows（Paths.get 不认识 file: 前缀）
            String filePath = resolvedPath.startsWith("file:") ? resolvedPath.substring(5) : resolvedPath;
            Path path = Paths.get(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                try {
                    String json = Files.readString(path, StandardCharsets.UTF_8);
                    this.config = objectMapper.readValue(json, AiConfig.class);
                    log.info("AI 配置已从外部文件加载: {}", path.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    log.warn("外部配置文件读取失败，回退到 classpath 默认: {}", e.getMessage());
                }
            } else {
                log.warn("配置路径不存在，回退到 classpath 默认: {}", resolvedPath);
            }
        } else if (resolvedPath != null && resolvedPath.startsWith("classpath:")) {
            // 处理 classpath: 前缀的资源路径
            String classpathResource = resolvedPath.substring("classpath:".length());
            try (InputStream is = new ClassPathResource(classpathResource).getInputStream()) {
                this.config = objectMapper.readValue(is, AiConfig.class);
                log.info("AI 配置已从 classpath 资源加载: {}", resolvedPath);
                return;
            } catch (IOException e) {
                log.warn("classpath 资源读取失败，回退到默认路径: {}", e.getMessage());
            }
        }

        // 2. 兜底：classpath 默认
        try (InputStream is = new ClassPathResource("config/ai-config.json").getInputStream()) {
            this.config = objectMapper.readValue(is, AiConfig.class);
            log.info("AI 配置已从 classpath 默认加载");
        } catch (IOException e) {
            log.error("加载 classpath 默认配置失败！AI 功能将使用回退值", e);
            this.config = createFallbackConfig();
        }
    }

    /** 解析最终配置路径 */
    private String resolveConfigPath() {
        if (StringUtils.hasText(configPath)) {
            return configPath;
        }
        // 环境变量覆盖
        String envPath = System.getenv("KBOOK_AI_CONFIG_PATH");
        if (StringUtils.hasText(envPath)) {
            return envPath;
        }
        return null;
    }

    /** 创建兜底空配置（防止启动失败后 NPE） */
    private AiConfig createFallbackConfig() {
        AiConfig c = new AiConfig();

        AiConfig.BookChatConfig bc = new AiConfig.BookChatConfig();
        bc.setDefaultStyle("DEEP");
        bc.setStyles(new ArrayList<>());
        c.setBookChat(bc);

        AiConfig.RoundTableConfig rt = new AiConfig.RoundTableConfig();
        rt.setHost(new AiConfig.RoundTableHost());
        AiConfig.RoundTableSettings settings = new AiConfig.RoundTableSettings();
        settings.setMaxRolesPerSession(20);
        settings.setDefaultSelectedKeys(List.of("HOST"));
        rt.setSettings(settings);
        rt.setRoles(new ArrayList<>());
        c.setRoundTable(rt);

        AiConfig.DebateConfig dc = new AiConfig.DebateConfig();
        dc.setHost(new AiConfig.DebateHost());
        dc.setPersonalities(new ArrayList<>());
        c.setDebate(dc);

        return c;
    }
}
