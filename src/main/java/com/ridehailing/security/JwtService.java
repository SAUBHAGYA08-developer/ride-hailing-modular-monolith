package com.ridehailing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PERMISSIONS = "permissions";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes long");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(principal.userId()))
                .claim(CLAIM_EMAIL, principal.email())
                .claim(CLAIM_ROLE, principal.role())
                .claim(CLAIM_PERMISSIONS, List.copyOf(principal.permissions()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofSeconds(properties.expirationSeconds()))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Duration validity() {
        return Duration.ofSeconds(properties.expirationSeconds());
    }

    /** Empty when the token is missing, expired, tampered with or malformed. */
    public Optional<AuthPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object rawPermissions = claims.get(CLAIM_PERMISSIONS);
            Set<String> permissions = new LinkedHashSet<>();
            if (rawPermissions instanceof List<?> list) {
                list.forEach(item -> permissions.add(String.valueOf(item)));
            }

            return Optional.of(new AuthPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    claims.get(CLAIM_ROLE, String.class),
                    Set.copyOf(permissions)));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
