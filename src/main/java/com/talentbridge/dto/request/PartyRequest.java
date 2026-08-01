package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
@Data public class PartyRequest {
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Pattern(regexp = "Spring|Fall") private String semester;
    @NotNull @Min(2020) @Max(2100) private Integer academicYear;
}
