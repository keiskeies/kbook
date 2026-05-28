package com.kbook.test;

import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 用户数据造数工具
 * 用于生成各种属性组合的用户数据
 */
@SpringBootTest
@ActiveProfiles("test") // 使用test配置文件（性能优化）
//@Transactional // 测试完成后回滚，如需持久化请注释掉此注解
public class UserDataGeneratorTest {

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Random random = new Random();

    // 定义所有可能的枚举值
    private static final String[] GENDERS = {"MALE", "FEMALE", "OTHER"};
    private static final Boolean[] MARRIED_OPTIONS = {true, false};
    private static final Boolean[] HAS_CHILDREN_OPTIONS = {true, false};
    private static final String[] MBTI_TYPES = {
            "ISTJ", "ISFJ", "INFJ", "INTJ",
            "ISTP", "ISFP", "INFP", "INTP",
            "ESTP", "ESFP", "ENFP", "ENTP",
            "ESTJ", "ESFJ", "ENFJ", "ENTJ"
    };
    private static final String[] OCCUPATIONS = {
            "STUDENT", "TECH", "FINANCE", "EDUCATION",
            "MEDICAL", "ARTS", "MANAGEMENT", "FREELANCE",
            "RETIRED", "OTHER"
    };
    private static final String[] EDUCATION_LEVELS = {
            "HIGH_SCHOOL", "COLLEGE", "BACHELOR", "MASTER",
            "DOCTORATE", "OTHER"
    };
    private static final String[] ENTREPRENEURSHIP_OPTIONS = {
            "ENTREPRENEUR", "WANT_ENTREPRENEUR", "NOT_INTERESTED"
    };
    private static final String[] ANNUAL_INCOME_OPTIONS = {
            "UNDER_50K", "50K_150K", "150K_300K", "300K_500K",
            "500K_1M", "OVER_1M", "PREFER_NOT_TO_SAY"
    };
    private static final String[] MOOD_OPTIONS = {
            "HAPPY", "CALM", "ANXIOUS", "SAD",
            "MOTIVATED", "TIRED", "CURIOUS"
    };

    // 年龄段划分（4个区间）
    private static final int[][] AGE_RANGES = {
            {14, 19},   // 14-19
            {20, 29},   // 20-29
            {30, 39},   // 30-39
            {40, 49}    // 40-49
    };

