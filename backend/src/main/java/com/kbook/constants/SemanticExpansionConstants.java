package com.kbook.constants;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SemanticExpansionConstants {

    private SemanticExpansionConstants() {}

    public enum Category {
        DOMAIN,
        LITERARY,
        BOTH
    }

    public record Expansion(String keyword, String synonyms, Category category) {}

    public static final List<Expansion> ALL = List.of(
            new Expansion("思维", "思维 认知 思考方式 思考 决策 心理学", Category.DOMAIN),
            new Expansion("提升", "提升 提高 进阶 方法 成长 觉醒", Category.DOMAIN),
            new Expansion("历史", "历史 历史故事 文明 古代 近代 史实 事件 过去", Category.BOTH),
            new Expansion("管理", "管理 领导力 组织 商业", Category.DOMAIN),
            new Expansion("心理", "心理学 认知行为 内心 精神 心灵 认知 行为 情绪", Category.BOTH),
            new Expansion("哲学", "哲学 思想 逻辑 智慧 思辨 存在", Category.BOTH),
            new Expansion("经济", "经济 金融 商业 投资 财富 贸易 市场", Category.DOMAIN),
            new Expansion("成长", "成长 自我提升 个人发展 变化 发展 蜕变 觉醒", Category.BOTH),
            new Expansion("沟通", "沟通 表达 人际 人际关系 说话", Category.DOMAIN),
            new Expansion("科学", "科学 科普 自然 探索 知识 发现", Category.BOTH),
            new Expansion("教育", "教育 学习 方法 成长 启蒙", Category.BOTH),
            new Expansion("健康", "健康 养生 运动 饮食 生活方式", Category.DOMAIN),
            new Expansion("创业", "创业 商业 创新 创造", Category.DOMAIN),
            new Expansion("编程", "编程 计算机 代码 算法 技术", Category.DOMAIN),
            new Expansion("小说", "小说 文学 故事 经典 叙事", Category.DOMAIN),
            new Expansion("推理", "推理 悬疑 侦探", Category.DOMAIN),
            new Expansion("科幻", "科幻 未来 想象", Category.DOMAIN),
            new Expansion("爱情", "爱情 恋爱 感情 情感 关系", Category.BOTH),
            new Expansion("恋爱", "恋爱 谈恋爱 交往 相爱 谈对象 谈朋友", Category.BOTH),
            new Expansion("脱单", "脱单 找对象 相亲 告白 表白 追求", Category.BOTH),
            new Expansion("暗恋", "暗恋 单恋 暗中喜欢 暗生情愫", Category.BOTH),
            new Expansion("表白", "表白 告白 示爱 吐露心意", Category.BOTH),
            new Expansion("暧昧", "暧昧 若即若离 暗生情愫 说不清", Category.BOTH),
            new Expansion("追求", "追求 追人 讨好 示好 献殷勤", Category.BOTH),
            new Expansion("约会", "约会 约会技巧 恋爱约会 浪漫", Category.BOTH),
            new Expansion("暧昧期", "暧昧期 互相试探 暗送秋波", Category.BOTH),
            new Expansion("热恋", "热恋 蜜月期 热恋期 甜蜜", Category.BOTH),
            new Expansion("异地恋", "异地恋 远距离 异地 感情维系", Category.BOTH),
            new Expansion("三角恋", "三角恋 多角关系 感情纠葛 夹缝", Category.BOTH),
            new Expansion("分手", "分手 分开 散了 结束关系 断联", Category.BOTH),
            new Expansion("复合", "复合 和好 重归于好 破镜重圆", Category.BOTH),
            new Expansion("出轨", "出轨 背叛 劈腿 不忠 外遇", Category.BOTH),
            new Expansion("背叛", "背叛 出轨 背信 伤害 信任崩塌", Category.BOTH),
            new Expansion("冷暴力", "冷暴力 冷战 忽视 冷漠 沉默对待", Category.BOTH),
            new Expansion("家暴", "家暴 家庭暴力 伤害 虐待", Category.BOTH),
            new Expansion("信任", "信任 信赖 坦诚 忠诚 安全感", Category.BOTH),
            new Expansion("安全感", "安全感 依赖 归属 踏实 被需要", Category.BOTH),
            new Expansion("磨合", "磨合 适应 包容 妥协 相处", Category.BOTH),
            new Expansion("亲密关系", "亲密关系 伴侣 夫妻 恋人 感情经营", Category.BOTH),
            new Expansion("婚姻经营", "婚姻经营 婚姻维护 夫妻相处 婚姻保鲜", Category.BOTH),
            new Expansion("婆媳", "婆媳 婆媳关系 婆婆 媳妇 家庭矛盾", Category.BOTH),
            new Expansion("离婚", "离婚 离异 婚姻破裂 分居", Category.BOTH),
            new Expansion("单亲", "单亲 离异带娃 独自抚养 单亲妈妈 单亲爸爸", Category.BOTH),
            new Expansion("嫉妒", "嫉妒 吃醋 羡慕 眼红 酸", Category.BOTH),
            new Expansion("依恋", "依恋 依赖 依附 纠缠 放不下", Category.BOTH),
            new Expansion("思念", "思念 想念 牵挂 怀念 惦记", Category.BOTH),
            new Expansion("心动", "心动 喜欢上 一见钟情 来电", Category.BOTH),
            new Expansion("浪漫", "浪漫 惊喜 仪式感 甜蜜 温馨", Category.BOTH),
            new Expansion("性", "性 性关系 亲密 两性 身体", Category.BOTH),
            new Expansion("两性", "两性 男女 男女关系 性别差异", Category.BOTH),
            new Expansion("男人", "男人 男性 男友 老公 丈夫", Category.BOTH),
            new Expansion("女人", "女人 女性 女友 老婆 妻子", Category.BOTH),

            new Expansion("职场", "职场 工作 职业发展", Category.DOMAIN),
            new Expansion("投资", "投资 理财 财富", Category.DOMAIN),
            new Expansion("文学", "文学 小说 经典 叙事", Category.DOMAIN),

            new Expansion("学生", "学生 学习 考试 校园 课业 学业 青春", Category.DOMAIN),
            new Expansion("考试", "考试 考研 考公 高考 四六级 雅思 托福 备考", Category.DOMAIN),
            new Expansion("技术", "技术 IT 互联网 程序员 软件 开发 工程", Category.DOMAIN),
            new Expansion("金融", "金融 银行 证券 投资 经济 商业 财务", Category.DOMAIN),
            new Expansion("医疗", "医疗 医学 临床 健康 护理 药学", Category.DOMAIN),
            new Expansion("文艺", "文艺 传媒 设计 创意 艺术 文化", Category.DOMAIN),
            new Expansion("行政", "行政 管理 办公 人事 组织", Category.DOMAIN),
            new Expansion("自由职业", "自由职业 远程 独立 自主 斜杠", Category.DOMAIN),
            new Expansion("退休", "退休 养老 晚年 休闲", Category.DOMAIN),

            new Expansion("育儿", "育儿 亲子 家庭 教育 孩子 宝宝 婴幼儿 父母", Category.DOMAIN),
            new Expansion("早教", "早教 启蒙 幼儿 学前 认知 0到3岁", Category.DOMAIN),
            new Expansion("儿童", "儿童 童书 绘本 少儿 小学生 中学生 青少年", Category.DOMAIN),
            new Expansion("婚姻", "婚姻 夫妻 家庭 关系 经营 相处", Category.DOMAIN),
            new Expansion("家庭", "家庭 亲情 家人 父母 子女 夫妻", Category.BOTH),

            new Expansion("焦虑", "焦虑 压力 紧张 不安 烦躁 担忧", Category.BOTH),
            new Expansion("低落", "低落 沮丧 失落 难过 悲伤 消沉", Category.BOTH),
            new Expansion("疲惫", "疲惫 疲倦 劳累 乏力 倦怠 精疲力竭", Category.BOTH),
            new Expansion("烦躁", "烦躁 烦闷 压抑 焦躁 不耐烦", Category.BOTH),
            new Expansion("平静", "平静 宁静 放松 淡定 安宁", Category.BOTH),
            new Expansion("开心", "开心 快乐 愉悦 喜悦 幸福", Category.BOTH),
            new Expansion("失恋", "失恋 分手 感情受挫 心碎 情伤", Category.BOTH),
            new Expansion("压力", "压力 焦虑 负担 紧绷 喘不过气", Category.BOTH),
            new Expansion("迷茫", "迷茫 困惑 彷徨 找方向 不知所措", Category.BOTH),
            new Expansion("孤独", "孤独 寂寞 疏离 独处", Category.BOTH),
            new Expansion("治愈", "治愈 温暖 安慰 抚慰 疗愈 走出来", Category.BOTH),
            new Expansion("放松", "放松 休闲 解压 减压 休息 逃离", Category.BOTH),

            new Expansion("充电", "充电 学习 提升 进阶 自我提升 技能", Category.DOMAIN),
            new Expansion("解惑", "解惑 答疑 指点 方向 启发 开悟", Category.DOMAIN),
            new Expansion("陪伴", "陪伴 共鸣 温暖 理解 倾诉 慰藉", Category.DOMAIN),
            new Expansion("刺激", "刺激 新鲜 惊险 冒险 奇幻 脑洞", Category.DOMAIN),
            new Expansion("逃避", "逃避 放空 沉浸 转移 忘却", Category.DOMAIN),

            new Expansion("内向", "内向 沉静 独处 I人 安静 敏感", Category.DOMAIN),
            new Expansion("外向", "外向 社交 E人 热情 活泼 开朗", Category.DOMAIN),
            new Expansion("直觉", "直觉 N人 洞察 想象 远见", Category.DOMAIN),
            new Expansion("理性", "理性 T人 逻辑 分析 客观 判断", Category.DOMAIN),
            new Expansion("感性", "感性 F人 共情 体贴 价值 温暖", Category.DOMAIN),
            new Expansion("计划", "计划 J人 规划 条理 秩序 自律", Category.DOMAIN),
            new Expansion("随性", "随性 P人 灵活 自由 即兴 探索", Category.DOMAIN),

            new Expansion("理财", "理财 投资 财务 赚钱 财商 资产", Category.DOMAIN),
            new Expansion("赚钱", "赚钱 收入 副业 财富 财务自由", Category.DOMAIN),
            new Expansion("副业", "副业 兼职 第二收入 自由职业", Category.DOMAIN),
            new Expansion("财富", "财富 富裕 有钱 金钱 财务自由 资产 富足", Category.DOMAIN),
            new Expansion("金钱", "金钱 钱 财务 收入 财富 经济", Category.DOMAIN),
            new Expansion("财务自由", "财务自由 财富自由 提前退休 FIRE 被动收入 不用上班", Category.DOMAIN),
            new Expansion("贫穷", "贫穷 贫困 缺钱 债务 经济困难 困窘", Category.BOTH),
            new Expansion("消费", "消费 花钱 购物 开支 支出 理性消费", Category.DOMAIN),
            new Expansion("存钱", "存钱 储蓄 攒钱 节俭 省钱", Category.DOMAIN),
            new Expansion("房产", "房产 买房 置业 房贷 不动产", Category.DOMAIN),
            new Expansion("保险", "保险 保障 养老 社保 医保", Category.DOMAIN),
            new Expansion("股票", "股票 股市 基金 证券 交易 炒股", Category.DOMAIN),
            new Expansion("基金", "基金 定投 理财 投资 组合", Category.DOMAIN),
            new Expansion("负债", "负债 债务 贷款 还款 借钱", Category.DOMAIN),

            new Expansion("修心", "修心 养性 修行 内修 心性 涵养 修持", Category.BOTH),
            new Expansion("养性", "养性 修身 修心 陶冶 涵养 心性", Category.BOTH),
            new Expansion("修行", "修行 修炼 修持 参悟 悟道 觉悟", Category.BOTH),
            new Expansion("禅", "禅 禅修 冥想 正念 觉察 打坐 内观", Category.BOTH),
            new Expansion("冥想", "冥想 正念 静坐 内观 打坐 觉察", Category.BOTH),
            new Expansion("静心", "静心 宁心 定心 沉静 安神 清心", Category.BOTH),
            new Expansion("觉悟", "觉悟 开悟 顿悟 醒悟 参悟 觉知", Category.BOTH),
            new Expansion("放下", "放下 释怀 看开 不执著 随缘 超脱", Category.BOTH),
            new Expansion("知足", "知足 满足 惜福 知足常乐 不贪", Category.BOTH),
            new Expansion("淡泊", "淡泊 清淡 超然 无争 不慕名利", Category.BOTH),
            new Expansion("修身", "修身 自省 克己 慎独 品德 操守", Category.BOTH),
            new Expansion("境界", "境界 格局 层次 高度 视野", Category.BOTH),
            new Expansion("智慧", "智慧 智识 见识 洞见 睿智", Category.BOTH),
            new Expansion("灵性", "灵性 灵修 心灵 灵魂 精神世界", Category.BOTH),
            new Expansion("国学", "国学 传统文化 儒学 道学 易经 经典", Category.BOTH),
            new Expansion("道家", "道家 老子 庄子 道德经 无为 道法自然", Category.BOTH),
            new Expansion("儒家", "儒家 孔子 论语 仁义 礼学 中庸", Category.BOTH),
            new Expansion("佛学", "佛学 佛法 佛教 般若 心经 金刚经", Category.BOTH),
            new Expansion("易经", "易经 周易 易理 八卦 阴阳 变通", Category.BOTH),
            new Expansion("养生", "养生 保健 长寿 调理 气血 中医", Category.DOMAIN),
            new Expansion("中医", "中医 中药 针灸 经络 气血 本草", Category.DOMAIN),

            new Expansion("女性", "女性 女人 女孩 女生 她力量", Category.DOMAIN),
            new Expansion("男性", "男性 男人 男生 男子气概", Category.DOMAIN),

            new Expansion("入门", "入门 零基础 初学 新手 小白 启蒙", Category.DOMAIN),
            new Expansion("进阶", "进阶 提高 深入 高级 专业", Category.DOMAIN),
            new Expansion("经典", "经典 名著 必读 传世 不朽", Category.DOMAIN),
            new Expansion("畅销", "畅销 热门 受欢迎 现象级", Category.DOMAIN),
            new Expansion("传记", "传记 人物 生平 回忆录 自传", Category.DOMAIN),
            new Expansion("励志", "励志 鸡汤 正能量 激励 奋斗", Category.DOMAIN),
            new Expansion("幽默", "幽默 搞笑 轻松 诙谐 趣味", Category.DOMAIN),
            new Expansion("悬疑", "悬疑 推理 侦探 解谜 烧脑", Category.DOMAIN),
            new Expansion("奇幻", "奇幻 魔法 玄幻 仙侠 异世界", Category.DOMAIN),
            new Expansion("武侠", "武侠 江湖 侠客 武功 武术", Category.DOMAIN),
            new Expansion("散文", "散文 随笔 杂文 游记 感悟", Category.DOMAIN),
            new Expansion("诗歌", "诗歌 诗词 诗句 现代诗 古诗", Category.DOMAIN),
            new Expansion("科普", "科普 科学 知识 探索 自然", Category.DOMAIN),
            new Expansion("社科", "社科 社会学 人类学 文化 制度", Category.DOMAIN),
            new Expansion("自律", "自律 习惯 毅力 坚持 意志力 自控", Category.DOMAIN),
            new Expansion("效率", "效率 时间管理 生产力 方法 工具", Category.DOMAIN),
            new Expansion("领导力", "领导力 管理 决策 影响力 团队", Category.DOMAIN),
            new Expansion("谈判", "谈判 沟通 说服 博弈 交锋", Category.DOMAIN),
            new Expansion("演讲", "演讲 表达 口才 当众说话", Category.DOMAIN),

            new Expansion("主角", "主角 人物 角色 主人公", Category.LITERARY),
            new Expansion("人物", "人物 角色 形象 性格 主人公", Category.LITERARY),
            new Expansion("主题", "主题 核心思想 主旨 要义", Category.LITERARY),
            new Expansion("情节", "情节 故事 剧情 发展", Category.LITERARY),
            new Expansion("结局", "结局 结尾 收尾 结果", Category.LITERARY),
            new Expansion("关系", "关系 互动 纠葛 联系", Category.LITERARY),
            new Expansion("写作", "写作 手法 技巧 叙事 风格", Category.LITERARY),
            new Expansion("写作手法", "叙事技巧 表现手法 写作手法", Category.LITERARY),
            new Expansion("象征", "象征 隐喻 暗示 寓意", Category.LITERARY),
            new Expansion("冲突", "冲突 矛盾 对抗 争斗", Category.LITERARY),
            new Expansion("背景", "背景 设定 环境 时代", Category.LITERARY),
            new Expansion("观点", "观点 看法 思想 见解", Category.LITERARY),
            new Expansion("原因", "原因 缘由 动机 为什么", Category.LITERARY),
            new Expansion("意义", "意义 价值 含义 重要性", Category.LITERARY),
            new Expansion("区别", "区别 差异 不同 对比", Category.LITERARY),
            new Expansion("影响", "影响 作用 后果 效果", Category.LITERARY),
            new Expansion("方法", "方法 方式 做法 途径", Category.LITERARY),
            new Expansion("特点", "特点 特征 特性 特色", Category.LITERARY),
            new Expansion("结构", "结构 框架 组织 布局", Category.LITERARY),
            new Expansion("情感", "情感 感受 心理 情绪", Category.LITERARY),
            new Expansion("道德", "道德 伦理 是非 善恶", Category.LITERARY),
            new Expansion("自由", "自由 解放 独立 束缚", Category.LITERARY),
            new Expansion("权力", "权力 权威 统治 控制 治理 制度", Category.BOTH),
            new Expansion("死亡", "死亡 生命 终结 逝去", Category.LITERARY),
            new Expansion("正义", "正义 公平 公正 道义", Category.LITERARY),
            new Expansion("命运", "命运 宿命 天命 必然", Category.LITERARY),
            new Expansion("人性", "人性 本性 本能 人心", Category.LITERARY),
            new Expansion("社会", "社会 群体 制度 时代", Category.LITERARY),
            new Expansion("文化", "文化 文明 传统 习俗", Category.LITERARY),
            new Expansion("战争", "战争 冲突 战斗 争斗", Category.LITERARY),
            new Expansion("宗教", "宗教 信仰 神学 灵性", Category.LITERARY),
            new Expansion("政治", "政治 权力 治理 制度", Category.LITERARY),
            new Expansion("核心观点", "核心观点 主要论点 中心思想", Category.LITERARY),
            new Expansion("主要讲", "主要讲 核心内容 重点", Category.LITERARY)
    );

    private static final Map<String, Expansion> KEYWORD_MAP = ALL.stream()
            .collect(Collectors.toMap(Expansion::keyword, e -> e, (a, b) -> a));

    public static String findSynonyms(String keyword, Category category) {
        Expansion expansion = KEYWORD_MAP.get(keyword);
        if (expansion == null) return null;
        if (category == Category.BOTH || expansion.category() == category || expansion.category() == Category.BOTH) {
            return expansion.synonyms();
        }
        return null;
    }

    public static List<Expansion> findByCategory(Category category) {
        if (category == Category.BOTH) return ALL;
        return ALL.stream()
                .filter(e -> e.category() == category || e.category() == Category.BOTH)
                .toList();
    }

    public static String expandQuery(String query, Category category, int maxExpansions) {
        if (query == null || query.isBlank()) return query;

        List<String> expansions = ALL.stream()
                .filter(e -> query.contains(e.keyword()))
                .filter(e -> category == Category.BOTH || e.category() == category || e.category() == Category.BOTH)
                .limit(maxExpansions)
                .map(Expansion::synonyms)
                .toList();

        if (expansions.isEmpty()) return query;
        return query + " " + String.join(" ", expansions);
    }
}
