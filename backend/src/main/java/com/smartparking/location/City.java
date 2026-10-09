package com.smartparking.location;

import com.smartparking.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cities")
public class City extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "is_capital", nullable = false)
    private boolean capital;

    /** Price-guideline tier: 1 = metro, 2 = other state capital, 3 = everything else. */
    @Column(nullable = false)
    private short tier = 3;

    /** Inactive cities are hidden from the public city lists and suggestions; their listings stay. */
    @Column(nullable = false)
    private boolean active = true;
}
