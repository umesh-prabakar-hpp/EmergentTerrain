package com.emergentterrain.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Authenticates only the internal read-only game-context request with a configured shared key. */
@Component
public class ResearchContextApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Research-Service-Key";

    private final String expectedKey;

    public ResearchContextApiKeyFilter(@Value("${app.research-context.api-key:}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isResearchContextRequest(request) && validKey(request.getHeader(HEADER))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "research-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_RESEARCH_CONTEXT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean validKey(String presentedKey) {
        return !expectedKey.isBlank() && presentedKey != null
                && MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isResearchContextRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod())
                && path.contains("/api/v1/game/rooms/")
                && path.endsWith("/research-context");
    }
}
