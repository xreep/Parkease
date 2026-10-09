package com.smartparking.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
