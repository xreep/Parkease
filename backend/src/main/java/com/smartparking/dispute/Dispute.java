package com.smartparking.dispute;

import com.smartparking.booking.Booking;
import com.smartparking.common.persistence.BaseEntity;
import com.smartparking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A driver's complaint about a booking, answered once by the owner and settled by an admin. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "disputes")
public class Dispute extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by", updatable = false)
    private User raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DisputeCategory category;

    @Column(nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeStatus status = DisputeStatus.OPEN;

    private String ownerResponse;
    private Instant ownerRespondedAt;
    private String adminNotes;

    @Enumerated(EnumType.STRING)
    private DisputeResolution resolution;

    private BigDecimal resolutionAmount;
    private Instant resolvedAt;
}
