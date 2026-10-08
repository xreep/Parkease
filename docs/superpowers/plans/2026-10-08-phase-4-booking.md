# Phase 4 — Booking, Razorpay Payments, Owner Approvals, Receipts

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Drivers add vehicles, reserve a slot (10-minute hold), pay with Razorpay test mode (or a built-in mock when keys are absent), and get a confirmed booking with QR code and PDF receipt; owners approve or reject requests on manual-approve listings (rejection/timeout refunds in full); availability everywhere accounts for bookings; no double-booking is possible.

**Architecture:** New backend packages `vehicle`, `booking`, `payment`, `invoice`, `earning` (+ jobs). Booking creation re-validates server-side (TimeWindow, evaluator, pricing), allocates a slot under a PostgreSQL exclusion constraint, and creates a provider order. Confirmation is idempotent and triggered by the client-side signature verification or the webhook. Frontend adds a driver area (vehicles, bookings), checkout with Razorpay Checkout.js or a mock dialog, a booking detail/confirmation page with QR + receipt download, and owner booking requests.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / PostgreSQL 16 (`btree_gist`); `com.razorpay:razorpay-java:1.4.10`; `com.github.librepdf:openpdf:3.0.5`; React 19 + TS + Tailwind 4; `qrcode.react` 4.x; TanStack Query; Vitest.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` — §5 (bookings/payments tables), §6.3, §6.4, §6.6 (hold/approval only), §7, §8 and **§19 Phase 4 Decisions** (binding).

**Plan format:** exact DDL, state machine, algorithms and complete tests for the risky core (allocation, signatures, confirmation idempotency); contracts + required test cases elsewhere. Contracts and tests are binding.

## Global Constraints

- Project root: `/Users/adityaraj/my projects/Parkease` (no spaces now; still quote paths). `source ~/.zshrc` before `./mvnw`.
- Branch `phase-4-booking` (already created from `main`). Never push, merge or switch branches.
- Spring Boot 4.1.1, Java 21, package root `com.smartparking`, package-by-feature. Don't rename existing packages.
- All endpoints under `/api/v1`; errors via `ApiException` → ProblemDetail with `code`. Inject `Clock`; never `Instant.now()` in main code.
- Money: `BigDecimal` scale 2 HALF_UP; provider amounts in **paise** (`long`, `amount × 100`).
- Secrets only from env: `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` (all optional; absent key id/secret ⇒ mock provider).
- Booking statuses: `PENDING_PAYMENT, AWAITING_APPROVAL, CONFIRMED, ACTIVE, COMPLETED, CANCELLED, REJECTED, EXPIRED`. Live (block a slot): `PENDING_PAYMENT` (only while `hold_expires_at > now`), `AWAITING_APPROVAL`, `CONFIRMED`, `ACTIVE`.
- Driver-only booking endpoints: non-DRIVER → 403 `DRIVERS_ONLY`. Ownership: another user's booking → 404.
- Scheduled jobs disabled in tests (`app.jobs.enabled: false` in test yml); job logic lives in services with public methods tested directly.
- Frontend: brand ParkEase; existing UI primitives; light/dark; 375 px without horizontal scroll; tests with `renderApp` + `axios-mock-adapter`; never load the real Razorpay script in tests.
- Checks: backend `cd backend && ./mvnw -q test`; frontend `cd frontend && npm test && npm run build && npm run lint`.
- Commits: conventional style + harness-provided `Co-Authored-By` trailer.

## Shared contracts

### Error codes (new)
| Code | HTTP | When |
|---|---|---|
| `DRIVERS_ONLY` | 403 | non-driver calls driver booking/vehicle endpoints |
| `INVALID_PLATE` | 400 | plate doesn't match Indian formats |
| `PLATE_TAKEN` | 409 | same plate twice for one driver |
| `LISTING_UNAVAILABLE` | 409 | listing not APPROVED |
| `SLOT_UNAVAILABLE` | 409 | no free slot for the window/vehicle (property `reason`: CLOSED/BLOCKED/NO_VEHICLE_SLOTS/FULLY_BOOKED) |
| `TOO_MANY_HOLDS` | 409 | driver already has 3 live unpaid holds |
| `HOLD_EXPIRED` | 410 | checkout/verify after the hold expired and slot couldn't be re-taken |
| `PAYMENT_VERIFICATION_FAILED` | 400 | bad signature / order mismatch |
| `INVALID_STATUS` | 409 | action not allowed in current booking status |
| `NOT_PAID` | 409 | receipt requested for an unpaid booking |

### DTOs (JSON field names exact)
```
VehicleDto        { id, type: TWO_WHEELER|FOUR_WHEELER, plateNumber, makeModel|null, isDefault }
BookingSummaryDto { id, bookingCode, status, listingId, listingTitle, cityName, coverPhotoUrl|null,
                    startTime, endTime, vehicleType, plateNumber, totalAmount, createdAt }
BookingDetailDto  { …BookingSummaryDto, address, lat, lng, slotLabel, pricingMode, pricingBreakdown, baseAmount,
                    platformFee, gstAmount, refundAmount, holdExpiresAt|null, approvalDeadline|null,
                    confirmedAt|null, cancelReason|null, cancelledBy|null, paymentStatus|null,
                    invoiceNumber|null, autoApprove, ownerFirstName,
                    events: [{ fromStatus|null, toStatus, actor, note|null, at }] }
CheckoutDto       { booking: BookingDetailDto, payment: { provider: RAZORPAY|MOCK, orderId, amount (paise),
                    currency: "INR", keyId|null, name: "ParkEase", description, prefill: { name, email, contact|null } } }
OwnerBookingDto   { id, bookingCode, status, listingId, listingTitle, slotLabel, startTime, endTime, vehicleType,
                    plateNumber, driverFirstName, baseAmount, approvalDeadline|null, createdAt }
