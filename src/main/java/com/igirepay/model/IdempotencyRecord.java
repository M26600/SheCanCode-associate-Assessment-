package com.igirepay.model;

import java.time.Instant;

public class IdempotencyRecord {

    public enum State { IN_FLIGHT, COMPLETE }

    private final String key;
    private final PaymentRequest originalRequest;
    private final Instant createdAt;
    private final Instant expiresAt;

    private State state;
    private PaymentResponse cachedResponse;
    private int httpStatus;

    public IdempotencyRecord(String key, PaymentRequest originalRequest,
                             Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.originalRequest = originalRequest;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = State.IN_FLIGHT;
    }

    public String getKey() { return key; }

    public PaymentRequest getOriginalRequest() { return originalRequest; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getExpiresAt() { return expiresAt; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public PaymentResponse getCachedResponse() { return cachedResponse; }
    public void setCachedResponse(PaymentResponse cachedResponse) {
        this.cachedResponse = cachedResponse;
    }

    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isComplete() { return state == State.COMPLETE; }
    public boolean isInFlight() { return state == State.IN_FLIGHT; }
}