package com.smartparking.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One admin-editable setting. The value is kept as text and parsed by {@link PlatformSettings}. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "platform_settings")
public class PlatformSetting {

    @Id
    @Column(name = "key")
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(nullable = false)
    private Instant updatedAt;

    private Long updatedBy;
}
