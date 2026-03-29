package com.talentbridge.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data public class ChangePasswordRequest {
    @NotBlank private String currentPassword;
    @NotBlank @Size(min=8) private String newPassword;
}
