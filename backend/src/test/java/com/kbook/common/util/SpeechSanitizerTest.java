package com.kbook.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 圆桌派发言清洗工具单测（阶段 2 第 1 批 — 纯静态工具，用真实脏数据模式验证）。
 * <p>
 * 用例来源即类注释里列出的三类脏数据：名字前缀、串台、第三人称旁白。
 */
class SpeechSanitizerTest {

    private static final List<String> ROLES = List.of("雷军", "马化腾", "刘强东", "乔布斯");

    // ==================== sanitize 主流程 ====================

    @Nested
    @DisplayName("sanitize — 完整清洗流程")
    class Sanitize {

        @Test
        void 空输入返回空串() {
            assertThat(SpeechSanitizer.sanitize(null, "雷军", ROLES)).isEmpty();
            assertThat(SpeechSanitizer.sanitize("   ", "雷军", ROLES)).isEmpty();
        }

        @Test
        void 正常发言原样保留() {
            assertThat(SpeechSanitizer.sanitize("我认为分布式存储是未来。", "雷军", ROLES))
                    .isEqualTo("我认为分布式存储是未来。");
        }

        @Test
        void 剥离单层角色名前缀() {
            assertThat(SpeechSanitizer.sanitize("雷军：这个观点我同意。", "雷军", ROLES))
                    .isEqualTo("这个观点我同意。");
        }

        @Test
        void 剥离多层与带装饰的前缀() {
            assertThat(SpeechSanitizer.sanitize("**雷军**：雷军：大家好。", "雷军", ROLES))
                    .isEqualTo("大家好。");
        }

        @Test
        void 剥离自己名字加逗号的自我称呼() {
            assertThat(SpeechSanitizer.sanitize("刘强东，你老把隐性知识挂在嘴边。", "刘强东", ROLES))
                    .isEqualTo("你老把隐性知识挂在嘴边。");
        }

        @Test
        void 串台内容在另一位嘉宾行首处截断() {
            String raw = "这本书的第一部分观点非常精彩。\n\n马化腾：你那套打法不行。";
            assertThat(SpeechSanitizer.sanitize(raw, "雷军", ROLES))
                    .isEqualTo("这本书的第一部分观点非常精彩。");
        }

        @Test
        void 正文行中的点名不会被误伤() {
            // "马化腾：" 不在行首 → 剥前缀与串台截断都不触发
            String raw = "我想回应一下，马化腾：这个比喻很妙。";
            assertThat(SpeechSanitizer.sanitize(raw, "雷军", ROLES))
                    .isEqualTo("我想回应一下，马化腾：这个比喻很妙。");
        }

        @Test
        void 第三人称旁白开头改写为第一人称内容() {
            String raw = "乔布斯对李彦宏的观点表示强烈反对，认为这本书很水";
            assertThat(SpeechSanitizer.sanitize(raw, "乔布斯", ROLES))
                    .isEqualTo("这本书很水");
        }

        @Test
        void 去掉模型自作主张的收尾语() {
            assertThat(SpeechSanitizer.sanitize("我的观点说完了。\n以上。", "雷军", ROLES))
                    .isEqualTo("我的观点说完了。");
        }

        @Test
        void 去粗体标记并规整连续空行() {
            assertThat(SpeechSanitizer.sanitize("**重点**内容\n\n\n\n下一段", "雷军", ROLES))
                    .isEqualTo("重点内容\n\n下一段");
        }

        @Test
        void 超长发言按句号收口截断到上限() {
            String raw = "字。".repeat(400); // 800 字符
            String out = SpeechSanitizer.sanitize(raw, "雷军", ROLES);
            // 前 600 字内最后句号在 599（>300）→ 截到句号，长度恰为 600
            assertThat(out.length()).isEqualTo(600);
            assertThat(out).endsWith("。");
        }
    }

    // ==================== 相似度 ====================

    @Nested
    @DisplayName("similarity — 二元组 Jaccard 相似度")
    class Similarity {

        @Test
        void 完全相同为1() {
            assertThat(SpeechSanitizer.similarity("今天聊点硬件", "今天聊点硬件")).isEqualTo(1.0);
        }

        @Test
        void 空与null为0() {
            assertThat(SpeechSanitizer.similarity(null, "abc")).isZero();
            assertThat(SpeechSanitizer.similarity("abc", " ")).isZero();
        }

        @Test
        void 无公共二元组为0() {
            assertThat(SpeechSanitizer.similarity("苹果", "香蕉")).isZero();
        }

        @Test
        void 部分重叠为中间值() {
            // ga={你好,好世,世界} gb={世界,界你,你好} 交集 2 → 2/(3+3-2)=0.5
            assertThat(SpeechSanitizer.similarity("你好世界", "世界你好")).isEqualTo(0.5);
        }

        @Test
        void 标点与空白不参与相似度() {
            // 去标点后完全一致 → 1.0
            assertThat(SpeechSanitizer.similarity("你好，世界。", "你好世界")).isEqualTo(1.0);
        }
    }

    // ==================== 名单合并 ====================

    @Test
    @DisplayName("mergeNames — null 安全且去重保序")
    void mergeNamesDedupesAndHandlesNull() {
        // Arrays.asList 允许 null 元素（List.of 不允许），用于验证脏输入兜底
        assertThat(SpeechSanitizer.mergeNames(null, Arrays.asList("雷军", "雷军", " ", null, "乔布斯")))
                .containsExactly("雷军", "乔布斯");
        assertThat(SpeechSanitizer.mergeNames(List.of("雷军"), null))
                .containsExactly("雷军");
    }
}
