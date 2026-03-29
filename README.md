# Payment Gateway

If you've ever integrated with Safaricom's [Daraja API](https://developer.safaricom.co.ke/) to accept M-Pesa payments in your app, you know the pattern: you send a payment request, get a transaction ID back, and then wait for a callback telling you whether the payment went through. Behind the scenes, the gateway has to authenticate your API key, make sure you haven't sent the same request twice, check your daily limits, process the payment, calculate the fee, and POST the result back to your server (retrying if your server is down).

This project is that gateway. It handles M-Pesa STK Push, card payments, and bank transfers. Merchants register through the API, authenticate with API keys, and process payments that are deduplicated, rate-limited, and fee-calculated per payment method. Results are delivered via HMAC-signed webhooks. Payments can be partially or fully refunded with overpayment protection. Everything settles into reconcilable batches.

## What It Does

**Payment processing:**
- Accept M-Pesa STK Push, M-Pesa C2B/B2C, card, and bank transfer payments
- Calculate fees per payment method (M-Pesa 1%, card 2.5%, bank 0.5%, with minimum floors)
- Prevent duplicate charges using idempotency keys tied to merchant + reference
- Rate limit per merchant with a sliding window counter
- Enforce daily transaction limits per merchant
- Track every payment through INITIATED, PROCESSING, COMPLETED/FAILED, SETTLED states

**Merchant management:**
- Register new merchants through the API and receive API key + secret
- Rotate API keys without downtime
- Activate/deactivate merchants
- Update callback URLs and daily limits

**Refunds:**
- Full or partial refunds on completed or settled transactions
- Track total refunded amount per transaction to prevent over-refunding
- Multiple partial refunds against the same transaction until the original amount is exhausted
- Separate refund records with their own lifecycle

**Async callbacks:**
- Receive M-Pesa and card processor callbacks at dedicated endpoints
- Update transaction status based on processor result
- Forward status to merchant via HMAC-signed webhook with exponential backoff retry

**Settlement and reconciliation:**
- Batch settle all completed transactions for a merchant
- Calculate net payout after per-transaction fee deduction
- Reconcile settlement batches against actual transaction sums

**Security and observability:**
- API key authentication on all payment endpoints
- HMAC-SHA256 request signing capability
- Correlation ID assigned to every request, carried through logs, returned in response headers
- Structured logging with correlation context
- Full audit trail for every state change

## Quick Start

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8787/swagger-ui.html
```

Or with Docker (PostgreSQL + Redis):

```bash
docker compose up
```

## Try It Out

```bash
# 1. The seed merchant is ready to use
#    API Key: tk_live_a1b2c3d4e5f6g7h8i9j0

# 2. Process an M-Pesa payment
curl -X POST http://localhost:8787/api/v1/payments \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethod":"MPESA_STK","amount":5000,"sourceAccount":"+254700000001","reference":"INV-001"}'

# 3. Process a card payment (higher fee)
curl -X POST http://localhost:8787/api/v1/payments \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethod":"CARD","amount":10000,"sourceAccount":"+254700000001","reference":"INV-002"}'

# 4. Partial refund
curl -X POST http://localhost:8787/api/v1/payments/MP.../refund \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0" \
  -H "Content-Type: application/json" \
  -d '{"amount":2000,"reason":"Item returned"}'

# 5. Register a new merchant
curl -X POST http://localhost:8787/api/v1/merchants \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0" \
  -H "Content-Type: application/json" \
  -d '{"name":"Coffee Shop Nairobi","callbackUrl":"https://myshop.co.ke/webhooks"}'

# 6. Settle and reconcile
curl -X POST http://localhost:8787/api/v1/payments/settle \
  -H "X-API-Key: tk_live_a1b2c3d4e5f6g7h8i9j0"
```

## Fee Structure

| Payment Method | Rate | Minimum Fee |
|---|---|---|
| M-Pesa STK / C2B | 1.0% | KES 5 |
| M-Pesa B2C | 1.5% | KES 5 |
| Card | 2.5% | KES 10 |
| Bank Transfer | 0.5% | KES 25 |

Fees are calculated per transaction and stored on each record. Settlement batches sum actual per-transaction fees instead of applying a flat rate.

## API Reference

### Payments (requires API key)

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/v1/payments` | Initiate a payment |
| GET | `/api/v1/payments/{txnId}` | Query payment status |
| GET | `/api/v1/payments` | List merchant transactions (paginated) |
| POST | `/api/v1/payments/{txnId}/reverse` | Full reversal |
| POST | `/api/v1/payments/{txnId}/refund` | Partial or full refund |
| GET | `/api/v1/payments/{txnId}/refunds` | List refunds for a transaction |
| POST | `/api/v1/payments/settle` | Batch settle completed transactions |
| GET | `/api/v1/payments/settle/{batchId}/reconcile` | Verify settlement totals |
| GET | `/api/v1/payments/stats` | Transaction analytics |
| GET | `/api/v1/payments/audit/{txnId}` | Audit trail |

### Merchants (requires API key)

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/v1/merchants` | Register new merchant |
| GET | `/api/v1/merchants` | List all merchants |
| GET | `/api/v1/merchants/{id}` | Get merchant details |
| PUT | `/api/v1/merchants/{id}/callback` | Update callback URL |
| POST | `/api/v1/merchants/{id}/rotate-key` | Rotate API credentials |
| POST | `/api/v1/merchants/{id}/activate` | Activate merchant |
| POST | `/api/v1/merchants/{id}/deactivate` | Deactivate merchant |
| PUT | `/api/v1/merchants/{id}/daily-limit` | Update daily limit |

### Processor Callbacks (public)

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/callbacks/mpesa` | M-Pesa STK Push result callback |
| POST | `/api/callbacks/card` | Card processor result callback |

## Payment Lifecycle

```
Merchant sends POST /payments
    |
    v
Rate limit check --> Idempotency check --> Daily limit check
    |
    v
Create transaction (INITIATED) --> Calculate fee --> Process (PROCESSING)
    |
    v
Processor result --> COMPLETED or FAILED
    |
    v
Store idempotency result --> Deliver webhook to merchant
    |
    v
Later: SETTLE --> RECONCILE
    |
    v
If dispute: REFUND (partial or full)
```

## Built With

Spring Boot 3.2, Java 17, Spring Data JPA, PostgreSQL (H2 for dev), Redis (in-memory fallback), Spring Boot Actuator, Docker + docker-compose, GitHub Actions CI.

## Tests

```bash
mvn test   # 25 tests
```

**Unit tests (14):** M-Pesa/card/bank transfer processing, payment decline, reversal, cross-merchant reversal prevention, settlement with tiered fees, no-transaction settlement, transaction lookup, audit trail tracking, merchant stats, HMAC signing and tamper detection.

**Integration tests (11):** API key rejection, authenticated payment, card vs M-Pesa fee comparison, partial refund with overpayment prevention, M-Pesa callback status update, transaction lookup via HTTP, merchant registration, settlement with fee breakdown, stats endpoint, correlation ID propagation, idempotency deduplication.

## License

MIT
