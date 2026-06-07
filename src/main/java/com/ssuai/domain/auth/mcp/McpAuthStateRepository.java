package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpAuthStateRepository extends JpaRepository<McpAuthStateEntity, String> {

    Optional<McpAuthStateEntity> findByStateAndExpiresAtAfter(String state, Instant now);

    int deleteByExpiresAtBefore(Instant now);
}
