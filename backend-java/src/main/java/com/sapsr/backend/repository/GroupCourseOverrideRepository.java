package com.sapsr.backend.repository;

import com.sapsr.backend.entity.GroupCourseOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupCourseOverrideRepository extends JpaRepository<GroupCourseOverride, GroupCourseOverride.Key> {
    Optional<GroupCourseOverride> findByGroupNumberAndAcademicYear(String groupNumber, Integer academicYear);

    List<GroupCourseOverride> findByAcademicYear(Integer academicYear);
}
