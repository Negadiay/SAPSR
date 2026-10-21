package com.sapsr.backend.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkFileNameTest {

    @Test
    @Tag("typical")
    void buildsNameFromTypeSemesterGroupAndLastName() {
        assertThat(WorkFileName.forDownload(work("course_work", 7, "321702", "Терешкова", null)))
                .isEqualTo("КР7_321702_Терешкова.pdf");
        assertThat(WorkFileName.of(work("course_project", 5, "321702", "Терешкова", null))).contains("КП5_321702_Терешкова");
        assertThat(WorkFileName.of(work("diploma", 8, "321702", "Терешкова", null))).contains("ДР8_321702_Терешкова");
    }

    @Test
    @Tag("boundary")
    void oldAccountTakesLastNameFromFullName() {
        assertThat(WorkFileName.of(work("course_work", 7, "321702", null, "Терешкова А.В. (гр. 321702)")))
                .contains("КР7_321702_Терешкова");
    }

    @Test
    @Tag("negative")
    void withoutProfileKeepsStudentsFileName() {
        Submission noProfile = work(null, null, "321702", "Терешкова", null);
        noProfile.setOriginalFileName("моя курсовая.pdf");
        assertThat(WorkFileName.forDownload(noProfile)).isEqualTo("моя курсовая.pdf");
        assertThat(WorkFileName.of(work("course_work", null, "321702", "Терешкова", null))).isEmpty();
        assertThat(WorkFileName.of(work("essay", 7, "321702", "Терешкова", null))).isEmpty();
        assertThat(WorkFileName.of(work("course_work", 7, null, "Терешкова", null))).isEmpty();
    }

    private static Submission work(String type, Integer semester, String group, String lastName, String fullName) {
        User student = new User();
        student.setGroupNumber(group);
        student.setLastName(lastName);
        student.setFullName(fullName);
        Submission s = new Submission();
        s.setStudent(student);
        s.setWorkType(type);
        s.setSemester(semester);
        return s;
    }
}
