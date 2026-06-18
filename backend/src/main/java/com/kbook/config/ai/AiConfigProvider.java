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
