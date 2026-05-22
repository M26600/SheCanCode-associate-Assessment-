package com.igirepay.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Objects;

public class PaymentRequest {

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    private Double amount;

    @NotNull(message = "currency is required")
    private String currency;

    public PaymentRequest() {}

    public PaymentRequest(Double amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentRequest that)) return false;
        return Objects.equals(amount, that.amount) &&
               Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return "PaymentRequest{amount=" + amount + ", currency='" + currency + "'}";
    }
}