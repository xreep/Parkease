package com.smartparking.owner;

import com.smartparking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "owner_profiles")
public class OwnerProfile {

    @Id
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.UNSUBMITTED;

    @Enumerated(EnumType.STRING)
    private DocumentType documentType;

    private String documentKey;
    private String documentContentType;
    private Instant documentSubmittedAt;
    private String payoutUpi;
    private String payoutBankAccount;
    private String payoutIfsc;
    private String payoutAccountName;
    private String rejectionReason;
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static OwnerProfile forUser(User user) {
        OwnerProfile profile = new OwnerProfile();
        profile.setUser(user);
        return profile;
    }
}
