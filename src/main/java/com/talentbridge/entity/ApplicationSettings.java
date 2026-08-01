package com.talentbridge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "application_settings")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationSettings {
    public static final short ID = 1;

    @Id
    private Short id = ID;

    @Column(name = "global_deadline")
    private LocalDateTime globalDeadline;

    @Column(name = "global_deadline_enabled", nullable = false)
    private boolean globalDeadlineEnabled;

    @Column(name = "demo_data_version")
    private Integer demoDataVersion;
}
