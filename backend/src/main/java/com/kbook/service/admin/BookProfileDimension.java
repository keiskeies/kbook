package com.kbook.service.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读者画像维度定义：dimKey -> users 表原始取值表达式 + 归一化规则 + 合法 token 白名单 + 展示名。
 *
 * 分工：SQL 只负责把原始列值取出来（含未填写哨兵），年龄边界、大小写折叠、脏值判定全部在
 * {@link #toTokens(Object)} 里，保证「本书分布」与「全站基线」走同一套口径且可单测。
 * 取值表达式只引用 users 既有列、不接收任何外部输入，因此它同时充当 SQL 列白名单——
 * 看板的全站画像分布也走这份表达式取数（{@link BookProfileCalculator#dashboardDistribution}），
 * 不再有把列名当参数拼进 native SQL 的地方。
 */
public enum BookProfileDimension {

    /**
     * 性别。OTHER 必须收录：`User.gender` 的注释与注册引导页都把它当合法选项（onboarding.tsx 的
     * 「其他 ○」），漏掉的话选了「其他」的人在看板上会被算成脏值——填了却等于没填，属于静默错误。
     */
    GENDER("性别", raw("u.gender"), tokens("MALE", "男", "FEMALE", "女", "OTHER", "其他"), 0),

    AGE("年龄段", ageSql(), tokens(
            "<18", "18 岁以下",
            "18-24", "18-24 岁",
            "25-30", "25-30 岁",
            "31-40", "31-40 岁",
            "41-50", "41-50 岁",
            ">50", "50 岁以上"), 0),

    MBTI_TYPE("MBTI 类型", raw("u.mbti"), tokens(
            "INTJ", "INTJ", "INTP", "INTP", "ENTJ", "ENTJ", "ENTP", "ENTP",
            "INFJ", "INFJ", "INFP", "INFP", "ENFJ", "ENFJ", "ENFP", "ENFP",
            "ISTJ", "ISTJ", "ISFJ", "ISFJ", "ESTJ", "ESTJ", "ESFJ", "ESFJ",
            "ISTP", "ISTP", "ISFP", "ISFP", "ESTP", "ESTP", "ESFP", "ESFP"), 6),

    MBTI_EI("MBTI · 注意力方向", raw("u.mbti"), tokens("E", "外向 E", "I", "内向 I"), 0),

    MBTI_SN("MBTI · 信息获取", raw("u.mbti"), tokens("S", "实感 S", "N", "直觉 N"), 0),

    MBTI_TF("MBTI · 决策方式", raw("u.mbti"), tokens("T", "思考 T", "F", "情感 F"), 0),

    MBTI_JP("MBTI · 生活态度", raw("u.mbti"), tokens("J", "判断 J", "P", "知觉 P"), 0),

    MARITAL("婚姻状况", boolSql("u.is_married"), tokens("MARRIED", "已婚", "UNMARRIED", "未婚"), 0),

    CHILDREN("是否有孩子", boolSql("u.has_children"), tokens("HAS_CHILD", "有孩子", "NO_CHILD", "没有孩子"), 0),

    /** 逗号多值：一人可进多个桶；children_no_children 出现即视为无孩并丢弃同行其余年龄段 */
    CHILD_AGE("孩子年龄段", raw("u.children_age_ranges"), childAgeTokens(), 0, true),

    /** 逗号多值 + 分类/职位名混存，职位名先折叠进分类 */
    OCCUPATION("职业分类", raw("u.occupation"), tokens(
            "STUDENT", "学生",
            "TECH", "技术/IT",
            "FINANCE", "金融/商业",
            "EDUCATION", "教育/科研",
            "MEDICAL", "医疗/健康",
            "ARTS", "文艺/传媒",
            "MANAGEMENT", "管理/行政",
            "FREELANCE", "自由职业",
            "RETIRED", "退休",
            "OTHER", "其他"), 8, true),

    /**
     * ⚠️ 这两列不是「实际学历/实际收入」。问卷题干是「当前/目标学历」「当前/期望年收入」
     * （onboarding.tsx:379,417 与 ProfileTraitsSheet.tsx:262,272），一个字段里混着现状与期望，
     * 填报人自证不了是哪一种。label 里带上这个限定不是为了好看：它同时是看板列名和
     * {@code BookProfileCalculator} 拼进 AI 摘要的那句话，写「学历」等于让模型替用户断言其实际学历。
     * 在题干拆开之前，这两维的 lift 只能当弱证据，不足以支撑选书结论。
     */
    EDUCATION("学历（当前/目标）", raw("u.education"), tokens(
            "HIGH_SCHOOL", "高中及以下",
            "COLLEGE", "大专",
            "BACHELOR", "本科",
            "MASTER", "硕士",
            "DOCTORATE", "博士",
            "OTHER", "其他"), 0),

    /** 与 EDUCATION 同病：题干是「当前/期望年收入」，现状与期望混在一个字段里 */
    INCOME("年收入（当前/期望）", raw("u.annual_income"), tokens(
            "UNDER_50K", "5 万以内",
            "50K_150K", "5~15 万",
            "150K_300K", "15~30 万",
            "300K_500K", "30~50 万",
            "500K_1M", "50~100 万",
            "OVER_1M", "100 万以上",
            "PREFER_NOT_TO_SAY", "不方便说"), 0),

    ENTREPRENEURSHIP("创业意向", raw("u.entrepreneurship"),
            tokens("ENTREPRENEUR_OR_WANT", "正在创业/想创业", "NOT_INTERESTED", "暂不考虑"), 0),

    CHAT_STYLE("共读风格", raw("u.book_chat_style"), tokens(
            "DEEP", "深度分析",
            "CASUAL", "随和聊天",
            "CONCISE", "简洁直接",
            "WITTY", "幽默风趣"), 0);

    /** 未填写与脏值都不参与 lift，也不进反查 */
    public static final String UNKNOWN = "未填写";
    public static final String DIRTY = "脏值";
    /** 小样本并桶后的展示桶，恒定不展示 lift */
    public static final String OTHER = "其他";

    /** 实测 children_age_ranges 里存在该 token，且会与其余年龄段并存，语义上覆盖同行所有值 */
    private static final String CHILD_NO_CHILDREN_TOKEN = "CHILDREN_NO_CHILDREN";

    private static final int AXIS_LENGTH = 4;

    private final String label;
    private final String valueSql;
    private final Map<String, String> allowed;
    private final int topN;
    private final boolean multiValue;

    BookProfileDimension(String label, String valueSql, Map<String, String> allowed, int topN) {
        this(label, valueSql, allowed, topN, false);
    }

    BookProfileDimension(String label, String valueSql, Map<String, String> allowed, int topN, boolean multiValue) {
        this.label = label;
        this.valueSql = valueSql;
        this.allowed = Collections.unmodifiableMap(allowed);
        this.topN = topN;
        this.multiValue = multiValue;
    }

    public String getLabel() {
        return label;
    }

    /** 原始取值表达式，别名固定为 bucket，作用于别名 u（users 表） */
    public String getValueSql() {
        return valueSql;
    }

    /** 合法 token -> 展示名；归一化后仍不在表里的 token 一律并入脏值 */
    public Map<String, String> getAllowed() {
        return allowed;
    }

    /** >0 时只保留人数前 N 的桶，其余并入其他 */
    public int getTopN() {
        return topN;
    }

    /** 多值维度一人进多桶，各桶占比之和可 > 100%，UI 必须标注 */
    public boolean isMultiValue() {
        return multiValue;
    }

    /** 是否允许作为反查维度：多值维度与样本过薄的三维度（学历/收入/创业）不参与榜单 */
    public boolean isQueryable() {
        return !multiValue && this != EDUCATION && this != INCOME && this != ENTREPRENEURSHIP;
    }

    public static BookProfileDimension of(String dimKey) {
        if (dimKey == null) {
            return null;
        }
        for (BookProfileDimension dim : values()) {
            if (dim.name().equalsIgnoreCase(dimKey.trim())) {
                return dim;
            }
        }
        return null;
    }

    /**
     * 原始列值 -> 规范桶 token 列表。
     * 返回单元素 [未填写] 表示没填，[脏值] 表示填了但不在白名单，其余为可参与统计的桶。
     *
     * 白名单只收录当前 onboarding 仍在产出的取值 + 语义无歧义的历史别名。实测库里
     * education='SELF'、annual_income='MEDIUM'/'500K'、entrepreneurship='Startup' 属于早期版本
     * 遗留、如今已无对应选项，语义无法从代码还原，一律按脏值处理（计入 dirtyCount，
     * 在看板上以「取值不规范」暴露），不做猜测式归并。
     */
    public List<String> toTokens(Object rawValue) {
        String raw = rawValue == null ? "" : String.valueOf(rawValue).trim();
        if (raw.isEmpty() || UNKNOWN.equals(raw)) {
            return List.of(UNKNOWN);
        }
        if (multiValue) {
            List<String> tokens = new ArrayList<>();
            for (String part : raw.split(",")) {
                String token = singleToken(part.trim());
                if (token == null || UNKNOWN.equals(token) || DIRTY.equals(token)) {
                    continue;
                }
                if (CHILD_NO_CHILDREN_TOKEN.equals(token)) {
                    return List.of(token);
                }
                if (!tokens.contains(token)) {
                    tokens.add(token);
                }
            }
            return tokens.isEmpty() ? List.of(DIRTY) : tokens;
        }
        return List.of(singleToken(raw));
    }

    /** 单值维度用：无法识别返回脏值，空值返回未填写 */
    public String toToken(Object rawValue) {
        List<String> tokens = toTokens(rawValue);
        return tokens.get(0);
    }

    /**
     * 写入端校验：折叠成入库用的规范值，取值不合法返回 null。多值维度逐段校验，任一段非法即整体拒绝，
     * 合法段按输入顺序去重后用逗号重新拼接。
     *
     * 与读取端共用同一份白名单是刻意的：两处各维护一份枚举迟早会漂，而漂的后果正是「看板少一类人」
     * 这种静默错误（第 12 节那条「画像列没有取值校验」的另一半——脏数据进了库，展示端只能挡，挡不住统计变稀）。
     * 这里只给口径不抛异常：拒绝还是忽略是接口层的决定，注册与资料更新对空值的语义也不同。
     */
    public String canonicalForWrite(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String raw = rawValue.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (multiValue) {
            List<String> tokens = new ArrayList<>();
            for (String part : raw.split(",")) {
                String token = singleToken(part.trim());
                if (!allowed.containsKey(token)) {
                    return null;
                }
                if (!tokens.contains(token)) {
                    tokens.add(token);
                }
            }
            return tokens.isEmpty() ? null : String.join(",", tokens);
        }
        String token = singleToken(raw);
        return allowed.containsKey(token) ? token : null;
    }

    /** 归一化到白名单 token；不在白名单里返回脏值 */
    private String singleToken(String raw) {
        if (raw.isEmpty()) {
            return UNKNOWN;
        }
        String token = switch (this) {
            case GENDER -> upper(raw);
            case MARITAL -> boolToken(raw, "MARRIED", "UNMARRIED");
            case CHILDREN -> boolToken(raw, "HAS_CHILD", "NO_CHILD");
            case AGE -> ageToken(raw);
            case MBTI_TYPE -> mbtiOr(DIRTY, raw);
            case MBTI_EI -> axisToken(raw, 0);
            case MBTI_SN -> axisToken(raw, 1);
            case MBTI_TF -> axisToken(raw, 2);
            case MBTI_JP -> axisToken(raw, 3);
            case CHILD_AGE -> childAgeToken(raw);
            case OCCUPATION -> occupationToken(raw);
            case EDUCATION -> educationToken(raw);
            case INCOME -> incomeToken(raw);
            case ENTREPRENEURSHIP -> entrepreneurshipToken(raw);
            case CHAT_STYLE -> upper(raw);
        };
        if (UNKNOWN.equals(token) || DIRTY.equals(token)) {
            return token;
        }
        // 白名单在这里统一收口：折叠后的别名（XNTJ、{{7*7}}、全栈架构师）一律进脏值，
        // 新增维度只需写折叠规则，不必再各自记得查表。
        return allowed.containsKey(token) ? token : DIRTY;
    }

    /**
     * 「无孩」有两个历史写法：前端两处表单在 hasChildren=no 时填裸值 `no_children`，库里另有
     * `children_no_children`。前者不在白名单里，看板把它算成脏值——用户明确选了「没有孩子」，
     * 统计上却等于没填。语义无歧义，这里折叠成同一个 token，写入端与读取端共用一次判定。
     */
    private String childAgeToken(String raw) {
        String key = upper(raw);
        return "NO_CHILDREN".equals(key) ? CHILD_NO_CHILDREN_TOKEN : key;
    }

    /** 实测同一列并存分类 token 与职位名，职位名折叠进分类，否则 ENGINEER/Engineer/engineer 会占掉三个榜位 */
    private String occupationToken(String raw) {
        String key = upper(raw);
        if ("ENGINEER".equals(key) || "HACKER".equals(key) || "PROGRAMMER".equals(key)) {
            return "TECH";
        }
        return allowed.containsKey(key) ? key : DIRTY;
    }

    private String educationToken(String raw) {
        String key = upper(raw);
        if ("PHD".equals(key) || "PH.D".equals(key) || "DOCTOR".equals(key)) {
            return "DOCTORATE";
        }
        if ("HIGH_SCHOOL".equals(key) || "HIGHSCHOOL".equals(key) || "SENIOR_HIGH".equals(key)) {
            return "HIGH_SCHOOL";
        }
        return allowed.containsKey(key) ? key : DIRTY;
    }

    private String incomeToken(String raw) {
        String key = upper(raw).replace("¥", "").replace("元", "").trim();
        if ("100K".equals(key) || "10W".equals(key) || "10万".equals(key)) {
            return "50K_150K";
        }
        if ("UNDER1M".equals(key) || "1M".equals(key)) {
            return "OVER_1M";
        }
        return allowed.containsKey(key) ? key : DIRTY;
    }

    /** 实测该列同时存布尔串与枚举串，布尔折叠，枚举原样按白名单判定 */
    private String entrepreneurshipToken(String raw) {
        String key = upper(raw);
        if ("TRUE".equals(key) || "YES".equals(key) || "1".equals(key)) {
            return "ENTREPRENEUR_OR_WANT";
        }
        if ("FALSE".equals(key) || "NO".equals(key) || "0".equals(key)) {
            return "NOT_INTERESTED";
        }
        return allowed.containsKey(key) ? key : DIRTY;
    }

    private static String boolToken(String raw, String truthy, String falsy) {
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
            return truthy;
        }
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) {
            return falsy;
        }
        return DIRTY;
    }

    /** MBTI 四条轴共用原始列；长度不足 4 视为未填写（设计文档第 5 节） */
    private String axisToken(String mbti, int position) {
        String code = fourChar(mbti);
        if (code == null) {
            return UNKNOWN;
        }
        String axis = String.valueOf(code.charAt(position));
        return allowed.containsKey(axis) ? axis : DIRTY;
    }

    private String mbtiOr(String fallback, String mbti) {
        String code = fourChar(mbti);
        return code == null ? fallback : code;
    }

    /** 'INTJ-A' 归到 'INTJ'；不足 4 位返回 null 交给调用方决定是脏值还是未填写 */
    private static String fourChar(String mbti) {
        if (mbti.length() < AXIS_LENGTH) {
            return null;
        }
        return upper(mbti.substring(0, AXIS_LENGTH));
    }

    private static String ageToken(String rawAge) {
        int age;
        try {
            age = Integer.parseInt(rawAge);
        } catch (NumberFormatException e) {
            return DIRTY;
        }
        if (age < 0) {
            return DIRTY;
        }
        if (age < 18) {
            return "<18";
        }
        if (age <= 24) {
            return "18-24";
        }
        if (age <= 30) {
            return "25-30";
        }
        if (age <= 40) {
            return "31-40";
        }
        if (age <= 50) {
            return "41-50";
        }
        return ">50";
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> tokens(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(upper(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** 枚举常量参数里不能直接前向引用静态字段，因此该维度的白名单走方法构造 */
    private static Map<String, String> childAgeTokens() {
        return tokens(
                "CHILDREN_0_2", "0-2 岁",
                "CHILDREN_3_6", "3-6 岁",
                "CHILDREN_7_12", "7-12 岁",
                "CHILDREN_13_17", "13-17 岁",
                "CHILDREN_18_PLUS", "18 岁以上",
                CHILD_NO_CHILDREN_TOKEN, "对外填「无孩」");
    }

    private static String raw(String column) {
        return "COALESCE(NULLIF(TRIM(" + column + "), ''), '" + UNKNOWN + "')";
    }

    /**
     * 布尔列保留 NULL 语义，交给归一化判定。
     *
     * ⚠️ is_married / has_children 在库里是 BIT(1)：`CAST(bit AS CHAR)` 得到的是 0x01/0x00 原始字节
     * （实测 true 显示成空串、false 显示成 \0），Java 侧 String.trim() 会把它们剪成空串，
     * 于是「填了」的人全被判成未填写。必须先 CAST AS UNSIGNED 再 CAST AS CHAR 拿到 '1'/'0'。
     */
    private static String boolSql(String column) {
        return "CASE WHEN " + column + " IS NULL THEN '" + UNKNOWN
                + "' ELSE CAST(CAST(" + column + " AS UNSIGNED) AS CHAR) END";
    }

    /** 生日为空的行留在未填写桶；未来日期是脏数据 */
    private static String ageSql() {
        return "CASE WHEN u.birthday IS NULL THEN '" + UNKNOWN + "'"
                + " WHEN u.birthday > CURDATE() THEN '-1'"
                + " ELSE CAST(TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) AS CHAR) END";
    }
}