```

## File Map
```
backend/
  pom.xml (+ razorpay-java 1.4.10, openpdf 3.0.5)
  src/main/resources/application.yml (+ app.payments.*, app.jobs.enabled, app.booking.*)
  src/test/resources/application-test.yml (+ app.jobs.enabled: false)
  src/main/resources/db/migration/V6__bookings_payments.sql
  src/main/java/com/smartparking/
    vehicle/Vehicle, VehicleRepository, PlateNumbers, VehicleService, VehicleController, dto/VehicleDto, VehicleRequest
    booking/Booking, BookingStatus, BookingActor, BookingEvent, BookingRepository, BookingEventRepository,
            BookingCodes, SlotAllocator, BookingService, BookingQueryService, BookingController,
            OwnerBookingService, OwnerBookingController, BookingJobs, BookingMapper, dto/*
    payment/Payment, PaymentStatus, Refund, RefundStatus, PaymentRepository, RefundRepository, WebhookEvent,
            WebhookEventRepository, PaymentProperties, PaymentProvider, ProviderOrder, ProviderRefund,
            RazorpayPaymentProvider, MockPaymentProvider, PaymentConfig, Signatures, PaymentService,
            PaymentController, WebhookController, dto/*
    invoice/Invoice, InvoiceRepository, InvoiceService, ReceiptPdf
    earning/OwnerEarning, EarningStatus, OwnerEarningRepository
    availability/AvailabilityEvaluator (modify), search/SearchService + listing/PublicListingService (modify)
    email/EmailTemplates (+ booking templates)
    common/security/SecurityConfig (permit POST /api/v1/payments/webhook)
frontend/
  package.json (+ qrcode.react)
  src/lib/vehicles.ts, bookings.ts, razorpay.ts
  src/pages/driver/DriverLayout, DriverHomePage, VehiclesPage, MyBookingsPage, BookingDetailPage, CheckoutPage
  src/pages/owner/OwnerBookingsPage (+ OwnerLayout tab, OwnerHomePage card)
  src/components/listing/BookingCard (Reserve → booking), components/booking/BookingQr, MockPaymentDialog
  src/App.tsx, Navbar.tsx, README.md
```

---

### Task 1: Schema and entities for vehicles, bookings, payments, invoices, earnings

**Files:** `V6__bookings_payments.sql`; all entity/enum/repository classes in `vehicle`, `booking`, `payment`, `invoice`, `earning` (entities + repositories only); test `booking/BookingSchemaTest.java`.

- [ ] **Step 1: Migration** (use verbatim):

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE vehicles (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(20) NOT NULL CHECK (type IN ('TWO_WHEELER','FOUR_WHEELER')),
    plate_number VARCHAR(15) NOT NULL,
    make_model   VARCHAR(60),
    is_default   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, plate_number)
);

CREATE TABLE bookings (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    booking_code      VARCHAR(12)    NOT NULL UNIQUE,
    driver_id         BIGINT         NOT NULL REFERENCES users (id),
    listing_id        BIGINT         NOT NULL REFERENCES parking_listings (id),
    slot_id           BIGINT         NOT NULL REFERENCES parking_slots (id),
    vehicle_id        BIGINT         REFERENCES vehicles (id) ON DELETE SET NULL,
    vehicle_type      VARCHAR(20)    NOT NULL CHECK (vehicle_type IN ('TWO_WHEELER','FOUR_WHEELER')),
    plate_number      VARCHAR(15)    NOT NULL,
    start_time        TIMESTAMPTZ    NOT NULL,
    end_time          TIMESTAMPTZ    NOT NULL,
    pricing_mode      VARCHAR(10)    NOT NULL,
    pricing_breakdown VARCHAR(100)   NOT NULL,
    base_amount       NUMERIC(10, 2) NOT NULL,
    platform_fee      NUMERIC(10, 2) NOT NULL,
    gst_amount        NUMERIC(10, 2) NOT NULL,
    total_amount      NUMERIC(10, 2) NOT NULL,
    refund_amount     NUMERIC(10, 2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING_PAYMENT','AWAITING_APPROVAL','CONFIRMED','ACTIVE','COMPLETED','CANCELLED','REJECTED','EXPIRED')),
    hold_expires_at   TIMESTAMPTZ,
    approval_deadline TIMESTAMPTZ,
    confirmed_at      TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    cancelled_by      VARCHAR(10)    CHECK (cancelled_by IN ('DRIVER','OWNER','SYSTEM','ADMIN')),
    cancel_reason     VARCHAR(500),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CHECK (end_time > start_time),
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (
        slot_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT','AWAITING_APPROVAL','CONFIRMED','ACTIVE'))
);
CREATE INDEX idx_bookings_driver ON bookings (driver_id, start_time DESC);
CREATE INDEX idx_bookings_listing ON bookings (listing_id, start_time);
CREATE INDEX idx_bookings_hold ON bookings (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_bookings_approval ON bookings (approval_deadline) WHERE status = 'AWAITING_APPROVAL';

CREATE TABLE booking_events (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    booking_id  BIGINT      NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    actor       VARCHAR(10) NOT NULL CHECK (actor IN ('DRIVER','OWNER','SYSTEM','ADMIN')),
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_events_booking ON booking_events (booking_id, created_at);

CREATE TABLE payments (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    booking_id          BIGINT         NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE CASCADE,
    provider            VARCHAR(10)    NOT NULL CHECK (provider IN ('RAZORPAY','MOCK')),
    provider_order_id   VARCHAR(60)    NOT NULL UNIQUE,
    provider_payment_id VARCHAR(60)    UNIQUE,
    amount              NUMERIC(10, 2) NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'INR',
    method              VARCHAR(30),
    status              VARCHAR(20)    NOT NULL CHECK (status IN ('CREATED','CAPTURED','FAILED','REFUNDED','PARTIALLY_REFUNDED')),
    failure_reason      VARCHAR(300),
    captured_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    payment_id         BIGINT         NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    provider_refund_id VARCHAR(60)    UNIQUE,
    amount             NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING','PROCESSED','FAILED')),
    reason             VARCHAR(300),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE owner_earnings (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    booking_id       BIGINT         NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE CASCADE,
    owner_id         BIGINT         NOT NULL REFERENCES users (id),
    gross            NUMERIC(10, 2) NOT NULL,
    commission       NUMERIC(10, 2) NOT NULL,
    net              NUMERIC(10, 2) NOT NULL,
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('HELD','PENDING_PAYOUT','PAID','REVERSED')),
    payout_reference VARCHAR(60),
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_owner_earnings_owner ON owner_earnings (owner_id, status);

CREATE SEQUENCE invoice_number_seq START 1;
CREATE TABLE invoices (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    invoice_number VARCHAR(20) NOT NULL UNIQUE,
    booking_id     BIGINT      NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE CASCADE,
    payment_id     BIGINT      NOT NULL REFERENCES payments (id),
    issued_at      TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_events (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider_event_id VARCHAR(100) NOT NULL UNIQUE,
    event_type        VARCHAR(60)  NOT NULL,
    payload           TEXT         NOT NULL,
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Entities and repositories** (Lombok getters/setters, `BaseEntity` where the table has `id/created_at/updated_at`):
  - `Vehicle` (user ManyToOne LAZY, `VehicleType type`, plateNumber, makeModel, `boolean isDefault` → column `is_default`, getter `isDefault()`/setter `setDefault`). `VehicleRepository`: `findByUserIdOrderByIsDefaultDescCreatedAtAsc`, `findByIdAndUserId`, `existsByUserIdAndPlateNumber`, `countByUserId`.
  - `BookingStatus` enum (8 values), `BookingActor` enum (`DRIVER, OWNER, SYSTEM, ADMIN`).
  - `Booking` (driver, listing, slot, vehicle (nullable) ManyToOne LAZY; `VehicleType vehicleType`; plateNumber; start/end `Instant`; `PricingMode pricingMode`; `pricingBreakdown`; amounts; `BookingStatus status`; holdExpiresAt, approvalDeadline, confirmedAt, completedAt; `BookingActor cancelledBy`; cancelReason). Helper `boolean isLiveAt(Instant now)` = status in {AWAITING_APPROVAL, CONFIRMED, ACTIVE} or (PENDING_PAYMENT and holdExpiresAt > now).
  - `BookingRepository`: `Optional<Booking> findByIdAndDriverId`; `Optional<Booking> findByBookingCode`; `long countByDriverIdAndStatusAndHoldExpiresAtAfter(Long driverId, BookingStatus s, Instant now)`; `@Query` **live overlapping slot ids** for listings: `select b.slot.id from Booking b where b.listing.id in :listingIds and b.startTime < :end and b.endTime > :start and (b.status in (AWAITING_APPROVAL, CONFIRMED, ACTIVE) or (b.status = PENDING_PAYMENT and b.holdExpiresAt > :now))` (use enum literals via fully-qualified names or parameters); `@Modifying` **expire stale holds** on given slots: `update Booking b set b.status = EXPIRED where b.status = PENDING_PAYMENT and b.holdExpiresAt <= :now and b.slot.id in :slotIds` returning count; finders for jobs `findByStatusAndHoldExpiresAtLessThanEqual`, `findByStatusAndApprovalDeadlineLessThanEqual`; paged `findByDriverId…` and owner queries (by `listing.owner.id` and status).
  - `BookingEvent` (+ repo `findByBookingIdOrderByCreatedAtAsc`).
  - `Payment` (booking OneToOne LAZY, `PaymentProvider provider` enum `RAZORPAY|MOCK` named `PaymentProviderType`, orderId, paymentId, amount, currency, method, `PaymentStatus`, failureReason, capturedAt); `PaymentRepository`: `findByBookingId`, `findByProviderOrderId`.
  - `Refund` (+ `RefundStatus`), `RefundRepository.findByProviderRefundId`.
  - `OwnerEarning` (+ `EarningStatus`), repo `findByBookingId`.
  - `Invoice`, `InvoiceRepository.findByBookingId`; native `@Query(value = "select nextval('invoice_number_seq')", nativeQuery = true) long nextNumber()`.
  - `WebhookEvent`, repo `existsByProviderEventId`.
- [ ] **Step 3: Test** `BookingSchemaTest` (`@IntegrationTest`): build an approved listing with one slot via `ListingTestSupport.approvedListingAt(...)` (existing helper) and a driver user; save a CONFIRMED booking 10:00–12:00 tomorrow; saving a second CONFIRMED booking on the same slot 11:00–13:00 → `saveAndFlush` throws `DataIntegrityViolationException`; a second one 12:00–14:00 (touching) saves fine; an EXPIRED overlapping one saves fine; payment/invoice/earning/event rows persist and reload; `invoiceRepository.nextNumber()` increments.
- [ ] **Step 4:** full suite. **Step 5: Commit** `feat: schema for vehicles, bookings, payments, invoices and earnings`

---

### Task 2: Vehicles API

**Files:** `vehicle/PlateNumbers.java`, `VehicleService.java`, `VehicleController.java`, `dto/VehicleDto.java`, `dto/VehicleRequest.java`; test `vehicle/VehicleControllerTest.java`, `vehicle/PlateNumbersTest.java`.

**Contract:**
- `PlateNumbers.normalize(String)` → uppercase, remove spaces/hyphens/dots; `isValid(String normalized)` accepts `^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4}$` (e.g. `MH12AB1234`, `DL3CAB1234`, `KA011234`) or BH series `^[0-9]{2}BH[0-9]{4}[A-Z]{1,2}$` (e.g. `22BH1234AA`).
- `VehicleRequest(@NotNull VehicleType type, @NotBlank String plateNumber, @Size(max=60) String makeModel, Boolean isDefault)`.
- Endpoints (DRIVER only → 403 `DRIVERS_ONLY`; use `@PreAuthorize`-free explicit role check so the error code is ours): `GET /api/v1/me/vehicles` → list (default first); `POST` → 201 VehicleDto (`INVALID_PLATE` 400, `PLATE_TAKEN` 409; the first vehicle becomes default automatically; `isDefault=true` unsets others; max 10 vehicles → 409 `VEHICLE_LIMIT`); `PUT /{id}` (same validation; can change default); `DELETE /{id}` → 204 (if it was default, the oldest remaining becomes default; vehicles referenced by bookings are deleted with `vehicle_id` set null — bookings keep the plate snapshot).
- [ ] **Tests:** PlateNumbers valid/invalid table (`mh 12 ab 1234` → valid `MH12AB1234`; `22 BH 1234 AA` valid; `ABC123` invalid); controller: add first vehicle → default true; add second with isDefault → first loses default; duplicate plate (different spacing) → 409 `PLATE_TAKEN`; invalid plate 400; owner calling → 403 `DRIVERS_ONLY`; delete default promotes the remaining one; another driver's vehicle → 404.
- [ ] **Commit** `feat: driver vehicles with Indian plate validation`

---

### Task 3: Booking-aware availability (search, quote) and midnight rule

**Files:** modify `availability/AvailabilityEvaluator.java`, `search/SearchService.java`, `listing/PublicListingService.java`; tests: extend `AvailabilityEvaluatorTest`, `SearchControllerTest`, `PublicListingControllerTest`.

**Contract:**
- `ListingAvailabilityInput` gains `Set<Long> bookedSlotIds` (slots with a live overlapping booking); keep a convenience constructor/overload so existing callers compile, or update all callers.
- `Result` gains `List<Long> freeSlotIds` (candidate active slots of the vehicle type, minus slot-blocked, minus booked; ordered by slot label ascending; requires slot labels, so sort by `ParkingSlot.getLabel()`).
- `FULLY_BOOKED` when no free slot remains (whether due to slot blocks or bookings).
- **Midnight rule** in `isOpen`: if `endLocal.toLocalTime() == 00:00` and `endLocal.toLocalDate() == startLocal.toLocalDate().plusDays(1)`, treat the window as ending at end of the start day: require the start day's rule with `closeTime == 23:59` (and `start ≥ open`).
- Search and quote load live overlapping bookings in batch via the new repository query (pass `clock.instant()` as `now`) and pass `bookedSlotIds`.
- [ ] **Tests:** evaluator: booking on one of two slots → free 1, `freeSlotIds` = the other; both booked → FULLY_BOOKED; expired-hold PENDING_PAYMENT (holdExpiresAt ≤ now) is not counted — test at the repository/search level; midnight: rule Mon–Sun 08:00–23:59, window 21:00 → 00:00 next day open; same with close 22:00 → CLOSED; window ending 00:15 next day → CLOSED. Search: a CONFIRMED booking on the only slot for the window removes the listing from windowed results but not from non-window results; quote returns `FULLY_BOOKED`.
- [ ] **Commit** `feat: availability accounts for live bookings; allow bookings ending at midnight`

---

### Task 4: Payment provider abstraction (Razorpay + mock) and signatures

**Files:** `pom.xml` (+ `com.razorpay:razorpay-java:1.4.10`); `application.yml` (+ `app.payments`); `payment/PaymentProperties`, `PaymentProvider`, `ProviderOrder`, `ProviderRefund`, `Signatures`, `RazorpayPaymentProvider`, `MockPaymentProvider`, `PaymentConfig`; tests `payment/SignaturesTest.java`, `payment/MockPaymentProviderTest.java`, `payment/PaymentConfigTest.java`.

**Contract:**
- YAML: `app.payments.razorpay.key-id: ${RAZORPAY_KEY_ID:}`, `key-secret: ${RAZORPAY_KEY_SECRET:}`, `webhook-secret: ${RAZORPAY_WEBHOOK_SECRET:}`; `app.booking.hold-minutes: 10`, `app.booking.approval-hours: 2`, `app.booking.max-active-holds: 3`; `app.jobs.enabled: true` (test yml: `false`).
- `PaymentProvider`: `PaymentProviderType type()`; `ProviderOrder createOrder(String receipt, long amountPaise, Map<String,String> notes)` → `ProviderOrder(String orderId, long amountPaise, String currency, String keyId /*null for mock*/)`; `boolean verifyPayment(String orderId, String paymentId, String signature)`; `ProviderRefund refund(String paymentId, long amountPaise, String reason)` → `ProviderRefund(String refundId, RefundStatus status)`; `boolean verifyWebhook(String rawBody, String signature)` (false if no webhook secret).
- `Signatures.hmacSha256Hex(String secret, String data)` (lowercase hex) and `Signatures.matches(String expectedHex, String providedHex)` (constant-time, null-safe).
- Razorpay: order via `new RazorpayClient(keyId, keySecret).orders.create(json{amount, currency:"INR", receipt, notes})`; payment signature = `hmacSha256Hex(keySecret, orderId + "|" + paymentId)`; webhook signature = `hmacSha256Hex(webhookSecret, rawBody)`; refund via `client.payments.refund(paymentId, json{amount, notes:{reason}})` — map Razorpay status `processed` → PROCESSED, else PENDING. Wrap SDK exceptions in `ApiException(502, "PAYMENT_PROVIDER_ERROR", "Payment provider is unavailable. Please try again.")`.
- Mock: secret = SHA-256 of `"mock-payments:" + jwtSecret` (hex); `createOrder` → `order_mock_<16 random hex>`; `verifyPayment` uses the same HMAC rule with the mock secret; extra method `String signForTesting(String orderId, String paymentId)` used by the mock pay endpoint (Task 5); `refund` → `rfnd_mock_<hex>`, PROCESSED; `verifyWebhook` false.
- `PaymentConfig`: `@Bean PaymentProvider` → Razorpay when key id AND secret have text, else Mock (log a WARN "Razorpay keys not set — using the mock payment provider").
- [ ] **Tests:** `SignaturesTest` with a known vector: `hmacSha256Hex("secret", "order_123|pay_456")` equals the value computed by Java's `Mac` in the test itself AND a hard-coded expected string you compute once and paste; `matches` is constant-time-safe for nulls/different lengths. `MockPaymentProviderTest`: order id format, verify accepts `signForTesting` output and rejects tampered ones, refund id format. `PaymentConfigTest`: with no keys → `MockPaymentProvider`; with keys (set via `ApplicationContextRunner` or direct `PaymentConfig` call) → `RazorpayPaymentProvider` (no network call during construction).
- [ ] **Commit** `feat: payment provider abstraction with Razorpay and mock implementations`

---

### Task 5: Reserve, checkout, verify and confirm (idempotent), invoices, earnings, emails

**Files:** `booking/BookingCodes`, `SlotAllocator`, `BookingService`, `BookingMapper`, `BookingController`, `dto/CreateBookingRequest`, `BookingSummaryDto`, `BookingDetailDto`, `CheckoutDto`; `payment/PaymentService`, `PaymentController`, `dto/VerifyPaymentRequest`, `MockPayRequest`; `invoice/InvoiceService`; `email/EmailTemplates` (+4); tests `booking/BookingFlowTest.java`, `booking/SlotAllocatorTest.java`.

**Contract:**
- `BookingCodes.newCode()` → `"PK-"` + 6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (SecureRandom); service retries on unique collision (max 5).
- `POST /api/v1/bookings` body `CreateBookingRequest(@NotNull Long listingId, @NotNull Long vehicleId, @NotNull Instant start, @NotNull Instant end)` → **201 `CheckoutDto`**. Steps (in this order):
  1. role DRIVER else 403 `DRIVERS_ONLY`; `TimeWindow.of(start,end,clock)`;
  2. listing APPROVED else 409 `LISTING_UNAVAILABLE` (unknown → 404); vehicle owned by driver else 404;
  3. live unpaid holds of this driver (`PENDING_PAYMENT` with `holdExpiresAt > now`) ≥ `maxActiveHolds` → 409 `TOO_MANY_HOLDS`;
  4. evaluate availability (rules, slots, overlapping blocks, live overlapping bookings) for `vehicle.type`; unavailable → 409 `SLOT_UNAVAILABLE` with property `reason`;
  5. quote via `PricingService.quote(listing, window)`;
  6. `SlotAllocator.allocate(...)`: for each free slot id in order (max 5 attempts): in a **new transaction** (`TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`) first expire stale holds on that slot (`expireStaleHolds(List.of(slotId), now)`), then insert the booking (`PENDING_PAYMENT`, `holdExpiresAt = now + holdMinutes`) with `saveAndFlush`; on `DataIntegrityViolationException` whose root cause is a `SQLException` with SQLState `23P01`, try the next slot; other exceptions propagate; all attempts fail → 409 `SLOT_UNAVAILABLE` reason `FULLY_BOOKED`. Add a `BookingEvent` (null → PENDING_PAYMENT, DRIVER).
  7. create the provider order (`receipt` = booking code, amount = total in paise, notes `{bookingCode}`), save `Payment` (CREATED); return `CheckoutDto` (prefill from the driver's name/email/phone; description = listing title).
  (Steps 6–7 are outside the outer request transaction boundary as needed; if order creation fails, mark the booking EXPIRED with event note "Payment order failed" and rethrow.)
- `GET /api/v1/bookings/{id}/checkout` → `CheckoutDto` again if `PENDING_PAYMENT` and hold valid; hold passed → 410 `HOLD_EXPIRED`; other statuses → 409 `INVALID_STATUS`.
- `POST /api/v1/payments/verify` body `VerifyPaymentRequest(@NotNull Long bookingId, @NotBlank String orderId, @NotBlank String paymentId, @NotBlank String signature)` → `BookingDetailDto`. Booking must belong to the driver; the payment's `providerOrderId` must equal `orderId`; `provider.verifyPayment` false → 400 `PAYMENT_VERIFICATION_FAILED`; else `PaymentService.confirmPayment(orderId, paymentId, method=null, actor=DRIVER)`.
- `PaymentService.confirmPayment(orderId, paymentId, method, actor)` — **idempotent**, row-locks the payment (`@Lock(PESSIMISTIC_WRITE)` finder):
  - payment already CAPTURED with the same paymentId → return current booking (no side effects);
  - set payment CAPTURED, `providerPaymentId`, `method`, `capturedAt`;
  - booking `PENDING_PAYMENT` → if `listing.autoApprove`: `CONFIRMED` (`confirmedAt = now`) else `AWAITING_APPROVAL` (`approvalDeadline = now + approvalHours`); clear `holdExpiresAt`;
  - booking `EXPIRED` (late payment): in a new transaction try to set it back to the live target status (constraint may reject) — success → continue as above with event note "Payment received after hold expired"; constraint violation → booking `CANCELLED` (cancelledBy SYSTEM, reason "Slot was taken before the payment arrived"), full refund (Task 7's `RefundService.refundFull`; implement the minimal refund call here if Task 7 isn't done — keep it in one place), and return;
  - any other status → log and return (no change);
  - create `Invoice` (`INV-<yyyy>-<6-digit zero-padded seq>`, `issuedAt = now`), `OwnerEarning` (`gross = base`, `commission = platformFee`, `net = base`, `HELD`), `BookingEvent`;
  - emails: driver `bookingConfirmed` (CONFIRMED) or `bookingRequested` (AWAITING_APPROVAL); owner `newBookingForOwner` (CONFIRMED) or `bookingApprovalNeeded` (AWAITING_APPROVAL, includes deadline); links to `/driver/bookings/{id}` and `/owner/bookings`.
- Mock-only: `POST /api/v1/payments/mock/pay` body `MockPayRequest(@NotNull Long bookingId)` → `{orderId, paymentId, signature}` (driver must own the booking; 404 `NOT_FOUND` when the active provider isn't the mock). Used by the frontend mock dialog, which then calls `/payments/verify` like Razorpay's handler.
- Email templates (subjects exact): `bookingConfirmed` "Booking confirmed – ParkEase", `bookingRequested` "Booking request sent – ParkEase", `newBookingForOwner` "New booking – ParkEase", `bookingApprovalNeeded` "Approve a booking request – ParkEase". Bodies include booking code, listing title, formatted IST window (`EEE d MMM, h:mm a` in Asia/Kolkata), slot label, total.
- [ ] **Tests** (`BookingFlowTest`, `@IntegrationTest`; helper to create a verified driver with a vehicle; the test profile uses the mock provider):
  1. reserve on an auto-approve listing → 201, `booking.status` PENDING_PAYMENT, `holdExpiresAt` ≈ now+10 min, `payment.provider` MOCK, `payment.amount` = total×100; mock pay → verify → 200 CONFIRMED, `invoiceNumber` matches `INV-\d{4}-\d{6}`; earning row HELD with gross = base, commission = fee; driver + owner emails sent; verifying again → 200 and no second invoice/email (idempotent).
  2. manual-approve listing → AWAITING_APPROVAL with `approvalDeadline` ≈ now+2 h; owner email subject "Approve a booking request – ParkEase".
  3. bad signature → 400 `PAYMENT_VERIFICATION_FAILED`; orderId of another booking → 400.
  4. second driver reserving the only slot for an overlapping window → 409 `SLOT_UNAVAILABLE` reason FULLY_BOOKED; after the first hold is forced expired (set `holdExpiresAt` in the past via repository) the second driver succeeds.
  5. `TOO_MANY_HOLDS` on the 4th unpaid hold (four different listings or windows).
  6. owner role → 403 `DRIVERS_ONLY`; another driver's vehicle → 404; 30-minute window → 400 `INVALID_TIME_RANGE`; draft listing → 409 `LISTING_UNAVAILABLE`.
  7. late payment: hold expired (status EXPIRED via job method or repository), slot still free → verify → CONFIRMED with event note; if another booking took the slot meanwhile → CANCELLED + refund row PROCESSED (mock) for the full total.
  8. `GET /checkout` after expiry → 410 `HOLD_EXPIRED`.
  `SlotAllocatorTest`: with two free slots where the first gets taken concurrently (simulate by inserting a conflicting CONFIRMED booking on slot 1 just before allocate), allocation lands on slot 2.
- [ ] **Commit** `feat: reserve with slot hold, payment verification and idempotent confirmation`

---

### Task 6: Razorpay webhook

**Files:** `payment/WebhookController.java`; `SecurityConfig` (permit `POST /api/v1/payments/webhook`); test `payment/WebhookControllerTest.java`.

**Contract:** `POST /api/v1/payments/webhook` (public; raw `String` body; header `X-Razorpay-Signature`; event id header `X-Razorpay-Event-Id`, fall back to `sha256(body)` when absent). If `provider.verifyWebhook(body, sig)` is false → 400 `INVALID_SIGNATURE` (also when no webhook secret configured). Duplicate event id → 200 `{"status":"duplicate"}` without processing. Parse with `org.json.JSONObject`: `event` = `payment.captured` → `confirmPayment(payload.payment.entity.order_id, …id, …method, SYSTEM)`; `payment.failed` → payment FAILED with `error_description` (booking unchanged); `refund.processed` / `refund.failed` → update `Refund` by `provider_refund_id`. Unknown events → 200 ignored. Store a `WebhookEvent` row (processed_at set) for every accepted event. Always 200 for processed/ignored.
- [ ] **Tests:** use a test-only `PaymentProvider` bean (`@TestConfiguration` with `@Primary`) whose `verifyWebhook` checks HMAC with a known test secret, or set `app.payments.razorpay.webhook-secret` in a nested test profile; cases: valid `payment.captured` for a pending booking → booking confirmed; same event id again → duplicate, nothing changes; bad signature → 400; `payment.failed` → payment FAILED; unknown event → 200.
- [ ] **Commit** `feat: Razorpay webhook with signature check and idempotent processing`

---

### Task 7: Owner approvals, refunds and booking jobs

**Files:** `payment/RefundService.java`; `booking/OwnerBookingService.java`, `OwnerBookingController.java`, `BookingJobs.java`, `dto/OwnerBookingDto.java`; `email/EmailTemplates` (+3); main class `@EnableScheduling`; tests `booking/OwnerBookingControllerTest.java`, `booking/BookingJobsTest.java`.

**Contract:**
- `RefundService.refundFull(Booking b, BookingActor actor, String reason)`: refunds `total` (paise) via provider for the CAPTURED payment; saves `Refund` (status from provider); payment → REFUNDED; `booking.refundAmount = total`; earning → REVERSED; event. No payment captured → no-op (returns false).
- Owner endpoints (`/api/v1/owner/bookings`, role OWNER via existing path rule; ownership = listing owner, else 404):
  - `GET ?status=AWAITING_APPROVAL|CONFIRMED|…&page&size` → `PageResponse<OwnerBookingDto>`; `view=upcoming` (start ≥ now, live statuses) / `view=past` alternatives allowed — implement `status` filter plus `view` param (`requests` = AWAITING_APPROVAL, `upcoming` = CONFIRMED/ACTIVE with end > now, `past` = everything else), default `requests`; ordered: requests by approvalDeadline asc, upcoming by start asc, past by start desc.
  - `POST /{id}/approve` → only AWAITING_APPROVAL (and deadline not passed) else 409 `INVALID_STATUS`; → CONFIRMED, `confirmedAt`, event (OWNER); email driver `bookingApproved` ("Your booking is confirmed – ParkEase").
  - `POST /{id}/reject` body `{reason}` (NotBlank ≤500) → only AWAITING_APPROVAL; → REJECTED, `cancelledBy` OWNER, `cancelReason`; `refundFull`; email driver `bookingRejected` ("Booking request declined – ParkEase", includes reason and "A full refund of ₹<total> is on its way").
- `BookingJobs` (`@Scheduled(fixedDelay = 60000)` methods guarded by `app.jobs.enabled`; logic in public methods taking no args and using `Clock`):
  - `expireHolds()` → PENDING_PAYMENT with `holdExpiresAt <= now` → EXPIRED (event SYSTEM "Payment window expired"); payment CREATED → FAILED reason "Hold expired".
  - `autoRejectOverdue()` → AWAITING_APPROVAL with `approvalDeadline <= now` → REJECTED (`cancelledBy` SYSTEM, reason "The owner didn't respond within 2 hours"), `refundFull`, email `bookingAutoRejected` ("Booking request expired – ParkEase").
- [ ] **Tests:** owner sees request; approve → CONFIRMED + driver email; reject → REJECTED, refund row PROCESSED for the full total, payment REFUNDED, earning REVERSED, driver email contains the reason; other owner → 404; approve twice → 409; `BookingJobsTest` with a mutable test clock (`@TestConfiguration` providing a `MutableClock` `@Primary` bean, or invoke services with a fixed `Clock` constructed in the test): hold older than 10 min → EXPIRED; overdue approval → REJECTED + refunded; not overdue → unchanged.
- [ ] **Commit** `feat: owner booking approvals, full refunds and hold/approval jobs`

---

### Task 8: Driver booking queries and PDF receipt

**Files:** `booking/BookingQueryService.java` (+ endpoints in `BookingController`); `invoice/ReceiptPdf.java`; `pom.xml` (+ OpenPDF 3.0.5 — package names in 3.x may differ from 2.x (`org.openpdf.text` vs `com.lowagie.text`); use what the jar provides); tests `booking/DriverBookingQueriesTest.java`, `invoice/ReceiptPdfTest.java`.

**Contract:**
- `GET /api/v1/bookings?view=upcoming|past|all&page&size` (default `upcoming`): upcoming = end > now and status in {PENDING_PAYMENT (hold valid), AWAITING_APPROVAL, CONFIRMED, ACTIVE} ordered start asc; past = the rest ordered start desc; → `PageResponse<BookingSummaryDto>`.
- `GET /api/v1/bookings/{id}` → `BookingDetailDto` (events chronological; `paymentStatus` from payment; `invoiceNumber`; `ownerFirstName`; address/lat/lng/slotLabel).
- `GET /api/v1/bookings/{id}/receipt` → `application/pdf` attachment `ParkEase-<invoiceNumber>.pdf`; only when an invoice exists else 409 `NOT_PAID`. PDF content (A4): "ParkEase" header, "Tax invoice / Receipt", invoice number, issue date (IST), booking code, driver name + email, listing title + address, slot label, vehicle plate, window (IST), table rows Parking (<breakdown>) / Platform fee / GST on platform fee (18%) / **Total paid**, payment id + method, refund line when `refundAmount > 0`, footer "This is a computer-generated receipt."
- [ ] **Tests:** upcoming/past split and ordering; another driver's booking → 404; receipt for a confirmed booking → 200, content type pdf, body starts with `%PDF`, and text extraction (OpenPDF `PdfTextExtractor` or simply searching the raw bytes for the invoice number when uncompressed) contains the invoice number; unpaid → 409 `NOT_PAID`.
- [ ] **Commit** `feat: driver booking history API and PDF receipts`

---

## Frontend tasks

Conventions for Tasks 9–12:
- Data layer in `src/lib/vehicles.ts`, `src/lib/bookings.ts`, `src/lib/razorpay.ts`; query keys `['vehicles']`, `['bookings', view, page]`, `['booking', id]`, `['checkout', id]`, `['owner','bookings', view, page]`; invalidate after mutations (including `['quote', …]` and `['search', …]` prefixes after a booking is created or changes status).
- Money via `formatINR`; times via `formatWindow`/`formatDateTime` (show "IST" hint rules from Phase 3 where times are edited).
- Never load `https://checkout.razorpay.com/v1/checkout.js` in tests — `razorpay.ts` exposes `loadRazorpay(): Promise<boolean>` and `openRazorpay(options): Promise<RazorpaySuccess>` which tests mock with `vi.mock`.

