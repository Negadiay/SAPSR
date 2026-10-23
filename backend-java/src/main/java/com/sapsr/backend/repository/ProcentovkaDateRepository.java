package com.sapsr.backend.repository;

import com.sapsr.backend.entity.ProcentovkaDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcentovkaDateRepository extends JpaRepository<ProcentovkaDate, ProcentovkaDate.Key> {
    List<ProcentovkaDate> findByAcademicYearAndHalfOrderByStageAsc(Integer academicYear, String half);
}
