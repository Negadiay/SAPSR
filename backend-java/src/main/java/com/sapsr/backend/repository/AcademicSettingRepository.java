package com.sapsr.backend.repository;

import com.sapsr.backend.entity.AcademicSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcademicSettingRepository extends JpaRepository<AcademicSetting, String> {
}
