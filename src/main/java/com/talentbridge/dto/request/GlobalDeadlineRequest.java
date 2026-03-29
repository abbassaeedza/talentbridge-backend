package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class GlobalDeadlineRequest { @NotNull private LocalDateTime deadline; }
