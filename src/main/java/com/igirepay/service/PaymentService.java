package com.igirepay.service;

import com.igirepay.model.IdempotencyRecord;
import com.igirepay.model.PaymentRequest;
import com.igirepay.model.PaymentResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private static final int PROCESSING_DELAY_MS = 2_000;
    private static final int POLL_INTERVAL_MS    = 100;
    private static final int POLL_TIMEOUT_MS     = 30_000;

    private final IdempotencyStore store;

    public PaymentService(IdempotencyStore store) {
        this.store = store;
    }

    public static class ProcessingResult {
        private final PaymentResponse response;
        private final int httpStatus;
        private final boolean cacheHit;
        private final boolean conflict;
        private final String errorMessage;

        private ProcessingResult(PaymentResponse response, int httpStatus,
                                 boolean cacheHit, boolean conflict, String errorMessage) {
            this.response     = response;
            this.httpStatus   = httpStatus;
            this.cacheHit     = cacheHit;
            this.conflict     = conflict;
            this.errorMessage = errorMessage;
        }

        public static ProcessingResult fresh(PaymentResponse r) {
            return new ProcessingResult(r, 201, false, false, null);
        }

        public static ProcessingResult cached(PaymentResponse r, int originalStatus) {
            return new ProcessingResult(r, originalStatus, true, false, null);
        }

        public static ProcessingResult conflict(String msg) {
            return new ProcessingResult(null, 409, false, true, msg);
        }

        public PaymentResponse getResponse()     { return response; }
        public int             getHttpStatus()   { return httpStatus; }
        public boolean         isCacheHit()      { return cacheHit; }
        public boolean         isConflict()      { return conflict; }
        public String          getErrorMessage() { return errorMessage; }
    }

    public ProcessingResult process(String idempotencyKey, PaymentRequest request)
            throws InterruptedException {

        IdempotencyRecord record = store.putIfAbsentOrExpired(idempotencyKey, request);

        if (!record.getOriginalRequest().equals(request)) {
            return ProcessingResult.conflict(
                    "Idempotency key already used for a different request body.");
        }

        boolean weClaimed = record.isInFlight() &&
                java.time.Duration.between(record.getCreatedAt(), Instant.now()).toMillis() < 500;

        if (!weClaimed && record.isInFlight()) {
            return waitForCompletion(record, idempotencyKey);
        }

        if (record.isComplete()) {
            return ProcessingResult.cached(record.getCachedResponse(), record.getHttpStatus());
        }

        return doProcess(record, request, idempotencyKey);
    }

    private ProcessingResult doProcess(IdempotencyRecord record,
                                       PaymentRequest request,
                                       String idempotencyKey) throws InterruptedException {
        try {
            Thread.sleep(PROCESSING_DELAY_MS);

            String txId = "TXN-" + UUID.randomUUID().toString().toUpperCase().substring(0, 8);
            String msg  = String.format("Charged %.0f %s", request.getAmount(), request.getCurrency());

            PaymentResponse response = new PaymentResponse(
                    "SUCCESS", msg, txId, idempotencyKey, Instant.now());

            synchronized (record) {
                record.setCachedResponse(response);
                record.setHttpStatus(201);
                record.setState(IdempotencyRecord.State.COMPLETE);
                record.notifyAll();
            }

            return ProcessingResult.fresh(response);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private ProcessingResult waitForCompletion(IdempotencyRecord record, String key)
            throws InterruptedException {

        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;

        synchronized (record) {
            while (record.isInFlight()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                record.wait(Math.min(remaining, POLL_INTERVAL_MS));
            }
        }

        if (record.isComplete()) {
            return ProcessingResult.cached(record.getCachedResponse(), record.getHttpStatus());
        }

        return doProcess(record, record.getOriginalRequest(), key);
    }
}