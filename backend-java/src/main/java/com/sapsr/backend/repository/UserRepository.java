package com.sapsr.backend.repository;

import com.sapsr.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByRole(String role);
    List<User> findByRoleAndGroupNumber(String role, String groupNumber);
    Optional<User> findByEmail(String email);

    /** Наибольший номер тёзки среди студентов группы с таким ФИО; 0 — таких ещё нет. */
    @Query("""
            select coalesce(max(u.namesakeNumber), 0) from User u
            where u.role = 'STUDENT' and u.groupNumber = :group and u.nameKey = :nameKey
            """)
    int maxNamesakeNumber(@org.springframework.data.repository.query.Param("group") String group,
                          @org.springframework.data.repository.query.Param("nameKey") String nameKey);

    /** Сколько студентов группы носят это ФИО: больше одного — тёзки. */
    @Query("""
            select count(u) from User u
            where u.role = 'STUDENT' and u.groupNumber = :group and u.nameKey = :nameKey
            """)
    long countNamesakes(@org.springframework.data.repository.query.Param("group") String group,
                        @org.springframework.data.repository.query.Param("nameKey") String nameKey);

    /** Группы зарегистрированных студентов и число студентов в каждой — для экрана администратора. */
    @Query("""
            select u.groupNumber, count(u) from User u
            where u.role = 'STUDENT' and u.groupNumber is not null
            group by u.groupNumber
            """)
    List<Object[]> countStudentsByGroup();
}