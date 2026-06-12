package com.ssuai.domain.lms.dto;

import java.util.List;

public record AssignmentsCompactResponse(
        List<AssignmentCompactItem> items
) {

    public AssignmentsCompactResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static AssignmentsCompactResponse from(AssignmentsResponse response) {
        return new AssignmentsCompactResponse(
                response.items() == null
                        ? List.of()
                        : response.items().stream()
                                .map(AssignmentCompactItem::from)
                                .toList()
        );
    }

    public record AssignmentCompactItem(String title, String dueDate) {

        private static AssignmentCompactItem from(AssignmentItem item) {
            return new AssignmentCompactItem(item.title(), item.dueDate());
        }
    }
}
