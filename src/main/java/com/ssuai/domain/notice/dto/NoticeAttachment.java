package com.ssuai.domain.notice.dto;

/** An official attachment link published in a notice body. */
public record NoticeAttachment(
        String name,
        String url
) {
}
