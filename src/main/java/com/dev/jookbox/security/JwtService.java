package com.dev.jookbox.security;

import com.dev.jookbox.config.JwtProperties;
import com.dev.jookbox.domain.Capability;
import com.dev.jookbox.domain.Membership;
import com.dev.jookbox.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtService {

    private final JwtProperties properties;
    private SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Membership membership) {
        Instant now = Instant.now();
        Set<String> caps = Capability.fromMask(membership.getCapabilities()).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

        return Jwts.builder()
                .setIssuer(properties.issuer())
                .setSubject(membership.getUser().getId().toString())
                .claim("membershipId", membership.getId().toString())
                .claim("roomId", membership.getRoom().getId().toString())
                .claim("roomCode", membership.getRoom().getCode())
                .claim("role", membership.getRole().name())
                .claim("capabilities", caps)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(properties.expiryMinutes(), ChronoUnit.MINUTES)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public AuthenticatedMember parse(String token) {
        Claims claims = Jwts.parserBuilder()
                .requireIssuer(properties.issuer())
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();

        UUID membershipId = UUID.fromString(claims.get("membershipId", String.class));
        UUID roomId = UUID.fromString(claims.get("roomId", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        String roomCode = claims.get("roomCode", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        Object rawCapabilities = claims.get("capabilities");
        Set<String> caps;
        if (rawCapabilities instanceof java.util.Collection<?> collection) {
            caps = collection.stream().map(Object::toString).collect(Collectors.toSet());
        } else {
            caps = Set.of();
        }
        return new AuthenticatedMember(membershipId, userId, roomId, roomCode, role, caps);
    }
}
