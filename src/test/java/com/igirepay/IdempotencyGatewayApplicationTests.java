package com.igirepay;

import com.igirepay.model.PaymentRequest;
import com.igirepay.model.PaymentResponse;
import com.igirepay.service.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyGatewayApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IdempotencyStore store;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/process-payment";
        store.clear();
    }

    private HttpEntity<PaymentRequest> buildRequest(String key, double amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return new HttpEntity<>(new PaymentRequest(amount, currency), headers);
    }

    @Test
    void firstRequest_returns201WithChargeMessage() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<PaymentResponse> resp =
                restTemplate.postForEntity(baseUrl, buildRequest(key, 100, "RWF"), PaymentResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("Charged 100 RWF");
        assertThat(resp.getBody().getTransactionId()).startsWith("TXN-");
        assertThat(resp.getHeaders().getFirst("X-Cache-Hit")).isNull();
    }

    @Test
    void missingIdempotencyKey_returns400() {
        ResponseEntity<String> resp =
                restTemplate.postForEntity(baseUrl, buildRequest(null, 100, "RWF"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateRequest_returnsCachedResponseWithCacheHitHeader() {
        String key = UUID.randomUUID().toString();
        HttpEntity<PaymentRequest> req = buildRequest(key, 200, "RWF");

        ResponseEntity<PaymentResponse> first =
                restTemplate.postForEntity(baseUrl, req, PaymentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<PaymentResponse> second =
                restTemplate.postForEntity(baseUrl, req, PaymentResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("X-Cache-Hit")).isEqualTo("true");
        assertThat(second.getBody().getTransactionId())
                .isEqualTo(first.getBody().getTransactionId());
    }

    @Test
    void sameKeyDifferentBody_returns409Conflict() {
        String key = UUID.randomUUID().toString();

        restTemplate.postForEntity(baseUrl, buildRequest(key, 100, "RWF"), PaymentResponse.class);

        ResponseEntity<String> conflict =
                restTemplate.postForEntity(baseUrl, buildRequest(key, 500, "RWF"), String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody())
                .contains("Idempotency key already used for a different request body");
    }

    @Test
    void concurrentRequestsSameKey_onlyChargesOnce() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        HttpEntity<PaymentRequest> req = buildRequest(key, 300, "RWF");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() ->
                    restTemplate.postForEntity(baseUrl, req, PaymentResponse.class)));
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        List<String> txIds = new ArrayList<>();
        for (Future<ResponseEntity<PaymentResponse>> f : futures) {
            try {
                ResponseEntity<PaymentResponse> r = f.get();
                assertThat(r.getStatusCode().value()).isIn(200, 201);
                txIds.add(r.getBody().getTransactionId());
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        assertThat(txIds).allMatch(id -> id.equals(txIds.get(0)));
    }
}