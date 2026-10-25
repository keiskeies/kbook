package com.kbook.service.ai.core;

import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.entity.AiSceneConfig;
import com.kbook.repository.AiProviderConfigRepository;
import com.kbook.repository.AiSceneConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 场景配置解析单测（阶段 2 第 2 批 — Mockito）。
 * <p>
 * 覆盖：精确绑定 → 分类回退的两级解析、思考参数三级来源、Caffeine 缓存命中、bind/unbind 语义。
 */
class AiSceneConfigServiceTest {

    private AiSceneConfigRepository sceneConfigRepository;
    private AiProviderConfigRepository providerConfigRepository;
    private AiProviderConfigService providerConfigService;
    private AiConfigCacheInvalidator cacheInvalidator;
    private AiSceneConfigService service;

    @BeforeEach
    void setUp() {
        sceneConfigRepository = mock(AiSceneConfigRepository.class);
        providerConfigRepository = mock(AiProviderConfigRepository.class);
        providerConfigService = mock(AiProviderConfigService.class);
        cacheInvalidator = mock(AiConfigCacheInvalidator.class);
        service = new AiSceneConfigService(sceneConfigRepository, providerConfigRepository,
                providerConfigService, cacheInvalidator);
    }

    private static AiProviderConfig provider(long id, boolean enabled) {
        AiProviderConfig cfg = new AiProviderConfig();
        cfg.setId(id);
        cfg.setEnabled(enabled);
        cfg.setName("cfg-" + id);
        return cfg;
    }

    // ==================== 解析：精确绑定与回退 ====================

    @Nested
    @DisplayName("resolveConfig — 两级解析")
    class Resolve {

        @Test
        void 显式绑定且配置启用_返回绑定配置() {
            AiSceneConfig binding = new AiSceneConfig();
            binding.setConfigId(5L);
            binding.setThinkingEnabled(true);
            binding.setReasoningEffort("high");
            binding.setThinkingBudget(2048);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(binding));
            when(providerConfigRepository.findById(5L))
                    .thenReturn(Optional.of(provider(5L, true)));

            AiProviderConfig resolved = service.resolveConfig(AiScene.AI_ASSISTANT);

            assertThat(resolved.getId()).isEqualTo(5L);
        }

        @Test
        void 绑定的配置被禁用_回退到QA默认() {
            AiSceneConfig binding = new AiSceneConfig();
            binding.setConfigId(5L);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(binding));
            when(providerConfigRepository.findById(5L))
                    .thenReturn(Optional.of(provider(5L, false)));
            when(providerConfigService.getChatConfigByRole("QA"))
                    .thenReturn(provider(9L, true));

