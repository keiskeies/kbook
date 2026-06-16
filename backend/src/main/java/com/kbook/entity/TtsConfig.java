package com.kbook.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tts_config", indexes = {
        @Index(name = "idx_tts_enabled", columnList = "enabled"),
        @Index(name = "idx_tts_type_provider", columnList = "tts_type, provider")
})
public class TtsConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "tts_type", nullable = false, length = 20)
    private TtsType ttsType;

    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(length = 500)
    private String baseUrl;

    @Column(length = 100)
    private String modelName;

    @Column(length = 500)
    private String apiKey;

    @Column(length = 500)
    private String apiSecret;

    @Column(length = 100)
    private String appId;

    @Column(length = 50)
    private String voice;

    @Column(length = 50)
    private String voicePresetId;

    @Column(length = 20)
    @Builder.Default
    private String language = "zh";

    @Builder.Default
    private Integer speed = 50;

    @Builder.Default
    private Integer pitch = 50;

    @Builder.Default
    private Boolean enabled = true;

    @Builder.Default
    private Boolean isDefault = false;

    @Builder.Default
    private Boolean streaming = false;

    public enum TtsType {
        LLM, TRADITIONAL, CLONE;

        @JsonCreator
        public static TtsType from(String value) {
            if (value == null) return null;
            return valueOf(value.toUpperCase());
        }
    }

    public enum Provider {
        XIAOMI, IFLYTEK, GPT_SOVITS, AZURE, CUSTOM;

        @JsonCreator
        public static Provider from(String value) {
            if (value == null) return null;
            return valueOf(value.toUpperCase());
        }
    }

    @Converter(autoApply = true)
    public static class TtsTypeConverter implements AttributeConverter<TtsType, String> {
        @Override
        public String convertToDatabaseColumn(TtsType attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public TtsType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : TtsType.valueOf(dbData.toUpperCase());
        }
    }

    @Converter(autoApply = true)
    public static class ProviderConverter implements AttributeConverter<Provider, String> {
        @Override
        public String convertToDatabaseColumn(Provider attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public Provider convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Provider.valueOf(dbData.toUpperCase());
        }
    }

    @Override
    public Long getId() {
        return id;
    }
}
