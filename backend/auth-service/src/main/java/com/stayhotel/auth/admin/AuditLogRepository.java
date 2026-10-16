package com.stayhotel.auth.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByTargetIdOrderByCreatedAtAsc(String targetId);
}
