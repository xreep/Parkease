package com.smartparking.review;

import com.smartparking.booking.Booking;
import com.smartparking.common.persistence.BaseEntity;
import com.smartparking.listing.ParkingListing;
import com.smartparking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A driver's rating of a completed booking, with the owner's single public reply. Immutable once posted. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reviews")
public class Review extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", updatable = false)
    private ParkingListing listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", updatable = false)
    private User driver;

    @Column(nullable = false)
    private short rating;

    private String comment;

    private String ownerReply;

    private Instant ownerRepliedAt;
}
