package com.talentbridge.security;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnExistingTokenImmediatelyAfterSuspension() throws Exception {
        UUID userId = UUID.randomUUID();
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        User suspended = User.builder()
                .email("student@example.com")
                .role(UserRole.STUDENT)
                .status(UserStatus.SUSPENDED)
                .build();
        when(tokenProvider.validateToken("token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(suspended));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new JwtAuthenticationFilter(tokenProvider, userRepository)
                .doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void givesRejectedAccountsOnlyReviewAuthority() throws Exception {
        UUID userId = UUID.randomUUID();
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        User rejected = User.builder()
                .email("company@example.com")
                .role(UserRole.COMPANY)
                .status(UserStatus.REJECTED)
                .build();
        when(tokenProvider.validateToken("token")).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(rejected));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        new JwtAuthenticationFilter(tokenProvider, userRepository)
                .doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ACCOUNT_REVIEW")));
        assertFalse(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_COMPANY")
                        || authority.getAuthority().equals("APPROVED_USER")));
    }
}
