package com.dev.jookbox.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

public class MemberAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedMember principal;
    private final String credentials;

    public MemberAuthentication(AuthenticatedMember principal, String token) {
        super(principal.capabilities().stream()
                .map(cap -> new SimpleGrantedAuthority("CAP_" + cap))
                .collect(Collectors.toSet()));
        this.principal = principal;
        this.credentials = token;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedMember getPrincipal() {
        return principal;
    }

    @Override
    public String getCredentials() {
        return credentials;
    }
}
