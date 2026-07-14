package com.ssuai.domain.auth.lms;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs short credential mutations independently from connector response handling. */
@Component
class LmsSessionTransactions {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    <T> T inNewTransaction(Supplier<T> operation) {
        return operation.get();
    }
}
