package com.ssuai.domain.copilot.policy.service;

import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ErrorCode;

public class PolicyCaseConflictException extends ApiException {

    public PolicyCaseConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
