package com.ridehailing.ride.matching;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the strategy named by the matching.strategy configuration row. Adding a
 * strategy means adding a @Component; nothing here changes and no caller ever
 * branches on the strategy name.
 */
@Slf4j
@Component
public class DriverMatchingStrategyResolver {

    private final Map<String, DriverMatchingStrategy> strategies;
    private final DriverMatchingStrategy defaultStrategy;
    private final ConfigurationService configurationService;

    public DriverMatchingStrategyResolver(List<DriverMatchingStrategy> strategies,
                                          ConfigurationService configurationService) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(strategy -> normalise(strategy.name()), Function.identity()));
        this.defaultStrategy = Objects.requireNonNull(this.strategies.get(NearestDriverMatchingStrategy.NAME),
                "The NEAREST matching strategy must always be present as the fallback");
        this.configurationService = configurationService;
    }

    public DriverMatchingStrategy resolve() {
        String configured = configurationService.getString(
                ConfigKeys.MATCHING_STRATEGY, NearestDriverMatchingStrategy.NAME);
        DriverMatchingStrategy strategy = strategies.get(normalise(configured));
        if (strategy == null) {
            // A typo in configuration must degrade to a working booking, never to an outage.
            log.warn("Unknown matching strategy '{}', falling back to {}", configured, NearestDriverMatchingStrategy.NAME);
            return defaultStrategy;
        }
        return strategy;
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
}
