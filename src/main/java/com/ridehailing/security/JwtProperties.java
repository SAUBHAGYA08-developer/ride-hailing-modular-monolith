package com.ridehailing.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Technical configuration only. Never DB driven: rotating a JWT secret through
 * a database row would be a security defect.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, long expirationSeconds) {
}
