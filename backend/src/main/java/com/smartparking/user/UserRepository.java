package com.smartparking.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Just the account status, for the per-request suspension check. */
    @Query("select u.status from User u where u.id = :id")
    Optional<UserStatus> findStatusById(@Param("id") Long id);

    /**
     * Admin user search. {@code pattern} is a lower-cased LIKE pattern ({@code %} matches everything), escaped with
     * a backslash; a null role or status matches any.
     */
    @Query(value = """
            select u from User u
            where (:role is null or u.role = :role) and (:status is null or u.status = :status)
              and (lower(u.name) like :pattern escape '\\' or lower(u.email) like :pattern escape '\\')
            """, countQuery = """
            select count(u) from User u
            where (:role is null or u.role = :role) and (:status is null or u.status = :status)
              and (lower(u.name) like :pattern escape '\\' or lower(u.email) like :pattern escape '\\')
            """)
    Page<User> search(@Param("role") Role role, @Param("status") UserStatus status,
                      @Param("pattern") String pattern, Pageable pageable);
}
