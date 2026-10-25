package com.kbook.service.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * 书侧 {@code target_reader_tags} 到读者画像维度桶的规则表。
 *
 * 实测该列有 19797 个不同取值、其中 70% 只出现一次（设计文档附录 E.2），所以只能按关键词映射，
 * 不能枚举字典——字典覆盖不到尾巴，规则能覆盖「政治学学生」「科幻小说读者」这类组合词。
 *
 * 分类互斥且五选一：只有 AUDIENCE 参与吻合度计算，其余四类都算「未映射」，但原因要分开记——
 * 「题材兴趣」指向的是 users 侧根本没有对应列，「现有桶表达不了」指向的是要加分桶，两者是不同的后续工作。
 * 不允许静默落进某个桶，那正是 P2 里 {@code {{7*7}}} 变成真实分桶的那类 bug。
 */
public final class TargetAudienceRules {

    public enum Kind {
        /** 能落到画像桶的身份标签，如「学生」「女性读者」「家长」 */
        AUDIENCE,
        /** 题材/兴趣，users 侧没有对应字段，如「历史爱好者」「中医爱好者」 */
        INTEREST,
        /** 泛化到等于没说，如「普通读者」「普通大众」 */
        GENERIC,
        /** 身份明确但现有桶表达不了，如「律师」「上班族」「科技从业者」 */
        NO_BUCKET,
        /** 规则没覆盖到，进未映射统计 */
        UNRECOGNIZED
    }

    /** 一个维度桶引用，token 必须是 {@link BookProfileDimension} 白名单里的值 */
    public record Bucket(BookProfileDimension dim, String token) {
    }

    public record Classification(Kind kind, List<Bucket> buckets) {
        static Classification of(Kind kind) {
            return new Classification(kind, List.of());
        }

        static Classification audience(List<Bucket> buckets) {
            return new Classification(Kind.AUDIENCE, List.copyOf(buckets));
        }
    }

    private record Rule(String keyword, BookProfileDimension dim, String token) {
    }

    /** 人口学身份：这些词在读者侧是列值，在书侧几乎总是「给谁看」 */
    private static final List<Rule> DEMOGRAPHIC = List.of(
            new Rule("女", BookProfileDimension.GENDER, "FEMALE"),
            new Rule("妇女", BookProfileDimension.GENDER, "FEMALE"),
            new Rule("男性", BookProfileDimension.GENDER, "MALE"),
            new Rule("男士", BookProfileDimension.GENDER, "MALE"),
            new Rule("爸爸", BookProfileDimension.GENDER, "MALE"),
            new Rule("父亲", BookProfileDimension.GENDER, "MALE"),
            new Rule("主妇", BookProfileDimension.GENDER, "FEMALE"),
            new Rule("儿童", BookProfileDimension.AGE, "<18"),
            new Rule("幼儿", BookProfileDimension.AGE, "<18"),
            new Rule("婴儿", BookProfileDimension.AGE, "<18"),
            new Rule("少年", BookProfileDimension.AGE, "<18"),
            new Rule("未成年", BookProfileDimension.AGE, "<18"),
            new Rule("小学生", BookProfileDimension.AGE, "<18"),
            new Rule("中学生", BookProfileDimension.AGE, "<18"),
            new Rule("青年", BookProfileDimension.AGE, "18-24"),
            new Rule("年轻", BookProfileDimension.AGE, "18-24"),
            new Rule("大学生", BookProfileDimension.AGE, "18-24"),
            new Rule("中年", BookProfileDimension.AGE, "41-50"),
            new Rule("老年", BookProfileDimension.AGE, ">50"),
            new Rule("银发", BookProfileDimension.AGE, ">50"),
            new Rule("退休", BookProfileDimension.AGE, ">50"),
            new Rule("已婚", BookProfileDimension.MARITAL, "MARRIED"),
            new Rule("新婚", BookProfileDimension.MARITAL, "MARRIED"),
            new Rule("夫妻", BookProfileDimension.MARITAL, "MARRIED"),
            new Rule("单身", BookProfileDimension.MARITAL, "UNMARRIED"),
            new Rule("未婚", BookProfileDimension.MARITAL, "UNMARRIED"),
            new Rule("家长", BookProfileDimension.CHILDREN, "HAS_CHILD"),
            new Rule("父母", BookProfileDimension.CHILDREN, "HAS_CHILD"),
            new Rule("亲子", BookProfileDimension.CHILDREN, "HAS_CHILD"),
            new Rule("爸", BookProfileDimension.CHILDREN, "HAS_CHILD"),
            new Rule("妈", BookProfileDimension.CHILDREN, "HAS_CHILD"),
            new Rule("孕育", BookProfileDimension.CHILDREN, "HAS_CHILD"));

