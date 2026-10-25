package com.kbook.service.admin;

import com.kbook.service.admin.TargetAudienceRules.Bucket;
import com.kbook.service.admin.TargetAudienceRules.Classification;
import com.kbook.service.admin.TargetAudienceRules.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计文档附录 E.3 第 1 步（受控映射表）的单测。
 *
 * 这里要守的不是「映射得多」，而是「映射得对」：宁可把标签判成未映射，也不能把题材兴趣
 * 当成人口学身份——吻合度一旦虚高，运营会拿着它去改标签，比没有指标更糟。
 */
class TargetAudienceRulesTest {

    /** 附录 E.2 实测的头部标签 + 容易判错的构造样本 */
    private static final List<String> SAMPLE = List.of(
            "学生", "历史爱好者", "文学爱好者", "教育工作者", "创业者", "教师", "程序员",
            "女性读者", "中医爱好者", "普通读者", "家长", "亲子", "退休老人", "上班族",
            "金融爱好者", "投资者", "政策制定者", "自由职业者", "律师", "科幻小说读者",
            "大学生", "研究生", "年轻妈妈", "已婚女性", "单身青年", "设计师", "医生",
            "历史学者", "政治学学生", "心理咨询师", "企业管理者", "所有人群", "儿童",
            "科技从业者", "家庭主妇", "编程初学者", "职场新人", "英语学习者");

    @Test
    @DisplayName("不变式：任何映射出的桶都必须落在维度白名单里，不允许造出第 16 个分桶")
    void everyBucketIsWhitelisted() {
        for (String tag : SAMPLE) {
            Classification c = TargetAudienceRules.classify(tag);
            for (Bucket bucket : c.buckets()) {
                assertThat(bucket.dim().getAllowed())
                        .as("标签「%s」映射到 %s=%s", tag, bucket.dim(), bucket.token())
                        .containsKey(bucket.token());
            }
            if (c.kind() != Kind.AUDIENCE) {
                assertThat(c.buckets()).as("非 AUDIENCE 分类不应带桶：%s", tag).isEmpty();
            }
        }
    }

    // ==================== 优先级：身份 > 兴趣后缀 ====================

    @Test
    @DisplayName("「女性读者」是身份声明，不能被「读者」后缀判成兴趣")
    void demographicBeatsInterestSuffix() {
        assertThat(TargetAudienceRules.classify("女性读者").kind()).isEqualTo(Kind.AUDIENCE);
        assertThat(TargetAudienceRules.classify("女性读者").buckets())
                .containsExactly(new Bucket(BookProfileDimension.GENDER, "FEMALE"));
    }

    @Test
    @DisplayName("题材兴趣一律不进桶：users 侧没有「读什么题材」这一列")
    void interestTagsStayUnmapped() {
        for (String tag : List.of("历史爱好者", "文学爱好者", "中医爱好者", "科幻小说读者", "金融爱好者")) {
            assertThat(TargetAudienceRules.classify(tag).kind())
                    .as(tag).isEqualTo(Kind.INTEREST);
        }
    }

    // ==================== 领域词陷阱 ====================

    @Test
    @DisplayName("「金融爱好者」判兴趣、「金融从业者」判职业：领域词不得直接进桶")
    void domainWordIsNotOccupation() {
        assertThat(TargetAudienceRules.classify("金融爱好者").kind()).isEqualTo(Kind.INTEREST);
        assertThat(TargetAudienceRules.classify("金融从业者").buckets())
                .containsExactly(new Bucket(BookProfileDimension.OCCUPATION, "FINANCE"));
        assertThat(TargetAudienceRules.classify("投资者").buckets())
                .containsExactly(new Bucket(BookProfileDimension.OCCUPATION, "FINANCE"));
    }

    // ==================== 一个标签进多个桶 ====================

    @Test
    @DisplayName("「大学生」同时是年龄段和学生；「退休教师」是年龄段加教育加退休")
    void compoundIdentityHitsMultipleBuckets() {
        assertThat(TargetAudienceRules.classify("大学生").buckets()).containsExactly(
                new Bucket(BookProfileDimension.AGE, "18-24"),
                new Bucket(BookProfileDimension.OCCUPATION, "STUDENT"));
        assertThat(TargetAudienceRules.classify("退休教师").buckets()).containsExactly(
                new Bucket(BookProfileDimension.AGE, ">50"),
                new Bucket(BookProfileDimension.OCCUPATION, "EDUCATION"),
                new Bucket(BookProfileDimension.OCCUPATION, "RETIRED"));
    }

