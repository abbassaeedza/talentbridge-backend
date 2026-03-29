package com.talentbridge.dto.request;
import com.talentbridge.enums.UserRole;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data public class RegisterRequest {
    @NotBlank @Email private String email;
    @NotBlank @Size(min=8) private String password;
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    @NotNull private UserRole role;
    private String phoneNumber;
    private String companyName;
    private String industry;
    private String companyDescription;
    private String website;
    private String registrationNumber;
}
