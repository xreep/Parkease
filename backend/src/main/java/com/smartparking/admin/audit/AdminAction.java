package com.smartparking.admin.audit;

import com.smartparking.common.persistence.BaseEntity;
import com.smartparking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row of the admin audit log: who did what to which record. Append-only. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "admin_actions")
public class AdminAction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", updatable = false)
    private User admin;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(nullable = false, updatable = false)
    private String targetType;

    @Column(updatable = false)
    private Long targetId;

    @Column(updatable = false)
    private String details;
}
