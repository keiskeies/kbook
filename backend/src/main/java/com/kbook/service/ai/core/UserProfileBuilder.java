package com.kbook.service.ai.core;

import com.kbook.entity.User;
import com.kbook.service.recommend.RecommendMatchCalculator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 用户画像文本构建器 — 从 User 实体提取多维信息，格式化为结构化文本供 AI 模型使用。
 *
 * <p>从 ChatModelManager 中抽取，职责单一：将用户数据转为 AI 可读的画像描述。</p>
 *
 * @author kbook
 * @since 1.1.0
 */
@Component
public class UserProfileBuilder {

    /**
     * 构建用户画像描述文本，用于个性化 AI 推荐和对话上下文。
     *
     * <p>提取：年龄、性别、婚姻、子女、MBTI、职业、期望学历、创业意向、
     * 期望收入、阅读意图、当前心情。</p>
     *
     * @param user 用户实体（可为 null）
     * @return 用户画像描述文本，user 为 null 时返回空字符串
     */
    public String build(User user) {
        if (user == null) return "";

        StringBuilder sb = new StringBuilder();

        if (user.getBirthday() != null) {
            int age = Period.between(user.getBirthday(), LocalDate.now()).getYears();
            sb.append("年龄：").append(age).append("岁\n");
        }
        if (user.getGender() != null) {
            sb.append("性别：").append(switch (user.getGender()) {
                case "MALE" -> "男";
                case "FEMALE" -> "女";
                default -> "其他";
            }).append("\n");
        }
        if (user.getMarried() != null) {
            sb.append("婚姻：").append(user.getMarried() ? "已婚" : "未婚").append("\n");
        }
        if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
            String labels = Arrays.stream(user.getChildrenAgeRanges().split(","))
                    .map(String::trim)
                    .map(RecommendMatchCalculator::getChildRangeLabel)
                    .collect(Collectors.joining("、"));
            sb.append("子女年龄段：").append(labels).append("\n");
        } else if (user.getHasChildren() != null) {
            sb.append("子女：").append(user.getHasChildren() ? "有孩子" : "无孩子").append("\n");
        }
        if (user.getMbti() != null) {
            sb.append("MBTI：").append(user.getMbti()).append("\n");
        }
        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            sb.append("职业：").append(RecommendMatchCalculator.getOccupationLabel(user.getOccupation())).append("\n");
        }
        if (user.getAspirationEducation() != null) {
            sb.append("期望学历：").append(RecommendMatchCalculator.getEducationLabel(user.getAspirationEducation())).append("\n");
        }
        if (user.getEntrepreneurship() != null) {
            sb.append("创业意向：").append(RecommendMatchCalculator.getEntrepreneurshipLabel(user.getEntrepreneurship())).append("\n");
        }
        if (user.getAspirationIncome() != null) {
            sb.append("期望年收入：").append(RecommendMatchCalculator.getAnnualIncomeLabel(user.getAspirationIncome())).append("\n");
        }
        if (user.getMood() != null && !user.getMood().isBlank()) {
            String moodRaw = user.getMood();
            int pipeIdx = moodRaw.indexOf('|');
            if (pipeIdx > 0) {
                String intentKey = moodRaw.substring(0, pipeIdx);
                String moodKey = moodRaw.substring(pipeIdx + 1);
                sb.append("阅读意图：").append(RecommendMatchCalculator.getIntentLabel(intentKey)).append("\n");
                sb.append("当前心情：").append(RecommendMatchCalculator.getMoodLabel(moodKey)).append("\n");
            }
        }
        return sb.toString();
    }
}
