markdown# 🏦 IgirePay Idempotency Gateway
### The "Pay-Once" Protocol

> A production-ready REST API that guarantees every payment is charged **exactly once** — no matter how many times the client retries.

---

## 🚨 The Problem This Solves

When a customer clicks **"Pay"**, the request is sent but the network lags. The client retries. Without an idempotency layer, **the customer gets charged twice**. This API prevents that entirely.

---

## 🏗️ Architecture Diagram

```text
       [ Client (e-commerce shop) ]
                    │
                    ▼
┌───────────────────────────────────────┐
│           PaymentController           │
├───────────────────────────────────────┤
│ • Validate Idempotency-Key header     │
│ • Validate request body payload       │
│ • Inject X-Cache-Hit: true on replay  │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│            PaymentService             │
├───────────────────────────────────────┤
│ 🆕 New key?                           │
│    └─► State = IN_FLIGHT              │
│    └─► Process payload (2s delay)     │
│    └─► State = COMPLETE + cache value │
│    └─► Return 201 Created             │
│                                       │
│ ✅ Same key + identical body?         │
│    └─► Short-circuit to cached value  │
│    └─► Return X-Cache-Hit: true       │
│                                       │
│ ❌ Same key + different body?         │
│    └─► Abort ──► Return 409 Conflict  │
│                                       │
│ ⏳ Same key + state is IN_FLIGHT?     │
│    └─► Block thread & await resolution│
│    └─► Return identical execution result│
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│           IdempotencyStore            │
├───────────────────────────────────────┤
│ • ConcurrentHashMap (Thread-safe)     │
│ • 24-Hour Time-To-Live (TTL) expiry   │
│ • Automated eviction sweep (Every 10m)│
└───────────────────────────────────────┘
```
---

## ⚙️ Setup Instructions

### Prerequisites
- Java 17+
- Maven 3.8+

### Run the server
```bash
git clone https://github.com/M26600/SheCanCode-associate-Assessment-.git
cd SheCanCode-associate-Assessment-
mvn spring-boot:run
```
Server starts on **http://localhost:8080**

### Run tests
```bash
mvn test
```

---

## 📡 API Documentation

### `POST /process-payment`

| | |
|---|---|
| **URL** | `/process-payment` |
| **Method** | `POST` |
| **Required Header** | `Idempotency-Key: <unique-string>` |

#### Request Body
```json
{
  "amount": 100,
  "currency": "RWF"
}
```

---

### ✅ Response — First Request
HTTP 201 Created
```json
{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "idempotencyKey": "order-abc-123",
  "processedAt": "2025-06-01T10:00:02Z"
}
```

---

### 🔁 Response — Duplicate Request
HTTP 201 Created
X-Cache-Hit: true
```json
{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "idempotencyKey": "order-abc-123",
  "processedAt": "2025-06-01T10:00:02Z"
}
```
> Same `transactionId` — customer was NOT charged again ✅

---

### ❌ Response — Key Reused With Different Body
HTTP 409 Conflict
```json
{
  "error": "Idempotency key already used for a different request body."
}
```

---

### 🧪 Example curl Commands

**First payment:**
```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Safe retry (same key):**
```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Fraud attempt (same key, different amount):**
```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"amount": 500, "currency": "RWF"}'
```

---

## 🧠 Design Decisions

### 1. `ConcurrentHashMap` + `compute()` for atomicity
Guarantees that two simultaneous first-requests for the same key cannot both win the slot. No external lock needed — the JDK handles it atomically.

### 2. `Object.wait()` / `notifyAll()` for race conditions
When a duplicate arrives while the first request is still processing, the thread blocks using `synchronized wait()`. When the first thread finishes it calls `notifyAll()`, waking all waiters immediately. A 30-second timeout prevents deadlock.

### 3. Body equality by value
Uses `equals()` on `PaymentRequest` to compare `amount` and `currency` exactly. Prevents fraud where someone tries to change the payment amount using an existing key.

### 4. No external dependencies
Only JDK + Spring Boot. No Redis, no database required. Start with a single command. For multi-instance deployment, swap `IdempotencyStore` for a Redis-backed implementation — the service layer stays unchanged.

---

## 💡 Developer's Choice: Key Expiry (TTL)

### What it does
Every idempotency key has a **24-hour TTL** (configurable via `idempotency.ttl-hours`). After expiry the key is released and a new payment can be processed with the same key. A background scheduler purges expired records every 10 minutes.

### Why it matters for Fintech
Without TTL, a client reusing key strings across billing periods would be permanently blocked — the next month's charge would silently replay last month's cached response. A 24-hour window covers all reasonable retry storms while releasing keys for new billing cycles. It also prevents **unbounded memory growth** in long-running services.

### Configuration
```properties
# application.properties
idempotency.ttl-hours=24
```

---

## 📁 Project Structure
``` text
src/main/java/com/igirepay/
├── IdempotencyGatewayApplication.java
├── controller/
│   └── PaymentController.java
├── service/
│   ├── PaymentService.java
│   └── IdempotencyStore.java
├── model/
│   ├── PaymentRequest.java
│   ├── PaymentResponse.java
│   └── IdempotencyRecord.java
└── config/
└── GlobalExceptionHandler.java
```
---

## ✅ User Stories Implemented

| Story | Description | Status |
|---|---|---|
| User Story 1 | First payment processed successfully | ✅ |
| User Story 2 | Duplicate returns cached response + X-Cache-Hit | ✅ |
| User Story 3 | Different body with same key returns 409 | ✅ |
| Bonus | Race condition handled with blocking wait | ✅ |
| Developer's Choice | 24-hour TTL with auto-eviction | ✅ |
