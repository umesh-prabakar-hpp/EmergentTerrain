package com.emergentterrain.researchservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Research is an internal service: game-owned query reads use a dedicated shared key, while the
 * work controller retains its own request-level credential. It deliberately exposes no form or
 * HTTP Basic login surface.
 */
@Configuration
@EnableWebSecurity
public class ResearchServiceSecurityConfig {

    @Bean
    ResearchQueryApiKeyFilter researchQueryApiKeyFilter(
            @Value("${app.research-service.query-api-key:}") String queryApiKey) {
        return new ResearchQueryApiKeyFilter(queryApiKey);
    }

    @Bean
    SecurityFilterChain researchServiceSecurityFilterChain(HttpSecurity http,
                                                            ResearchQueryApiKeyFilter queryFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(queryFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        // The controller independently verifies X-Research-Work-Key.
                        .requestMatchers("/api/v1/research/work/**").permitAll()
                        .requestMatchers("/api/v1/research/rooms/**").hasRole("RESEARCH_QUERY")
                        .anyRequest().denyAll());
        return http.build();
    }
}