### Task 9: Driver area, vehicles page and data layer

**Files:** `package.json` (+ `qrcode.react`); `src/lib/vehicles.ts`, `src/lib/bookings.ts` (types mirroring the DTO tables + API functions + hooks); `src/pages/driver/DriverLayout.tsx`, `DriverHomePage.tsx`, `VehiclesPage.tsx`; routes `/driver` (home), `/driver/vehicles`, `/driver/bookings` (Task 11), `/driver/bookings/:id` (Task 11), `/checkout/:bookingId` (Task 10, driver-only); `RoleHomePage` no longer used for drivers; Navbar: drivers see "My bookings" → `/driver/bookings`; tests `src/pages/driver/Vehicles.test.tsx`.

**Behaviour:**
- `DriverLayout`: heading "My parking" + `Tabs` [Overview `/driver` (end), Bookings `/driver/bookings`, Vehicles `/driver/vehicles`].
- `DriverHomePage`: "Welcome, <first name>"; next upcoming booking card (from `GET /bookings?view=upcoming&size=1`) with code, listing, window and link "View booking", or "No upcoming bookings." + button-link "Find parking" → `/search`; "Your vehicles" count + link "Manage vehicles".
- `VehiclesPage`: list (plate in monospace, type label "Car"/"Two-wheeler", make/model, "Default" badge) with "Make default", "Edit", "Delete" (confirm Dialog "Delete <plate>?"); form "Add a vehicle": `Select` "Vehicle type", "Number plate" (placeholder "MH 12 AB 1234"), "Make and model (optional)", checkbox "Use as default", button "Add vehicle"; client validation mirrors `PlateNumbers` (normalise spaces/hyphens, same regexes) → "Enter a valid Indian number plate"; server `PLATE_TAKEN` → field error "You've already added this vehicle"; empty state "Add your vehicle to start booking."
- [ ] **Tests:** add vehicle normalises "mh 12 ab 1234" → POST body `plateNumber: 'MH12AB1234'`; invalid plate shows the message and no POST; PLATE_TAKEN shows the field error; delete asks for confirmation then DELETEs; driver home shows the next booking from the mocked API; owner visiting `/driver/vehicles` is redirected.
- [ ] **Commit** `feat(frontend): driver area with vehicle management`

