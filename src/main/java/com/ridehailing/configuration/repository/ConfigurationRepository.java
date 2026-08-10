package com.ridehailing.configuration.repository;

import com.ridehailing.configuration.entity.ConfigurationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigurationRepository extends JpaRepository<ConfigurationEntry, Long> {

    Optional<ConfigurationEntry> findByConfigKey(String configKey);
}
