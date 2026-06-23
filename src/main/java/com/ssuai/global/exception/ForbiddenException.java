package com.ssuai.global.exception;

/**
 * Thrown when the caller is authenticated but lacks permission for the target
 * resource (e.g. a non-admin student hitting {@code /api/admin/**}). Mapped by
 * {@link GlobalExceptionHandler} to HTTP 403 with code {@code FORBIDDEN}.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }
}