    @Test
    @DisplayName("「学生家长」声称的是家长，不是学生——亲属词必须屏蔽职业叠加")
    void kinshipSuppressesOccupation() {
        Classification c = TargetAudienceRules.classify("学生家长");
        assertThat(c.buckets()).containsExactly(new Bucket(BookProfileDimension.CHILDREN, "HAS_CHILD"));
    }

    @Test
    @DisplayName("「年轻妈妈」给年龄段与家长，不给职业")
    void youngMother() {
        assertThat(TargetAudienceRules.classify("年轻妈妈").buckets()).contains(
                new Bucket(BookProfileDimension.AGE, "18-24"),
                new Bucket(BookProfileDimension.CHILDREN, "HAS_CHILD"));
    }

    @Test
    @DisplayName("「初学者」不得因为含「学者」子串被判成教育/科研人群")
    void beginnerIsNotResearcher() {
        assertThat(TargetAudienceRules.classify("历史初学者").kind()).isEqualTo(Kind.UNRECOGNIZED);
        assertThat(TargetAudienceRules.classify("编程初学者").kind()).isEqualTo(Kind.UNRECOGNIZED);
        // 真正的研究者仍然进教育/科研桶
        assertThat(TargetAudienceRules.classify("历史研究者").buckets())
                .containsExactly(new Bucket(BookProfileDimension.OCCUPATION, "EDUCATION"));
    }

    // ==================== 其余三类未映射 ====================

    @Test
    @DisplayName("泛化词单独一类：「普通读者」「所有人群」比对不了任何分桶")
    void genericTags() {
        for (String tag : List.of("普通读者", "所有人群", "成年读者")) {
            assertThat(TargetAudienceRules.classify(tag).kind()).as(tag).isEqualTo(Kind.GENERIC);
        }
    }

    @Test
    @DisplayName("身份明确但无分桶：律师、上班族，以及「领域+从业者」这一类人称标签")
    void identityWithoutBucket() {
        for (String tag : List.of("律师", "上班族", "白领", "科技从业者", "人力资源从业者", "销售人员", "职场新人")) {
            assertThat(TargetAudienceRules.classify(tag).kind()).as(tag).isEqualTo(Kind.NO_BUCKET);
        }
    }

    @Test
    @DisplayName("「家庭主妇」是性别声称，「教育工作者」已是分桶词所以不进 NO_BUCKET")
    void housewifeIsGenderAndEducatorStaysAudience() {
        assertThat(TargetAudienceRules.classify("家庭主妇").buckets())
                .containsExactly(new Bucket(BookProfileDimension.GENDER, "FEMALE"));
        assertThat(TargetAudienceRules.classify("教育工作者").kind()).isEqualTo(Kind.AUDIENCE);
    }

    @Test
    @DisplayName("空值与未覆盖词按未识别处理，不猜")
    void nullAndBlankAndUnknown() {
        assertThat(TargetAudienceRules.classify(null).kind()).isEqualTo(Kind.UNRECOGNIZED);
        assertThat(TargetAudienceRules.classify("   ").kind()).isEqualTo(Kind.UNRECOGNIZED);
        assertThat(TargetAudienceRules.classify("户外").kind()).isEqualTo(Kind.UNRECOGNIZED);
    }

    // ==================== 书级聚合 ====================

    @Test
    @DisplayName("一本书多个标签的桶去重，未映射标签不贡献桶")
    void bucketsOfDeduplicates() {
        List<Bucket> buckets = TargetAudienceRules.bucketsOf(
                List.of("女性读者", "妇女", "历史爱好者", "学生", "普通读者"));
        assertThat(buckets).containsExactly(
                new Bucket(BookProfileDimension.GENDER, "FEMALE"),
                new Bucket(BookProfileDimension.OCCUPATION, "STUDENT"));
        assertThat(TargetAudienceRules.bucketsOf(List.of("历史爱好者", "律师"))).isEmpty();
    }
}
