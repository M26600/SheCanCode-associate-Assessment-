# IgirePay Idempotency Gateway — The "Pay-Once" Protocol

A production-ready REST API that ensures every payment is charged **exactly once**,
no matter how many times the client retries.

---

## Architecture Diagram
Client (e-commerce shop)
|
|  POST /process-payment
|  Idempotency-Key: <uuid>
|  { "amount": 100, "currency": "RWF" }
v
+------------------------------------------+
|           PaymentController              |
|  1. Validate header and body             |
|  2. Delegate to PaymentService           |
|  3. Set X-Cache-Hit: true on replays     |
+------------------+-----------------------+
|
v
+------------------------------------------+
|            PaymentService                |
|                                          |
|  Key not in store?                       |
|  -> Mark IN_FLIGHT                       |
|  -> Simulate 2s processing               |
|  -> Mark COMPLETE                        |
|  -> Return 201 Created                   |
|                                          |
|  Key found, body matches, COMPLETE?      |
|  -> Return cached response               |
|  -> X-Cache-Hit: true                    |
|                                          |
|  Key found, body DIFFERS?                |
|  -> Return 409 Conflict                  |
|                                          |
|  Key found, IN_FLIGHT? (race condition)  |
|  -> Block until COMPLETE                 |
|  -> Return same result                   |
+------------------+-----------------------+
|
v
+------------------------------------------+
|         IdempotencyStore                 |
|  ConcurrentHashMap<String, Record>       |
|  Atomic compute() for thread safety      |
|  TTL expiry on every key                 |
|  Auto-eviction every 10 minutes          |
+------------------------------------------+

Key State Machine:
(absent/expired) -> IN_FLIGHT -> COMPLETE
                        |
                [concurrent duplicate]
                        |
                   block and wait
                        |
                   returns same result

---

## Setup Instructions

### Prerequisites
- Java 17+
- Maven 3.8+

### Run the server

git clone https://github.com/M26600/SheCanCode-associate-Assessment-.git
cd SheCanCode-associate-Assessment-
mvn spring-boot:run

Server starts on http://localhost:8080

### Run tests

mvn test

---

## API Documentation

### POST /process-payment

Process a payment. Safe to retry — guaranteed to charge exactly once.

#### Required Header

| Header | Description |
|---|---|
| Idempotency-Key | Unique string (UUID recommended). Max 255 chars. |

#### Request Body

{
  "amount": 100,
  "currency": "RWF"
}

---

#### Response — First Request

HTTP 201 Created

{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "idempotencyKey": "your-unique-key",
  "processedAt": "2025-06-01T10:00:02Z"
}

---

#### Response — Duplicate Request (same key and same body)

HTTP 201 Created
X-Cache-Hit: true

{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "idempotencyKey": "your-unique-key",
  "processedAt": "2025-06-01T10:00:02Z"
}

---

#### Response — Key Reused With Different Body

HTTP 409 Conflict

{
  "error": "Idempotency key already used for a different request body."
}

---

#### Response — Missing Header

HTTP 400 Bad Request

{
  "error": "Missing required header: Idempotency-Key"
}

---

### Example curl Commands

First request:
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 100, "currency": "RWF"}'

Safe retry:
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 100, "currency": "RWF"}'

Fraud attempt:
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 500, "currency": "RWF"}'

---

## Design Decisions

### 1. ConcurrentHashMap + compute() for atomicity
The store uses ConcurrentHashMap.compute() which is atomic per key. Two simultaneous
first-requests for the same key cannot both win the slot. No external lock needed.

### 2. Object.wait() and notifyAll() for race conditions
When a duplicate arrives while the first request is still processing, the thread
blocks with synchronized wait(). When the first thread finishes it calls notifyAll()
waking all waiters. A 30-second timeout prevents deadlock.

### 3. Body equality by value
Request body equality is checked with equals() on PaymentRequest comparing
amount and currency exactly. This prevents fraud where someone tries to change
the payment amount using an existing key.

### 4. No external dependencies
Only JDK and Spring Boot. No Redis, no database. Run with a single command.

---

## Developer's Choice: Key Expiry (TTL)

### What it does
Every idempotency key has a 24-hour time-to-live configurable via
idempotency.ttl-hours in application.properties. After expiry the key
is released and a new payment can be made with the same key string.
A background task purges expired records every 10 minutes.

### Why it matters for Fintech
Without TTL, a client reusing key strings across billing periods would be
permanently blocked. A 24-hour window covers all reasonable retry storms
but releases keys for new billing cycles. It also prevents unbounded
memory growth in long-running services.

### How to configure
idempotency.ttl-hours=24

---

## Project Structure

src/main/java/com/igirepay/
|- IdempotencyGatewayApplication.java
|- controller/
|  |- PaymentController.java
|- service/
|  |- PaymentService.java
|  |- IdempotencyStore.java
|- model/
|  |- PaymentRequest.java
|  |- PaymentResponse.java
|  |- IdempotencyRecord.java
|- config/
   |- GlobalExceptionHandler.java