    /**
     * 生成全量组合用户数据 - 覆盖所有可能的属性组合
     * 年龄作为最外层循环，根据年龄自动过滤不符合业务规则的组合
     */
    @Test
    public void generateAllCombinations() {
        System.out.println("开始生成全量组合用户数据...");
        System.out.println("年龄范围: 14-49岁 (分为4个年龄段)");
        
        // 计算理论总数（不考虑continue过滤）
        long theoreticalTotal = (long) AGE_RANGES.length * GENDERS.length * MARRIED_OPTIONS.length 
                * HAS_CHILDREN_OPTIONS.length * MBTI_TYPES.length * OCCUPATIONS.length 
                * EDUCATION_LEVELS.length * ENTREPRENEURSHIP_OPTIONS.length 
                * ANNUAL_INCOME_OPTIONS.length * MOOD_OPTIONS.length;
        System.out.println("理论最大组合数: " + String.format("%,d", theoreticalTotal));
        System.out.println("实际数量会因业务规则过滤而减少");
        System.out.println("警告: 数据量较大，请确保数据库有足够空间！");
        System.out.println("请稍候，这可能需要较长时间...\n");

        List<User> usersToSave = new ArrayList<>();
        int count = 0;
        long startTime = System.currentTimeMillis();

        // 年龄在最外层循环，按年龄段遍历
        for (int[] ageRange : AGE_RANGES) {
            int minAge = ageRange[0];
            int maxAge = ageRange[1];

            // 在每个年龄段内随机取一个年龄

            for (String gender : GENDERS) {

                for (Boolean married : MARRIED_OPTIONS) {
                    // 未成年（<18岁）：固定未婚
                    if (maxAge < 20 && married) continue;

                    for (Boolean hasChildren : HAS_CHILDREN_OPTIONS) {
                        // 未成年（<18岁）：固定无小孩
                        if (maxAge < 20 && hasChildren) continue;

                        for (String mbti : MBTI_TYPES) {
                            for (String occupation : OCCUPATIONS) {
                                // 未成年（<18岁）：必须是学生
                                if (maxAge < 20 && !"STUDENT".equals(occupation)) continue;
                                // 老年（>=60岁）：必须是退休
                                if (minAge >= 60 && !"RETIRED".equals(occupation)) continue;

                                for (String education : EDUCATION_LEVELS) {
                                    for (String entrepreneurship : ENTREPRENEURSHIP_OPTIONS) {
                                        // 未成年和老年：固定无创业意向
                                        if ((maxAge < 20 || minAge >= 60) && !"NOT_INTERESTED".equals(entrepreneurship))
                                            continue;

                                        for (String annualIncome : ANNUAL_INCOME_OPTIONS) {
                                            // 未成年（<18岁）：固定最低收入
                                            if (maxAge < 20 && !"UNDER_50K".equals(annualIncome)) continue;
                                            // 老年（>=60岁）：固定不便透露
                                            if (minAge >= 60 && !"PREFER_NOT_TO_SAY".equals(annualIncome)) continue;

                                            for (String mood : MOOD_OPTIONS) {

                                                count++;  // 先递增，保证从1开始
                                                int age = minAge + random.nextInt(maxAge - minAge + 1);
                                                // 创建用户对象
                                                User user = createUser(
                                                        gender, married, hasChildren, mbti,
                                                        occupation, education, entrepreneurship,
                                                        annualIncome, mood, age, count
                                                );

                                                usersToSave.add(user);

                                                // 每2000条批量保存一次（优化性能）
                                                if (usersToSave.size() >= 2000) {
                                                    userRepository.saveAllAndFlush(usersToSave);
                                                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                                                    long speed = elapsed > 0 ? count / elapsed : count;
                                                    System.out.printf("进度: %s 条 | 年龄 %d岁 (%d-%d) | 耗时: %d秒 | 速度: %d 条/秒%n",
                                                            String.format("%,d", count), age, minAge, maxAge, elapsed, speed);
                                                    usersToSave.clear();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 保存剩余的数据
        if (!usersToSave.isEmpty()) {
            userRepository.saveAllAndFlush(usersToSave);
        }

        long endTime = System.currentTimeMillis();
        long totalElapsed = (endTime - startTime) / 1000;

        System.out.println("\n========== 生成完成 ==========");
        System.out.println("共生成 " + count + " 条用户数据");
        System.out.println("总耗时: " + totalElapsed + " 秒 (约" + (totalElapsed / 60) + "分钟)");
        long avgSpeed = totalElapsed > 0 ? count / totalElapsed : count;
        System.out.println("平均速度: " + avgSpeed + " 条/秒");
        System.out.println("\n属性覆盖情况:");
        System.out.println("✓ 性别: " + GENDERS.length + " 种 (" + String.join(", ", GENDERS) + ")");
        System.out.println("✓ 婚姻: " + MARRIED_OPTIONS.length + " 种 (true, false)");
        System.out.println("✓ 孩子: " + HAS_CHILDREN_OPTIONS.length + " 种 (true, false)");
        System.out.println("✓ MBTI: " + MBTI_TYPES.length + " 种 (全部16型人格)");
        System.out.println("✓ 职业: " + OCCUPATIONS.length + " 种 (" + String.join(", ", OCCUPATIONS) + ")");
        System.out.println("✓ 学历: " + EDUCATION_LEVELS.length + " 种 (" + String.join(", ", EDUCATION_LEVELS) + ")");
        System.out.println("✓ 创业: " + ENTREPRENEURSHIP_OPTIONS.length + " 种 (" + String.join(", ", ENTREPRENEURSHIP_OPTIONS) + ")");
        System.out.println("✓ 收入: " + ANNUAL_INCOME_OPTIONS.length + " 种 (全部7个区间)");
        System.out.println("✓ 心情: " + MOOD_OPTIONS.length + " 种 (" + String.join(", ", MOOD_OPTIONS) + ")");
        System.out.println("✓ 年龄: 4个年龄段 (14-19, 20-29, 30-39, 40-49)，每个段内随机取一个年龄");
        System.out.println("\n业务规则:");
        System.out.println("  - 未成年(<18岁): 未婚、无孩、学生、低收入、无创业意向");
        System.out.println("  - 老年(>=60岁): 退休、不便透露收入、无创业意向");
        System.out.println("  - 其他年龄: 所有属性自由组合");
        System.out.println("==============================\n");
    }

    /**
     * 创建单个用户对象
     */
    private User createUser(String gender, Boolean married, Boolean hasChildren,
                            String mbti, String occupation, String education,
                            String entrepreneurship, String annualIncome, String mood, int age, int sequenceNumber) {

        // 生成唯一邮箱（使用序号保证唯一性）
        String email = "user_" + System.nanoTime() + "_" + sequenceNumber + "@keiskei.top";

        // 使用明文密码（测试环境，提升性能）
        // BCrypt加密非常耗时，500万条数据会浪费大量时间在加密上
        String plainPassword = "password123";

        // 使用传入的年龄参数
        LocalDate birthday = LocalDate.now().minusYears(age);

        return User.builder()
                .email(email)
                .password(plainPassword) // 测试环境使用明文密码，提升性能
                .nickname("用户" + sequenceNumber)
                .avatar(null) // 可选设置头像
                .role("USER") // 固定为USER角色
                .status("APPROVED") // 可用状态
                .emailBound(true) // 已绑定邮箱
                .birthday(birthday)
                .gender(gender)
                .married(married)
                .hasChildren(hasChildren)
                .mbti(mbti)
                .occupation(occupation)
//                .education(education)
                .entrepreneurship(entrepreneurship)
//                .annualIncome(annualIncome)
                .mood(mood)
                .bio("这是一个自动生成的测试用户")
                .followerCount(0)
                .followingCount(0)
                .build();
    }

    /**
     * 清空所有测试用户数据(谨慎使用)
     */
    @Test
    public void cleanTestData() {
        System.out.println("开始清理测试用户数据...");
        long beforeCount = userRepository.count();
        System.out.println("清理前用户总数: " + beforeCount);

        // 删除所有状态为APPROVED且邮箱包含@keiskei.top的用户
        List<User> testUsers = userRepository.findAll().stream()
                .filter(user -> user.getEmail().contains("@keiskei.top") && "APPROVED".equals(user.getStatus()))
                .toList();

        userRepository.deleteAll(testUsers);

        long afterCount = userRepository.count();
        System.out.println("清理后用户总数: " + afterCount);
        System.out.println("共删除 " + (beforeCount - afterCount) + " 条测试用户数据");
    }
}
