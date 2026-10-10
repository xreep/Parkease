package com.smartparking.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Row-locks the account so moderation and the user's own edits cannot overwrite each other (the entity is
     * written in full). Load it only through this method in such a transaction: an instance already in the
     * persistence context would be returned as it was read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    long countByRole(Role role);

    long countByStatus(UserStatus status);

    /** Accounts of the role created in [from, to). */
    long countByRoleAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Role role, Instant from, Instant to);

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
