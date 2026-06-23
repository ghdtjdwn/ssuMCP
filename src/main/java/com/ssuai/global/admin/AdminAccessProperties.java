package com.ssuai.global.admin;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Owner-only allowlist for {@code /api/admin/**} operational endpoints
 * ({@code ssuai.admin.*}, ADR 0063).
 *
 * <p>{@code studentIds} is a comma-separated list — prod injects it via the
 * {@code SSUAI_ADMIN_STUDENT_IDS} secret (the repo is public, so the owner's
 * student ID is never committed). An empty list denies everyone
 * (deny-by-default): the dashboard stays locked until an admin ID is configured,
 * which is the safe failure mode for a security gate.</p>
 */
@Component
@ConfigurationProperties(prefix = "ssuai.admin")
public class AdminAccessProperties {

    private List<String> studentIds = List.of();

    public List<String> getStudentIds() {
        return studentIds;
    }

    public void setStudentIds(List<String> studentIds) {
        this.studentIds = studentIds == null ? List.of() : List.copyOf(studentIds);
    }

    /** True iff {@code studentId} is non-blank and present in the allowlist. */
    public boolean isAllowed(String studentId) {
        return studentId != null && !studentId.isBlank() && studentIds.contains(studentId);
    }
}
