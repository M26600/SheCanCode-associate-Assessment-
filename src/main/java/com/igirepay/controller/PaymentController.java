package com.igirepay.controller;

import com.igirepay.model.PaymentRequest;
import com.igirepay.service.PaymentService;
import com.igirepay.service.PaymentService.ProcessingResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/process-payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<?> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing required header: Idempotency-Key"));
        }

        if (idempotencyKey.length() > 255) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Idempotency-Key must not exceed 255 characters"));
        }

        ProcessingResult result;
        try {
            result = paymentService.process(idempotencyKey, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Request processing was interrupted. Please retry."));
        }

        if (result.isConflict()) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of("error", result.getErrorMessage()));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotency-Key", idempotencyKey);

        if (result.isCacheHit()) {
            headers.add("X-Cache-Hit", "true");
        }

        return ResponseEntity
                .status(result.getHttpStatus())
                .headers(headers)
                .body(result.getResponse());
    }
}