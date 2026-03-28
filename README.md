# Payment Gateway

If you've ever integrated with Safaricom's [Daraja API](https://developer.safaricom.co.ke/) to accept M-Pesa payments in your app, you know the pattern: you send a payment request, get a transaction ID back, and then wait for a callback telling you whether the payment went through. Behind the scenes, the gateway has to authenticate your API key, make sure you haven't sent the same request twice, check your daily limits, process the payment, and POST the result back to your server — retrying if your server is down.

This project is that gateway. It handles M-Pesa STK Push, card payments, and bank transfers. Merchants authenticate with API keys, payments are deduplicated with idempotency locks, and results are delivered via HMAC-signed webhooks with automatic retry.

## What It Does

- **Processes payments** — M-Pesa STK Push, card, and bank transfer, each generating a tracked transaction
- **Prevents duplicate charges** — Redis-backed idempotency locks ensure a retried request never processes twice
- **Rate limits merchants** — sliding window counter per merchant, remaining quota returned in response headers
- **Delivers webhooks** — async callback to merchant's URL with exponential backoff retry (1s → 2s → 4s) and HMAC-signed payloads so merchants can verify authenticity
- **Settles transactions** — batches completed transactions, deducts the 1.5% processing fee, and creates a settlement record
- **Reverses payments** — completed transactions can be reversed, with state validation and audit logging
- **Tracks everything** — every state change (initiated → processing → completed → settled) is recorded in an append-only audit log
- **Tags every request** — a correlation ID is assigned at entry, carried through all logs, and returned in the response header for end-to-end tracing

## How Authentication Works

Every request to `/api/v1/*` requires:

```
X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0
```

For production, requests should also include HMAC signatures:

```
X-Signature: <HMAC-SHA256 of timestamp.method.path.body>
X-Timestamp: <unix epoch seconds>
```

### Endpoints

```
POST   /api/v1/payments              Initiate a payment
GET    /api/v1/payments/{txnId}      Query payment status
GET    /api/v1/payments              List merchant transactions (paginated)
POST   /api/v1/payments/{txnId}/reverse   Reverse a completed payment
POST   /api/v1/payments/settle       Batch settle completed transactions
GET    /api/v1/payments/stats        Transaction analytics by status/method
GET    /api/v1/payments/audit/{txnId}     Full audit trail
GET    /actuator/health              Service health check
```

### Payment Flow

```
Merchant                    Payment Gateway                  Payment Processor
   |                              |                                |
   |-- POST /payments ----------->|                                |
   |   (API key + idempotency)    |                                |
   |                              |-- check idempotency key ------>|
   |                              |-- check rate limit ----------->|
   |                              |-- validate merchant ---------->|
   |                              |-- check daily limit ---------->|
   |                              |                                |
   |                              |-- initiate processing -------->|
   |                              |<--- processing result ---------|
   |                              |                                |
   |                              |-- record audit trail           |
   |<-- 201 PaymentResponse ------|                                |
   |                              |                                |
   |                              |-- async webhook delivery ----->|
   |                              |   (HMAC-signed, retries)       |
   |<-- POST callback (signed) ---|                                |
```

### Request/Response Example

**Initiate M-Pesa STK Push:**
```bash
curl -X POST http://localhost:8787/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0" \
  -d '{
    "paymentMethod": "MPESA_STK",
    "amount": 5000.00,
    "sourceAccount": "+254700000001",
    "reference": "INV-2026-001",
    "description": "Order #12345"
  }'
```

**Response:**
```json
{
  "code": 0,
  "status": "SUCCESS",
  "message": "Request processed",
  "correlationId": "a1b2c3d4e5f6",
  "data": {
    "transactionId": "MP17119283740001",
    "merchantId": "MCH-TESTSHOP",
    "paymentMethod": "MPESA_STK",
    "amount": 5000.00,
    "currency": "KES",
    "status": "COMPLETED",
    "reference": "INV-2026-001",
    "correlationId": "a1b2c3d4e5f6",
    "initiatedAt": "2026-03-26T23:30:00",
    "processedAt": "2026-03-26T23:30:01"
  },
  "timestamp": "2026-03-26T23:30:01"
}
```

## Project Structure

```
src/main/java/com/gateway/
├── PaymentGatewayApplication.java
├── config/
│   ├── AsyncConfig.java              # Async thread pool for webhooks
│   └── ExceptionConfig.java          # Global error handling with correlation IDs
├── controller/
│   └── PaymentController.java        # Versioned REST API (v1)
├── dto/
│   ├── request/
│   │   └── PaymentRequest.java       # Validated payment initiation request
│   └── response/
│       ├── GatewayResponse.java      # Standardized envelope with correlation ID
│       └── PaymentResponse.java      # Payment status response
├── entity/
│   ├── AuditLog.java                 # Immutable audit trail entries
│   ├── Merchant.java                 # API credentials and configuration
│   ├── PaymentTransaction.java       # Core transaction record
│   ├── SettlementBatch.java          # Batched settlement with fees
│   └── WebhookDelivery.java          # Delivery attempts and responses
├── enums/
│   ├── PaymentMethod.java            # MPESA_STK, CARD, BANK_TRANSFER, etc.
│   └── PaymentStatus.java            # INITIATED → PROCESSING → COMPLETED/FAILED → SETTLED
├── repository/
│   ├── AuditLogRepository.java
│   ├── MerchantRepository.java
│   ├── SettlementRepository.java
│   ├── TransactionRepository.java    # Custom queries for aggregation and daily limits
│   └── WebhookRepository.java
├── security/
│   ├── ApiKeyAuthFilter.java         # API key validation filter
│   └── HmacSigner.java              # HMAC-SHA256 request signing/verification
├── service/
│   ├── IdempotencyService.java       # Redis-backed duplicate prevention
│   ├── PaymentService.java           # Core payment lifecycle
│   ├── RateLimitService.java         # Per-merchant sliding window rate limiter
│   └── WebhookService.java           # Async webhook delivery with retry
└── util/
    └── CorrelationIdFilter.java      # Distributed tracing via X-Correlation-ID
```

## Database Design

### Relational (PostgreSQL)

The schema uses indexed tables optimized for the query patterns of a payment system:

- **merchants** — API credentials, callback URLs, daily limits
- **payment_transactions** — Core transaction data with status lifecycle
- **settlement_batches** — Batched settlement records with fee breakdowns
- **webhook_deliveries** — Full delivery history for debugging callbacks
- **audit_log** — Immutable append-only audit trail

Key indexes target the hot paths: transaction lookup by ID, merchant transaction listing, settlement queries, and audit trail retrieval.

### In-Memory (Redis)

- **Idempotency locks** — `SETNX` with 24h TTL prevents duplicate processing
- **Rate limit counters** — Per-merchant sliding window with `INCR` + `EXPIRE`

Both services fall back to `ConcurrentHashMap` when Redis is unavailable, making the application runnable without any external dependencies.

## Running

### Local development (zero dependencies)

```bash
mvn spring-boot:run
```

Starts with H2 in-memory database and in-memory Redis fallback. Swagger UI at [http://localhost:8787/swagger-ui.html](http://localhost:8787/swagger-ui.html).

### With Docker (production-like)

```bash
docker compose up
```

Starts PostgreSQL + Redis + payment-gateway with health checks.

### Test merchant credentials

```
Merchant ID:  MCH-TESTSHOP
API Key:      tk_live_a1b2c3d4e5f6g7h8i9j0
Daily Limit:  KES 5,000,000
```

## Testing

```bash
mvn test
```

14 tests covering:

- M-Pesa STK, card, and bank transfer payment processing
- Payment decline handling (simulated processor rejection)
- Payment reversal with state validation
- Cross-merchant reversal prevention
- Settlement batching with fee calculation
- Transaction retrieval and merchant analytics
- Audit trail lifecycle tracking
- HMAC signature generation, verification, and tamper detection
- Rate limiting and idempotency behavior

## Configuration

| Property | Default | Description |
|---|---|---|
| `gateway.security.hmac-secret` | (base64 key) | HMAC signing secret |
| `gateway.security.max-timestamp-drift-seconds` | 300 | Max clock skew for signed requests |
| `gateway.redis.enabled` | false | Enable Redis (falls back to in-memory) |
| `gateway.webhook.timeout-ms` | 5000 | Webhook delivery timeout |
| `gateway.webhook.max-retries` | 3 | Max webhook delivery attempts |

## Production Considerations

This project demonstrates the patterns. In a production deployment, you would additionally:

- **Encrypt sensitive fields** (account numbers, API secrets) at rest using column-level encryption
- **Add Flyway/Liquibase** for versioned database migrations instead of `schema.sql`
- **Deploy Redis in cluster mode** for HA idempotency and rate limiting
- **Add OpenTelemetry** for distributed tracing across microservices
- **Integrate with real payment processors** (Safaricom Daraja, Stripe, Flutterwave)
- **Add PCI-DSS compliance** measures for card payment handling
- **Set up Prometheus + Grafana** dashboards for transaction volume, latency percentiles, and error rates

## License

MIT
