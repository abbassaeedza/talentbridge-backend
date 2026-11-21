package com.talentbridge.config;

import com.talentbridge.security.AssessmentOidcVerifier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
public class AssessmentSecurityConfig {
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain assessmentChain(HttpSecurity http, AssessmentOidcVerifier verifier) throws Exception {
        // Independent chain; neither application identities nor spoofed Cloud Tasks headers are credentials.
        http.securityMatcher("/internal/assessment-jobs/**").csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("ASSESSMENT_WORKER"))
                .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> r.setStatus(401)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        SecurityContextHolder.clearContext();
                        String header = request.getHeader("Authorization");
                        if (!"POST".equals(request.getMethod()) || header == null || !header.startsWith("Bearer ")
                                || !verifier.verify(header.substring(7))) { response.setStatus(401); return; }
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                "assessment-worker", null, List.of(new SimpleGrantedAuthority("ASSESSMENT_WORKER"))));
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
