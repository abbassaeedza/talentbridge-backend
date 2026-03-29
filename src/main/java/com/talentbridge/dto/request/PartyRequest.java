package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data public class PartyRequest {
    @NotBlank private String name;
    private String semester;
    private Integer academicYear;
}
