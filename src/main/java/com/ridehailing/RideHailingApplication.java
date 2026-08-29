package com.ridehailing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling exists for one job: the stale ride reaper. Its kill switch is a config row, not this annotation.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RideHailingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideHailingApplication.class, args);
    }
}