            assertThat(service.resolveConfig(AiScene.AI_ASSISTANT).getId()).isEqualTo(9L);
        }

        @Test
        void 未绑定_走QA分类默认() {
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("QA"))
                    .thenReturn(provider(9L, true));

            assertThat(service.resolveConfig(AiScene.AI_ASSISTANT).getId()).isEqualTo(9L);
        }

        @Test
        void TOOL场景回退到TOOL默认配置() {
            when(sceneConfigRepository.findBySceneKey(AiScene.LIST_QUERY_DETECT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("TOOL"))
                    .thenReturn(provider(7L, true));

            assertThat(service.resolveConfig(AiScene.LIST_QUERY_DETECT).getId()).isEqualTo(7L);
        }

        @Test
        void QA回退缺失_抛IllegalStateException() {
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("QA")).thenReturn(null);

            assertThatThrownBy(() -> service.resolveConfig(AiScene.AI_ASSISTANT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QA");
        }

        @Test
        void TOOL回退缺失_抛IllegalStateException() {
            when(sceneConfigRepository.findBySceneKey(AiScene.LIST_QUERY_DETECT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("TOOL")).thenReturn(null);

            assertThatThrownBy(() -> service.resolveConfig(AiScene.LIST_QUERY_DETECT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TOOL");
        }
    }

    // ==================== 思考参数三级来源 ====================

    @Nested
    @DisplayName("resolveSceneConfig — 思考参数解析")
    class ThinkingParams {

        @Test
        void 绑定自带思考参数_优先取绑定字段() {
            AiSceneConfig binding = new AiSceneConfig();
            binding.setConfigId(5L);
            binding.setThinkingEnabled(false);       // 与场景默认相反，验证绑定优先
            binding.setReasoningEffort("low");
            binding.setThinkingBudget(512);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(binding));
            when(providerConfigRepository.findById(5L))
                    .thenReturn(Optional.of(provider(5L, true)));

            var resolved = service.resolveSceneConfig(AiScene.AI_ASSISTANT);

            assertThat(resolved.thinkingEnabled()).isFalse();
            assertThat(resolved.reasoningEffort()).isEqualTo("low");
            assertThat(resolved.thinkingBudget()).isEqualTo(512);
        }

        @Test
        void 绑定的thinking字段为null_回退场景默认() {
            AiSceneConfig binding = new AiSceneConfig();
            binding.setConfigId(5L);
            binding.setThinkingEnabled(null);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(binding));
            when(providerConfigRepository.findById(5L))
                    .thenReturn(Optional.of(provider(5L, true)));

            var resolved = service.resolveSceneConfig(AiScene.AI_ASSISTANT);

            assertThat(resolved.thinkingEnabled()).isEqualTo(AiScene.AI_ASSISTANT.isThinking());
            assertThat(resolved.reasoningEffort()).isNull();
            assertThat(resolved.thinkingBudget()).isNull();
        }

        @Test
        void 未绑定_thinking取场景默认() {
            when(sceneConfigRepository.findBySceneKey(AiScene.LIST_QUERY_DETECT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("TOOL"))
                    .thenReturn(provider(7L, true));

            var resolved = service.resolveSceneConfig(AiScene.LIST_QUERY_DETECT);

            assertThat(resolved.thinkingEnabled())
                    .isEqualTo(AiScene.LIST_QUERY_DETECT.isThinking());
        }
    }

    // ==================== 缓存行为 ====================

    @Nested
    @DisplayName("缓存 — 热路径零 SQL")
    class Cache {

        @Test
        void 二次解析命中缓存不再查库() {
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("QA"))
                    .thenReturn(provider(9L, true));

            service.resolveConfig(AiScene.AI_ASSISTANT);
            service.resolveConfig(AiScene.AI_ASSISTANT);
            service.resolveSceneConfig(AiScene.AI_ASSISTANT);

            verify(sceneConfigRepository, times(1)).findBySceneKey(AiScene.AI_ASSISTANT);
        }

        @Test
        void 失效缓存后重新查库() {
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());
            when(providerConfigService.getChatConfigByRole("QA"))
                    .thenReturn(provider(9L, true));

            service.resolveConfig(AiScene.AI_ASSISTANT);
            service.invalidateSceneCache();
            service.resolveConfig(AiScene.AI_ASSISTANT);

            verify(sceneConfigRepository, times(2)).findBySceneKey(AiScene.AI_ASSISTANT);
        }

        @Test
        void isExplicitlyBound_缓存命中时不触发查询() {
            AiSceneConfig binding = new AiSceneConfig();
            binding.setConfigId(5L);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(binding));
            when(providerConfigRepository.findById(5L))
                    .thenReturn(Optional.of(provider(5L, true)));

            service.resolveSceneConfig(AiScene.AI_ASSISTANT);

            assertThat(service.isExplicitlyBound(AiScene.AI_ASSISTANT)).isTrue();
            // 未触发 existsBySceneKey（缓存路径）
            verify(sceneConfigRepository, never()).existsBySceneKey(any());
        }

        @Test
        void isExplicitlyBound_无缓存时查库且未绑定返回false不抛异常() {
            when(sceneConfigRepository.existsBySceneKey(AiScene.LIST_QUERY_DETECT)).thenReturn(false);

            assertThat(service.isExplicitlyBound(AiScene.LIST_QUERY_DETECT)).isFalse();
            verify(sceneConfigRepository).existsBySceneKey(AiScene.LIST_QUERY_DETECT);
        }

        @Test
        void getExplicitBinding_未绑定返回empty() {
            when(sceneConfigRepository.findBySceneKey(AiScene.LIST_QUERY_DETECT))
                    .thenReturn(Optional.empty());

            assertThat(service.getExplicitBinding(AiScene.LIST_QUERY_DETECT)).isEmpty();
        }
    }

    // ==================== bind / unbind ====================

    @Nested
    @DisplayName("bind/unbind — 绑定管理")
    class Binding {

        @Test
        void 绑定不存在的配置抛IllegalArgumentException() {
            when(providerConfigRepository.existsById(99L)).thenReturn(false);

            assertThatThrownBy(() -> service.bind(AiScene.AI_ASSISTANT, 99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        void 首次绑定新建实体并保存全部字段() {
            when(providerConfigRepository.existsById(5L)).thenReturn(true);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());
            when(sceneConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AiSceneConfig saved = service.bind(AiScene.AI_ASSISTANT, 5L, true, "high", 1024);

            ArgumentCaptor<AiSceneConfig> captor = ArgumentCaptor.forClass(AiSceneConfig.class);
            verify(sceneConfigRepository).save(captor.capture());
            assertThat(captor.getValue().getSceneKey()).isEqualTo(AiScene.AI_ASSISTANT);
            assertThat(captor.getValue().getConfigId()).isEqualTo(5L);
            assertThat(captor.getValue().getThinkingEnabled()).isTrue();
            assertThat(captor.getValue().getReasoningEffort()).isEqualTo("high");
            assertThat(captor.getValue().getThinkingBudget()).isEqualTo(1024);
            assertThat(saved.getConfigId()).isEqualTo(5L);
            // 无事务环境下 afterCommit 立即执行 → 触发版本号失效
            verify(cacheInvalidator).bumpVersion();
        }

        @Test
        void 覆盖绑定复用已有实体() {
            AiSceneConfig existing = new AiSceneConfig();
            existing.setSceneKey(AiScene.AI_ASSISTANT);
            existing.setConfigId(1L);
            when(providerConfigRepository.existsById(5L)).thenReturn(true);
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(existing));
            when(sceneConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.bind(AiScene.AI_ASSISTANT, 5L);

            ArgumentCaptor<AiSceneConfig> captor = ArgumentCaptor.forClass(AiSceneConfig.class);
            verify(sceneConfigRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getConfigId()).isEqualTo(5L);
        }

        @Test
        void unbind删除已有绑定并失效缓存() {
            AiSceneConfig existing = new AiSceneConfig();
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.of(existing));

            service.unbind(AiScene.AI_ASSISTANT);

            verify(sceneConfigRepository).delete(existing);
            verify(cacheInvalidator).bumpVersion();
        }

        @Test
        void unbind未绑定时不动库() {
            when(sceneConfigRepository.findBySceneKey(AiScene.AI_ASSISTANT))
                    .thenReturn(Optional.empty());

            service.unbind(AiScene.AI_ASSISTANT);

            verify(sceneConfigRepository, never()).delete(any());
            verify(cacheInvalidator, never()).bumpVersion();
        }
    }
}
