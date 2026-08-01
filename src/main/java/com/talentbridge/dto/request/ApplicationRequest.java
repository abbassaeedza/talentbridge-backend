package com.talentbridge.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.UUID;
@Data public class ApplicationRequest {
    @NotNull private UUID projectId;
    @NotNull @Min(1) @Max(5) private Integer rankPosition;
    @Size(max = 2000) private String proposalText;
}