    /**
     * 含亲属关系词时，标签说的是「谁的家长」而不是「谁的从业者」。
     * 「学生家长」若继续叠加职业规则，会把 STUDENT 也算进声称人群，而实际声称的是 HAS_CHILD。
     */
    private static final List<String> KINSHIP = List.of("家长", "父母", "亲子", "爸", "妈", "孩子", "孕");

    /** 泛化词必须挡在「读者→兴趣」之前，否则「普通读者」会被算成兴趣 */
    private static final List<String> GENERIC = List.of(
            "普通读者", "普通大众", "大众", "所有人", "任何人", "一般读者", "所有读者",
            "成年读者", "成人读者", "成年人", "普通用户", "普罗大众", "所有人群");

    /**
     * 职业只收「人称名词」，不收领域词。
     * 写「金融」「技术」这类领域词会把「金融爱好者」判成 AUDIENCE，而它表达的是题材兴趣，
     * 与读者侧的「职业分类=金融/商业」不是同一件事，会让吻合度虚高。
     */
    private static final List<Rule> OCCUPATION = List.of(
            new Rule("学生", BookProfileDimension.OCCUPATION, "STUDENT"),
            new Rule("研究生", BookProfileDimension.OCCUPATION, "STUDENT"),
            new Rule("在校生", BookProfileDimension.OCCUPATION, "STUDENT"),
            new Rule("教师", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("老师", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("教育工作者", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("讲师", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("教授", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("学者", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("研究者", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("研究人员", BookProfileDimension.OCCUPATION, "EDUCATION"),
            new Rule("程序员", BookProfileDimension.OCCUPATION, "TECH"),
            new Rule("工程师", BookProfileDimension.OCCUPATION, "TECH"),
            new Rule("开发者", BookProfileDimension.OCCUPATION, "TECH"),
            new Rule("技术人员", BookProfileDimension.OCCUPATION, "TECH"),
            new Rule("技术从业者", BookProfileDimension.OCCUPATION, "TECH"),
            new Rule("金融从业", BookProfileDimension.OCCUPATION, "FINANCE"),
            new Rule("金融人士", BookProfileDimension.OCCUPATION, "FINANCE"),
            new Rule("银行职员", BookProfileDimension.OCCUPATION, "FINANCE"),
            new Rule("投资者", BookProfileDimension.OCCUPATION, "FINANCE"),
            new Rule("投资人", BookProfileDimension.OCCUPATION, "FINANCE"),
            new Rule("医生", BookProfileDimension.OCCUPATION, "MEDICAL"),
            new Rule("医师", BookProfileDimension.OCCUPATION, "MEDICAL"),
            new Rule("护士", BookProfileDimension.OCCUPATION, "MEDICAL"),
            new Rule("医务工作者", BookProfileDimension.OCCUPATION, "MEDICAL"),
            new Rule("心理咨询师", BookProfileDimension.OCCUPATION, "MEDICAL"),
            new Rule("作家", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("编剧", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("设计师", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("记者", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("编辑", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("传媒人", BookProfileDimension.OCCUPATION, "ARTS"),
            new Rule("管理者", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("经理", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("主管", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("高管", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("公务员", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("政策制定者", BookProfileDimension.OCCUPATION, "MANAGEMENT"),
            new Rule("退休", BookProfileDimension.OCCUPATION, "RETIRED"),
            new Rule("自由职业", BookProfileDimension.OCCUPATION, "FREELANCE"),
            new Rule("创业者", BookProfileDimension.ENTREPRENEURSHIP, "ENTREPRENEUR_OR_WANT"),
            new Rule("企业家", BookProfileDimension.ENTREPRENEURSHIP, "ENTREPRENEUR_OR_WANT"));

    /** 身份明确但画像桶表达不了，单独记一类，别混进「未识别」 */
    private static final List<String> NO_BUCKET = List.of(
            "律师", "法务", "上班族", "白领", "蓝领", "职场", "农民工", "工人", "农民",
            "士兵", "军人", "志愿者", "信徒", "教徒", "患者", "病人", "情侣", "恋人", "团队");

    /**
     * 人称后缀：形如「科技从业者」「人力资源从业者」「销售人员」的职业声称。
     * 它们确实是人群，但现有职业分桶只有 10 个大类，硬塞进 OTHER 会让吻合度拿「其他」比「其他」，
     * 所以记成 NO_BUCKET——这类词的规模就是要不要细化职业枚举的证据。
     */
    private static final List<String> PERSON_SUFFIX = List.of(
            "从业者", "工作者", "人士", "人员", "职员", "专员");

    /** 兴趣/题材后缀：命中即 INTEREST，因为 users 侧没有「读什么题材」这一列 */
    private static final List<String> INTEREST_SUFFIX = List.of(
            "爱好者", "发烧友", "迷", "粉丝", "读者", "书友", "关注者", "思考者");

    private record Rewrite(String from, String to) {
    }

    /**
     * 「初学者」含有子串「学者」，直接匹配会把 227 个「编程初学者」「理财初学者」之类的入门标签
     * 判成教育/科研人群（实测见设计文档附录 E.4）——入门表达的是熟练度，画像里没有这一列，
     * 所以先把这个词改写成人称无关的中性词，让它落进未映射而不是假分成 AUDIENCE。
     */
    private static final List<Rewrite> REWRITE = List.of(
            new Rewrite("初学者", "新手"));

    private TargetAudienceRules() {
    }

    /** 单个标签的分类。空串与 null 按未识别处理，不猜 */
    public static Classification classify(String tag) {
        if (tag == null || tag.isBlank()) {
            return Classification.of(Kind.UNRECOGNIZED);
        }
        String value = tag.trim();
        for (Rewrite rewrite : REWRITE) {
            value = value.replace(rewrite.from(), rewrite.to());
        }
        if (containsAny(value, GENERIC)) {
            return Classification.of(Kind.GENERIC);
        }

        List<Bucket> hits = new ArrayList<>();
        collect(value, DEMOGRAPHIC, hits);
        if (!containsAny(value, KINSHIP)) {
            collect(value, OCCUPATION, hits);
        }
        if (!hits.isEmpty()) {
            return Classification.audience(hits);
        }
        if (containsAny(value, NO_BUCKET) || containsAny(value, PERSON_SUFFIX)) {
            return Classification.of(Kind.NO_BUCKET);
        }
        if (containsAny(value, INTEREST_SUFFIX)) {
            return Classification.of(Kind.INTEREST);
        }
        return Classification.of(Kind.UNRECOGNIZED);
    }

    /** 一本书的标签集合去重后的桶引用；未映射的标签由 {@link #classify} 单独给类别，用于展示「另有 N 个标签无法比对」 */
    public static List<Bucket> bucketsOf(List<String> tags) {
        List<Bucket> result = new ArrayList<>();
        for (String tag : tags) {
            for (Bucket bucket : classify(tag).buckets()) {
                if (!result.contains(bucket)) {
                    result.add(bucket);
                }
            }
        }
        return result;
    }

    private static void collect(String value, List<Rule> rules, List<Bucket> into) {
        for (Rule rule : rules) {
            if (value.contains(rule.keyword())) {
                Bucket bucket = new Bucket(rule.dim(), rule.token());
                if (!into.contains(bucket)) {
                    into.add(bucket);
                }
            }
        }
    }

    private static boolean containsAny(String value, List<String> keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
