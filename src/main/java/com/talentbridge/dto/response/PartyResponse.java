package com.talentbridge.dto.response;
import com.talentbridge.enums.PartyStatus;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyResponse {
    private UUID id; private String name; private PartyStatus status;
    private String semester; private Integer academicYear;
    private MemberDto leader; private List<MemberDto> members;
    private String supervisorName; private UUID supervisorId;
    private UUID assignedProjectId; private String assignedProjectTitle;
    private LocalDateTime assignedProjectDeadline;
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MemberDto {
        private UUID id; private String firstName; private String lastName;
        private String email; private List<String> skills;
    }
}
