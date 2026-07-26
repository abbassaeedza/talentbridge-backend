package com.talentbridge.dto.response;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import lombok.*;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    @Builder.Default private String tokenType = "Bearer";
    private UserDto user;
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserDto {
        private UUID id; private String email; private String firstName; private String lastName;
        private UserRole role; private UserStatus status; private boolean onboardingComplete;
        private String githubUsername;
    }
}