### Task 10: Reserve → checkout → payment (Razorpay or mock)

**Files:** `src/lib/razorpay.ts`; `src/components/listing/BookingCard.tsx` (Reserve for drivers); `src/components/booking/MockPaymentDialog.tsx`; `src/pages/driver/CheckoutPage.tsx`; tests `src/pages/driver/Checkout.test.tsx`, update `ListingPage.test.tsx`.

**Behaviour:**
- BookingCard Reserve for a signed-in **driver** (replaces the "next update" helper): if the driver has no vehicles → open a `Dialog` "Add your vehicle" with the same add form (type, plate, make/model) → after adding, continue; otherwise a `Select` "Vehicle" in the card lists the driver's vehicles of the selected vehicle type (default vehicle preselected; the vehicle-type select stays in sync with the chosen vehicle). Clicking "Reserve" → `POST /bookings` → navigate `/checkout/<bookingId>`. Errors: `SLOT_UNAVAILABLE` → "Sorry, that slot was just taken. Try different times."; `TOO_MANY_HOLDS` → "You have unpaid reservations. Complete or wait for them to expire."; others → server detail. Signed-out and owner/admin behaviour unchanged.
- `CheckoutPage` (`/checkout/:bookingId`, driver-only): loads `GET /bookings/{id}/checkout` (410 → "Your reservation expired" + button "Search again" linking back to the listing with the same times); shows listing title, cover, address, window + duration, vehicle plate, slot label, price breakdown (Parking (<breakdown>), Platform fee, GST on fee, **Total**), countdown "Slot held for mm:ss" (turns red under 2 min; at 0 shows the expired state), cancellation policy text, and the button "Pay ₹<total>".
  - RAZORPAY provider: `loadRazorpay()` then `openRazorpay({ key: keyId, amount, currency, order_id: orderId, name, description, prefill, theme: { color: '#059669' } })`; on success → `POST /payments/verify` → navigate `/driver/bookings/<id>?new=1`; on dismissal → stay, show "Payment cancelled. You can try again while your slot is held."; on `payment.failed` → "Payment failed: <description>".
  - MOCK provider: open `MockPaymentDialog` titled "Test payment" with text "Razorpay keys aren't configured, so this simulates a payment.", amount, buttons "Pay ₹<total>" (calls `POST /payments/mock/pay` then `/payments/verify`) and "Simulate failure" (shows "Payment failed: Simulated failure").
  - Verify errors → `FormError` with the server detail.
