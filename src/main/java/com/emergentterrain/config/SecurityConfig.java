package com.emergentterrain.config;

import com.emergentterrain.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@Profile("!perf")
public class SecurityConfig {

    @Value("${app.e2e.enabled:false}")
    private boolean e2eEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        List<String> openEndpoints = new ArrayList<>(Arrays.asList(
            "/register",
            "/login",
            "/profile/select",
            "/css/**",
            "/js/**",
            "/qr",
            "/qr/**",
            "/game",
            "/game/state",
            "/game/update",
            "/game/confirm",
            "/game/qr/state",
            "/game/snapshot",
            "/game/snapshot/latest",
            "/game/snapshot/load",
            "/game/quest/save",
            "/room/**",
            "/diag/**",
            "/ws/**"
        ));
        if (e2eEnabled) {
            openEndpoints.add("/game/test");
            openEndpoints.add("/game/test-token");
        }
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(openEndpoints.toArray(new String[0])).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/profile/select", true)
                .permitAll()
            )
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login"))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authProvider(UserService userService,
                                                  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }
}
