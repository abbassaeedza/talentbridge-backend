package com.talentbridge.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;
@Data public class AssignSupervisorRequest { @NotNull private UUID supervisorId; }