- [ ] **Tests:** driver with no vehicles clicking Reserve gets the add-vehicle dialog; with a vehicle, Reserve POSTs `{listingId, vehicleId, start, end}` and navigates to `/checkout/91`; SLOT_UNAVAILABLE message; checkout shows the countdown text and Pay button with formatted total; MOCK flow posts mock/pay then verify with the returned signature and navigates to `/driver/bookings/91?new=1`; RAZORPAY flow calls the mocked `openRazorpay` with `order_id` and `key` and then verify; dismissal message; 410 expired state.
- [ ] **Commit** `feat(frontend): reserve and checkout with Razorpay or test payments`

### Task 11: Booking detail (confirmation, QR, receipt) and My bookings

**Files:** `src/components/booking/BookingQr.tsx`; `src/pages/driver/BookingDetailPage.tsx`, `MyBookingsPage.tsx`; tests `src/pages/driver/Bookings.test.tsx`.

**Behaviour:**
- `BookingDetailPage` (`/driver/bookings/:id`): with `?new=1` show a success banner — CONFIRMED: "You're all set! Show this QR code at the parking entrance."; AWAITING_APPROVAL: "Request sent. The owner has until <time> to approve. You'll be refunded in full if they don't." Content: `StatusBadge`-style status pill (labels: PENDING_PAYMENT "Awaiting payment", AWAITING_APPROVAL "Waiting for owner", CONFIRMED "Confirmed", ACTIVE "Active", COMPLETED "Completed", CANCELLED "Cancelled", REJECTED "Declined", EXPIRED "Expired"), booking code (large, monospace, copy button "Copy code"), `BookingQr` (`qrcode.react` SVG of `PARKEASE:<bookingCode>`, 180 px, with caption) only for CONFIRMED/ACTIVE, listing title (link to public listing), address + "Get directions", window + duration, vehicle plate, slot label, price breakdown, refund line when `refundAmount > 0` ("Refunded ₹x"), cancel reason when present, "Download receipt" button (when `invoiceNumber`; fetch `GET /bookings/{id}/receipt` as blob and save as `ParkEase-<invoiceNumber>.pdf`), "Complete payment" button linking to checkout when PENDING_PAYMENT with a valid hold, and a status timeline from `events` ("<label> · <formatDateTime>"). Auto-refresh every 15 s while status is PENDING_PAYMENT or AWAITING_APPROVAL.
- `MyBookingsPage`: tabs "Upcoming" / "Past" (`view`), cards with code, status pill, listing, window, plate, total, link "View"; pagination; empty states "No upcoming bookings." + "Find parking", "No past bookings yet."
- [ ] **Tests:** `?new=1` confirmed banner + QR rendered (`svg` within a labelled region "Booking QR code"); awaiting-approval banner text with deadline; receipt download calls the endpoint with `responseType: 'blob'` and triggers a download (mock `URL.createObjectURL`); timeline lists events; My bookings tabs request `view=upcoming` then `view=past`.
- [ ] **Commit** `feat(frontend): booking confirmation with QR code, receipts and booking list`

