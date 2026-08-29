package com.ridehailing.driver.service;

import com.ridehailing.driver.entity.Driver;
import com.ridehailing.driver.entity.DriverStatus;
import com.ridehailing.driver.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Republishes persisted driver positions into the Redis GEO set at startup.
 *
 * The GEO set is a cache, so an empty Redis after a restart would silently make
 * every driver unbookable. This only warms the cache from data Flyway or the
 * application already persisted; it never creates required data itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverLocationWarmupRunner implements ApplicationRunner {

    private final DriverRepository driverRepository;
    private final DriverLocationService driverLocationService;

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        List<Driver> drivers = driverRepository.findByLastKnownLatitudeIsNotNullAndLastKnownLongitudeIsNotNull();
        int published = 0;
        for (Driver driver : drivers) {
            if (driver.getStatus() == DriverStatus.OFFLINE) {
                continue;
            }
            try {
                driverLocationService.updateLocation(driver.getId(),
                        driver.getLastKnownLatitude(), driver.getLastKnownLongitude());
                published++;
            } catch (RuntimeException ex) {
                // Never block startup on a cache problem; drivers repopulate the
                // set with their next location ping.
                log.warn("Could not warm up location for driver {}", driver.getId(), ex);
            }
        }
        log.info("Driver location warm-up published {} of {} known positions", published, drivers.size());
    }
}
