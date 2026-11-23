package com.basamak.common;

import com.basamak.auth.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Authorization is enforced here and in services, never only in the UI.
 * - /api/cms/**  : CMS-audience token (issued only after password + TOTP) + staff role checks in services
 * - /api/**      : member token (web or mobile audience)
 * - public       : health, auth endpoints, certificate verification, webhooks, OpenAPI
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter,
                                    @Value("${basamak.web-base-url}") String web,
                                    @Value("${basamak.cms-base-url}") String cms) throws Exception {
        http.csrf(c -> c.disable()) // bearer tokens; cookie refresh has its own header check
            .cors(c -> c.configurationSource(cors(web, cms)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                // SSE: the async re-dispatch of an already authorised stream must not be re-checked
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/health", "/api/public/**",
                        "/api/auth/register", "/api/auth/verify-email", "/api/auth/resend-verification",
                        "/api/auth/login", "/api/auth/login/verify", "/api/auth/login/resend",
                        "/api/auth/refresh", "/api/auth/logout",
                        "/api/auth/forgot-password", "/api/auth/reset-password",
                        "/api/cms-auth/**", "/api/hooks/**",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/cms/**").hasAuthority("AUD_cms")
                .requestMatchers("/api/**").hasAnyAuthority("AUD_web", "AUD_mobile")
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Registered inside the security chain only - not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter f) {
        FilterRegistrationBean<JwtAuthFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }

    private CorsConfigurationSource cors(String web, String cms) {
        CorsConfiguration c = new CorsConfiguration();
        // Capacitor serves the mobile app from https://localhost (Android) and capacitor://localhost (iOS)
        c.setAllowedOrigins(List.of(web, cms, "https://localhost", "capacitor://localhost", "http://localhost"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Request-Id", "Idempotency-Key", "Last-Event-ID"));
        c.setExposedHeaders(List.of("X-Request-Id"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