### Task 12: Owner bookings page and docs

**Files:** `src/pages/owner/OwnerBookingsPage.tsx`; `OwnerLayout` (+ tab "Bookings" `/owner/bookings`); `OwnerHomePage` (+ card "<n> booking requests waiting" when > 0 with link "Review requests"); `README.md`; `backend/.env.example` (+ commented `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` with a short comment on test mode); test `src/pages/owner/OwnerBookings.test.tsx`.

**Behaviour:** tabs "Requests" / "Upcoming" / "Past" (`view`); request cards show code, listing, slot, window, vehicle type + plate, driver first name, earnings "You earn ₹<base>", and "Respond by <time>" (red when < 30 min); actions "Approve" (toast "Booking approved") and "Decline" (`ReasonDialog` "Decline this booking?", confirm "Decline", toast "Booking declined — the driver will be refunded"); empty states "No booking requests right now.", "No upcoming bookings.", "No past bookings yet."
README: Phase 4 ✅; "Payments" section — mock by default, how to add Razorpay test keys to `backend/.env` (never commit), Razorpay test card `4111 1111 1111 1111` / any future expiry / any CVV and test UPI `success@razorpay`, webhooks need a public URL (Phase 8).
- [ ] **Tests:** approve posts `/owner/bookings/5/approve`; decline posts `{reason}`; requests tab shows "You earn ₹240"; home card shows the request count.
- [ ] **Commit** `feat(frontend): owner booking requests and Phase 4 docs`

### Task 13 (controller): Manual browser verification
Driver: add vehicle → search → listing → Reserve → checkout countdown → mock Pay → confirmation with QR → receipt PDF downloads → My bookings. Manual-approve listing: request → owner approves; another request → owner declines → driver sees "Declined" + refund. Overlap: second driver gets "slot was just taken". Search "slots free" decreases after booking. Mobile 375 px + dark mode.
