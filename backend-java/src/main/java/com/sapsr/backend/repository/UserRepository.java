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

    /** Группы зарегистрированных студентов и число студентов в каждой — для экрана администратора. */
    @Query("""
            select u.groupNumber, count(u) from User u
            where u.role = 'STUDENT' and u.groupNumber is not null
            group by u.groupNumber
            """)
    List<Object[]> countStudentsByGroup();
}