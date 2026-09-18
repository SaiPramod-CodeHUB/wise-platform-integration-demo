# Wise Platform Integration Demo

A partner-side integration against the [Wise Platform](https://docs.wise.com/guides) sandbox API, built in Spring Boot and Java 21.

This service plays the role a **partner bank or fintech** plays when it embeds Wise: it quotes an FX rate, creates a recipient, creates a transfer, funds it, and then tracks it to completion through webhooks and a reconciliation job.

Everything runs against the Wise **sandbox**. No real money moves.

---

## Why this exists

Wise Platform is the segment of Wise's business where other financial institutions call the API from inside their own product. Reading the docs tells you the endpoints. It does not tell you where a real integration goes wrong.

So I built one, and wrote down what I hit. The [Integration pitfalls](#integration-pitfalls) section is the part I would want if I were the engineer at the partner bank.

---

## The flow

```
   PARTNER                        THIS SERVICE                      WISE PLATFORM
      |                                 |                                 |
      |  POST /api/v1/transfers         |                                 |
      |  { clientReference, amount }    |                                 |
      |-------------------------------->|                                 |
      |                                 |                                 |
      |                          [claim clientReference]                  |
      |                           atomically — dupes stop here            |
      |                                 |                                 |
      |                                 |  1. POST /v3/.../quotes         |
      |                                 |-------------------------------->|
      |                                 |<-- rate, fee, EXPIRY -----------|
      |                                 |                                 |
      |                          [check expiry buffer]                    |
      |                           too close? re-quote, don't fund         |
      |                                 |                                 |
      |                                 |  2. POST /v1/transfers          |
      |                                 |     + customerTransactionId     |
      |                                 |-------------------------------->|
      |                                 |<-- transfer id -----------------|
      |                                 |                                 |
      |                          [persist BEFORE funding]                 |
      |                                 |                                 |
      |                                 |  3. POST /v3/.../payments       |
      |                                 |-------------------------------->|
      |                                 |<-- funded ----------------------|
      |<-- 200 { status: FUNDED } ------|                                 |
      |                                 |                                 |
      |                                 |<== webhook: state changed ======|
      |                                 |   [verify sig, dedupe, apply]   |
      |                                 |                                 |
      |                                 |  every 5 min: GET /v1/transfers |
      |                                 |  reconcile local vs remote      |
      |                                 |-------------------------------->|
```

| Step | Endpoint | What it does |
|---|---|---|
| 1. Quote | `POST /v3/profiles/{id}/quotes` | Rate, fee, delivery estimate. **Expires.** |
| 2. Recipient | `POST /v1/accounts` | Who gets paid. Fields vary per corridor. |
| 3. Transfer | `POST /v1/transfers` | Carries `customerTransactionId` — the idempotency key. |
| 4. Fund | `POST /v3/profiles/{id}/transfers/{id}/payments` | Moves money from the partner balance. |
| 5. Track | webhooks + `GET /v1/transfers/{id}` | Async settlement. Minutes to days. |

---

## Integration pitfalls

The seven things I would tell another partner engineer before they start.

### 0. The sandbox moved — check which environment you are pointed at

The first thing I hit. Older documentation and most blog posts still reference `api.sandbox.transferwise.tech`, which now returns:

```json
{ "message": "This sandbox environment is no longer available, see .../sandbox-v2-migration" }
```

Sandbox V2 is `api.wise-sandbox.com`, with the portal at `wise-sandbox.com`. Two things that bite:

- **V1 credentials created after 1 April 2025 do not work in V2.** New credentials are required.
- **Test data created after that date did not migrate** and has to be recreated.

The endpoints and webhooks themselves are unchanged, so this is a configuration problem rather than a code one — which is exactly why the base URL is externalised (`WISE_BASE_URL`) rather than compiled in. Swapping environments is an environment variable, not a release.

### 1. `customerTransactionId` is the whole safety story

It is a UUID **the caller generates**, per transfer. If a create call times out, you do not know whether the transfer was created. Retry without an idempotency key and you may pay someone twice.

Generate it once, persist it, reuse it on every retry of that specific transfer. Never regenerate on retry — a fresh UUID on a retry is a second payment wearing a disguise.

This service goes one layer further and is idempotent on the **partner's own** `clientReference` too, so their retry never even reaches Wise. See `TransferStore.putIfAbsent` and the concurrency test.

### 2. Quotes expire, and that is not a bug

Wise holds the FX rate, so Wise carries the currency risk — which is exactly why it cannot hold it open forever.

Failure mode: you quote, show it to a user, they take four minutes filling in recipient details, and funding fails with a confusing error.

This service refuses to fund a quote within a configurable safety buffer of its expiry (`wise.quote.min-remaining-validity-seconds`) and returns **409 with `retryable: true`** telling the caller to re-quote. Failing clearly beats executing at a stale rate.

### 3. Recipient requirements are per-corridor and must be dynamic

India needs IFSC. The UK needs a sort code. The US needs a routing number. The euro area needs IBAN.

Hard-code these and you break the day you add a country. Drive the form off Wise's account-requirements endpoint instead — the API is designed to be asked what it needs.

### 4. Webhooks arrive twice, out of order, and from strangers

Three separate problems, three separate defences, all in `WebhookController`:

- **Verify the signature against the raw body.** Your endpoint is a public URL. Re-serialising parsed JSON changes whitespace and key order and breaks verification — check the bytes you received.
- **De-duplicate on event identity.** At-least-once delivery is normal.
- **Never move state backwards.** A late `processing` event must not overwrite a delivered transfer.

Also: answer `200` fast. A slow response looks like a failed delivery and earns you a retry storm. Acknowledge, then process.

### 5. Reconcile. Webhooks are a convenience, not a guarantee

If a webhook is your only way of learning a payment completed, one dropped delivery means money in a state your system will never learn about.

`ReconciliationService` sweeps every non-terminal transfer on a schedule, asks Wise what actually happened, and corrects local state. Wise is the source of truth; we are a cache that must not drift.

Anything stuck non-terminal past a threshold is **escalated, not silently retried**. A payment that has been "processing" for an hour is a human problem.

### 6. A bug I only found by running it

The reconciliation job originally applied whatever status Wise reported. That looked correct until I ran the failure path and read the log:

```
[RECONCILE] divergence transferId=90002 local='CREATED_UNFUNDED'
            remote='incoming_payment_waiting' — correcting
[STATUS]    transferId=90002 CREATED_UNFUNDED -> incoming_payment_waiting
```

`CREATED_UNFUNDED` is not a Wise status. It is **our** record that the transfer was created but funding failed and a human needs to act. Wise will keep reporting that transfer as awaiting payment — entirely true from its side, and useless from ours.

So reconciliation was quietly erasing the only marker saying this payment was stuck. The money stayed stuck; nothing was tracking it any more. The job built to prevent exactly that was causing it.

**The fix**, in `applyObservedStatus`: an unfunded transfer only clears its flag when the remote status shows funding actually progressed (`processing`, `outgoing_payment_sent`, a refund or a cancellation). `incoming_payment_waiting` means *still not funded*, so the flag stays and the transfer is escalated on every sweep rather than once it goes stale.

The general lesson, and the reason it is worth writing down: **your own internal states and the provider's states are different vocabularies.** Treating a remote status as authoritative over a local flag that means something else is how you lose track of money.

Locked in by the `unfundedFlagSurvivesReconciliation` regression test.

---

## Design decisions

**Money is `BigDecimal`, never `double`.** `0.1 + 0.2` is `0.30000000000000004` in binary floating point. Do that across a ledger and your books disagree with the partner's. Scale comes from the currency (JPY 0dp, most 2dp, KWD 3dp), rounding is explicit `HALF_UP`. Demonstrated in `MoneyTest`.

**Transient and permanent failures are different types.** `WiseTransientException` (429, 5xx, timeouts) is retried with exponential backoff. `WisePermanentException` (4xx) is not — retrying a validation error just burns rate limit and delays telling the partner the truth.

**Non-blocking outbound calls.** A partner integration spends its life waiting on someone else's network. `WebClient` on Reactor Netty means a slow upstream consumes a request, not a thread. I fixed a production thread-exhaustion incident this way at Apple; the lesson transferred directly.

**Persist before funding.** The transfer record is written the moment Wise returns an id, before funding is attempted. A crash between the two steps leaves a recoverable record rather than a payment nobody knows about.

**Compensation over abandonment.** If funding fails, the transfer *exists at Wise but is unfunded* — the most dangerous state in the system, because a real customer is waiting. It is recorded as `CREATED_UNFUNDED` so reconciliation and alerting both see it. Wise auto-cancels unfunded transfers after 14 days, but that is a safety net, not a strategy.

**Everything tunable is in config.** Timeouts, retry windows, reconciliation cadence, expiry buffer. These are what you change under pressure — a config change and a rolling restart beats a code change and a release.

**Error responses tell the caller whether retry is safe.** Every failure returns `retryable` and an `action`. A partner who is afraid to retry leaves money in limbo.

---

## Running it

Requires Java 21 and Maven.

### Option A — no credentials needed (recommended for a quick look)

Wise Platform sandbox access is not fully self-serve; credentials come through partner onboarding. That is reasonable for a payments company and a genuine friction point for a prospective partner's engineers — so the orchestration is written against an interface and ships with an in-memory stand-in:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The whole flow runs with no account. The stub models the behaviours that matter, not just the happy path: quotes carry a real expiry, transfer creation is idempotent on `customerTransactionId`, funding can be made to fail on demand, and transfers change state over time so reconciliation has something real to detect.

```bash
# A live quote - real rate, real fee, real expiry
curl "http://localhost:8080/api/v1/quotes?source=USD&target=INR&amount=1000"

# Send money. Run it TWICE with the same clientReference:
# the second call returns the FIRST transfer. No second payment.
curl -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -d '{"clientReference":"partner-payment-001","sourceCurrency":"USD",
       "targetCurrency":"INR","sourceAmount":1000.00,
       "targetAccountId":12345,"reference":"rent"}'

# Now the interesting part - break the funding step:
curl -X POST http://localhost:8080/demo/fail-next-funding
curl -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -d '{"clientReference":"partner-payment-002","sourceCurrency":"USD",
       "targetCurrency":"INR","sourceAmount":500.00,
       "targetAccountId":12345,"reference":"test"}'

# 503 with retryable:true, and a CREATED_UNFUNDED record.
# Watch the log: reconciliation escalates it every 15 seconds and
# refuses to let Wise's status erase the flag.
curl http://localhost:8080/demo/state
```

### Option B — against the real sandbox

```bash
# 1. Get sandbox credentials from https://wise-sandbox.com
export WISE_API_TOKEN=your-sandbox-token
export WISE_PROFILE_ID=your-profile-id

# 2. Run
mvn spring-boot:run

# 3. Swagger UI
open http://localhost:8080/swagger-ui.html
```

**No credentials are committed.** The token is read from the environment and defaults to empty.

### Try it

```bash
# Quote
curl "http://localhost:8080/api/v1/quotes?source=USD&target=INR&amount=1000"

# Transfer — run this TWICE with the same clientReference.
# The second call returns the first transfer. No second payment.
curl -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -d '{
        "clientReference": "partner-payment-001",
        "sourceCurrency": "USD",
        "targetCurrency": "INR",
        "sourceAmount": 1000.00,
        "targetAccountId": 12345,
        "reference": "rent"
      }'

# What do we know about it?
curl http://localhost:8080/api/v1/transfers/partner-payment-001
```

### Tests

```bash
mvn test
```

The tests worth reading are in `TransferOrchestrationServiceTest`:

| Test | What it proves |
|---|---|
| `doesNotDoublePay` | Two calls, same reference → one transfer at Wise |
| `concurrentDuplicatesCreateOneTransfer` | 10 threads racing the same reference → still one |
| `refusesExpiredQuote` | Stale rate is refused before funding, not executed |
| `fundingFailureIsRecorded` | The dangerous state is visible, never lost |
| `statusNeverGoesBackwards` | Out-of-order webhooks cannot regress a completed transfer |
| `unfundedFlagSurvivesReconciliation` | Reconciliation cannot erase the compensation flag (regression) |

---

## What is deliberately not here

Being honest about scope, because a README that oversells is worse than a small one.

- **Storage is in-memory.** `TransferStore` is a `ConcurrentHashMap`. In production this is a table with a **unique constraint on `client_reference`** — and that constraint, not the application-level check, is what guarantees correctness across multiple instances.
- **Webhook de-duplication is per-instance.** A bounded in-memory set. In production: Redis with a TTL, shared across pods.
- **No outbox.** Cross-service atomicity would use the transactional outbox pattern; here the local write and the API call are sequenced, not atomic.
- **Escalation logs.** In production it raises a Splunk alert or pages someone. Logging and hoping somebody greps is not an escalation.

---

## Layout

```
com.sai.wise
├── client/       WiseClient — the five API calls, retry + circuit breaker
├── config/       Externalised properties, WebClient, OpenAPI
├── controller/   Partner-facing REST surface
├── exception/    Transient vs permanent vs quote-expired
├── model/        Money (BigDecimal), quote, transfer, webhook event
├── service/      Orchestration saga, transfer store, reconciliation job
└── webhook/      Signature verification, idempotent receiver
```

---

Built against the Wise Platform sandbox. Not affiliated with Wise.
