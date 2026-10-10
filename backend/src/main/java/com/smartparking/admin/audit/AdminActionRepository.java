package com.smartparking.admin.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    @EntityGraph(attributePaths = {"admin"})
    @Query("""
            select a from AdminAction a
            where (:action is null or a.action = :action) and (:targetType is null or a.targetType = :targetType)
            """)
    Page<AdminAction> search(@Param("action") String action, @Param("targetType") String targetType,
                             Pageable pageable);
}
