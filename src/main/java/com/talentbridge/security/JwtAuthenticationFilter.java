package com.talentbridge.security;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component @RequiredArgsConstructor @Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/internal/assessment-jobs/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = extractToken(req);
            if (StringUtils.hasText(token)) {
                var identity = tokenProvider.parseAccessToken(token);
                userRepository.findById(identity.userId())
                        .filter(user -> user.getStatus() != UserStatus.SUSPENDED
                                && user.getTokenVersion() == identity.version())
                        .ifPresent(user -> {
                            List<SimpleGrantedAuthority> authorities = user.getStatus() == UserStatus.APPROVED
                                    ? List.of(
                                            new SimpleGrantedAuthority("ROLE_" + user.getRole().name()),
                                            new SimpleGrantedAuthority("APPROVED_USER"))
                                    : List.of(new SimpleGrantedAuthority("ACCOUNT_REVIEW"));
                            var auth = new UsernamePasswordAuthenticationToken(identity.userId(), null,
                                    authorities);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        });
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected invalid authentication token");
        } catch (Exception e) { log.error("Auth error", e); }
        chain.doFilter(req, res);
    }

    private String extractToken(HttpServletRequest req) {
        String bearer = req.getHeader("Authorization");
        return (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) ? bearer.substring(7) : null;
    }
}
