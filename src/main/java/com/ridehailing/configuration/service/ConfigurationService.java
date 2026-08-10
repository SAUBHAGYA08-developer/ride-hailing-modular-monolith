package com.ridehailing.configuration.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.configuration.dto.ConfigurationResponse;
import com.ridehailing.configuration.entity.ConfigurationEntry;
import com.ridehailing.configuration.entity.ValueType;
import com.ridehailing.configuration.repository.ConfigurationRepository;
import com.ridehailing.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read side of business configuration, cached in Redis.
 *
 * MySQL stays the source of truth; Redis only removes the per request read.
 * Every Redis failure degrades to a database read rather than to an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationService {

    private final ConfigurationRepository configurationRepository;
    private final StringRedisTemplate redis;
    private final AuditService auditService;

    @Value("${app.cache.configuration-ttl-seconds:300}")
    private long cacheTtlSeconds;

    public String getString(String key) {
        return rawValue(key);
    }

    public String getString(String key, String fallback) {
        try {
            return rawValue(key);
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    public int getInt(String key) {
        return Integer.parseInt(rawValue(key).trim());
    }

    public int getInt(String key, int fallback) {
        try {
            return getInt(key);
        } catch (BusinessException | NumberFormatException ex) {
            log.warn("Falling back to {} for configuration {}", fallback, key);
            return fallback;
        }
    }

    public BigDecimal getDecimal(String key) {
        return new BigDecimal(rawValue(key).trim());
    }

    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        try {
            return getDecimal(key);
        } catch (BusinessException | NumberFormatException ex) {
            log.warn("Falling back to {} for configuration {}", fallback, key);
            return fallback;
        }
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(rawValue(key).trim());
    }

    public boolean getBoolean(String key, boolean fallback) {
        try {
            return getBoolean(key);
        } catch (BusinessException ex) {
            return fallback;
        }
    }

    @Transactional(readOnly = true)
    public List<ConfigurationResponse> findAll() {
        return configurationRepository.findAll().stream()
                .sorted(Comparator.comparing(ConfigurationEntry::getConfigKey))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConfigurationResponse findByKey(String key) {
        return toResponse(loadEntry(key));
    }

    /**
     * Updates a business setting. Optimistic locking on the row protects two
     * administrators editing the same key; the cache entry is evicted after.
     */
    @Transactional
    public ConfigurationResponse update(String key, String newValue) {
        ConfigurationEntry entry = loadEntry(key);
        if (!entry.isEditable()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_EDITABLE,
                    "Configuration " + key + " cannot be modified at runtime");
        }
        validate(entry.getValueType(), key, newValue);

        String oldValue = entry.getConfigValue();
        if (oldValue.equals(newValue)) {
            return toResponse(entry);
        }
        entry.setConfigValue(newValue);
        ConfigurationEntry saved = configurationRepository.saveAndFlush(entry);

        evict(key);
        auditService.record(AuditEntities.CONFIGURATION, key, AuditActions.CONFIGURATION_CHANGED,
                Map.of("value", oldValue), Map.of("value", newValue));
        return toResponse(saved);
    }

    private ConfigurationEntry loadEntry(String key) {
        return configurationRepository.findByConfigKey(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND,
                        "Unknown configuration key: " + key));
    }

    private String rawValue(String key) {
        String cacheKey = RedisKeys.configuration(key);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException ex) {
            log.warn("Configuration cache read failed for {}, reading MySQL", key);
        }

        String value = loadEntry(key).getConfigValue();
        try {
            redis.opsForValue().set(cacheKey, value, Duration.ofSeconds(cacheTtlSeconds));
        } catch (RuntimeException ex) {
            log.warn("Configuration cache write failed for {}", key);
        }
        return value;
    }

    private void evict(String key) {
        try {
            redis.delete(RedisKeys.configuration(key));
        } catch (RuntimeException ex) {
            log.warn("Configuration cache eviction failed for {} - it expires in {}s", key, cacheTtlSeconds);
        }
    }

    private void validate(ValueType type, String key, String value) {
        try {
            switch (type) {
                case INTEGER -> Integer.parseInt(value.trim());
                case DECIMAL -> new BigDecimal(value.trim());
                case BOOLEAN -> {
                    String normalised = value.trim().toLowerCase();
                    if (!normalised.equals("true") && !normalised.equals("false")) {
                        throw new NumberFormatException("not a boolean");
                    }
                }
                case STRING -> {
                    if (value.isBlank()) {
                        throw new NumberFormatException("blank");
                    }
                }
            }
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VALUE_INVALID,
                    "Value '" + value + "' is not a valid " + type + " for " + key);
        }
    }

    private ConfigurationResponse toResponse(ConfigurationEntry entry) {
        return new ConfigurationResponse(entry.getConfigKey(), entry.getConfigValue(), entry.getValueType(),
                entry.getDescription(), entry.isEditable(), entry.getUpdatedAt(), entry.getUpdatedBy());
    }
}
