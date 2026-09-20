package com.emergentterrain.researchservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Authenticates game-owned reads of the research v1 query API. */
public final class ResearchQueryApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Research-Query-Key";

    private final String expectedKey;

    public ResearchQueryApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey == null ? "" : expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isResearchQueryRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!validKey(request.getHeader(HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                "game-service", null, List.of(new SimpleGrantedAuthority("ROLE_RESEARCH_QUERY")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private boolean validKey(String presentedKey) {
        return !expectedKey.isBlank() && presentedKey != null
                && MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isResearchQueryRequest(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().matches("/api/v1/research/rooms/[^/]+/(papers|stats|papers/[^/]+/(quality-assessments|remediation-assessments|provenance))");
    }
}
