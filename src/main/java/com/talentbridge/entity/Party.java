package com.talentbridge.entity;
import com.talentbridge.enums.PartyStatus;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity @Table(name = "parties")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Party extends BaseEntity {
    @Column(nullable = false, length = 255) private String name;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "leader_id", nullable = false) private User leader;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "party_members",
        joinColumns = @JoinColumn(name = "party_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id"))
    @Builder.Default private Set<User> members = new HashSet<>();
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "supervisor_id") private User supervisor;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private PartyStatus status;
    @Column(length = 50) private String semester;
    private Integer academicYear;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_project_id") private Project assignedProject;
    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rankPosition ASC")
    @Builder.Default private List<Application> applications = new ArrayList<>();
    @OneToOne(mappedBy = "party", cascade = CascadeType.ALL, fetch = FetchType.LAZY) private Submission submission;
}
