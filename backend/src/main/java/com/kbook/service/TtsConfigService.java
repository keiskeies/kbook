package com.kbook.service;

import com.kbook.entity.TtsConfig;
import com.kbook.repository.TtsConfigRepository;
import com.kbook.service.tts.TtsEngineFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsConfigService {

    private final TtsConfigRepository ttsConfigRepository;
    private final TtsEngineFactory ttsEngineFactory;

    public List<TtsConfig> listAll() {
        return ttsConfigRepository.findByOrderByIsDefaultDescUpdatedAtDesc();
    }

    public TtsConfig getActiveConfig() {
        return ttsConfigRepository.findByIsDefaultTrueAndEnabledTrue().orElse(null);
    }

    @Transactional
    public TtsConfig create(TtsConfig config) {
        if (config.getIsDefault() == null) {
            config.setIsDefault(false);
        }
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            ttsConfigRepository.clearDefaultForOthers(-1L);
        }
        TtsConfig saved = ttsConfigRepository.save(config);
        log.info("TTS config created: id={}, name={}, type={}, provider={}",
                saved.getId(), saved.getName(), saved.getTtsType(), saved.getProvider());
        return saved;
    }

    @Transactional
    public TtsConfig update(Long id, TtsConfig config) {
        TtsConfig existing = ttsConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("TTS 配置不存在"));
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getTtsType() != null) existing.setTtsType(config.getTtsType());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getBaseUrl() != null) existing.setBaseUrl(config.getBaseUrl());
        if (config.getModelName() != null) existing.setModelName(config.getModelName());
        if (config.getApiKey() != null) existing.setApiKey(config.getApiKey());
        if (config.getApiSecret() != null) existing.setApiSecret(config.getApiSecret());
        if (config.getAppId() != null) existing.setAppId(config.getAppId());
        if (config.getVoice() != null) existing.setVoice(config.getVoice());
        if (config.getLanguage() != null) existing.setLanguage(config.getLanguage());
        if (config.getSpeed() != null) existing.setSpeed(config.getSpeed());
        if (config.getPitch() != null) existing.setPitch(config.getPitch());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (Boolean.TRUE.equals(config.getIsDefault()) && !existing.getIsDefault()) {
            ttsConfigRepository.clearDefaultForOthers(id);
            existing.setIsDefault(true);
        }
        TtsConfig saved = ttsConfigRepository.save(existing);
        log.info("TTS config updated: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        ttsConfigRepository.deleteById(id);
        log.info("TTS config deleted: id={}", id);
    }

    @Transactional
    public TtsConfig switchDefault(Long id) {
        TtsConfig config = ttsConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("TTS 配置不存在"));
        ttsConfigRepository.clearDefaultForOthers(id);
        config.setIsDefault(true);
        TtsConfig saved = ttsConfigRepository.save(config);
        log.info("TTS default switched: id={}, name={}", id, saved.getName());
        return saved;
    }

    public byte[] synthesize(String text, Long configId) {
        TtsConfig config;
        if (configId != null) {
            config = ttsConfigRepository.findById(configId)
                    .orElseThrow(() -> new RuntimeException("TTS 配置不存在"));
        } else {
            config = getActiveConfig();
            if (config == null) {
                throw new RuntimeException("未找到启用的 TTS 配置");
            }
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new RuntimeException("TTS 配置未启用");
        }
        return ttsEngineFactory.getEngine(config).synthesize(text, config);
    }
}
