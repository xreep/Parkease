# Smart Parking Slot Rental & Availability Platform — Design Spec

**Date:** 2026-10-04
**Status:** Approved in brainstorming, pending written-spec review
**Source:** Unified Mentor PRD (project id 11778)

## 1. Summary

A two-sided, location-based marketplace where private parking owners list unused slots (near metro stations, offices, commercial hubs) and drivers search, reserve and pay for them in advance. An admin verifies owners/listings, monitors bookings and disputes, manages payouts and generates reports. Web-only, desktop + mobile responsive, map-centric.

Additions beyond the PRD (agreed): real Razorpay test-mode payments with refunds, in-app + email notifications, reviews/ratings, all-India seed data.

## 2. Decisions

| Topic | Decision |
|---|---|
| Repo structure | Monorepo: `backend/` (Spring Boot) + `frontend/` (React) + `docs/`. Optional single-JAR packaging that bundles the React build into Spring Boot. |
| Backend | Spring Boot 4.1.x (Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3), Java 21, Maven wrapper |
| Database | PostgreSQL 16 — local (Homebrew) for `dev`, Neon for `prod`; Flyway migrations |
| Frontend | React 18 + Vite + TypeScript + Tailwind CSS |
| Payments | Razorpay **test mode**, full flow (orders, checkout, signature verify, webhooks, refunds, invoices). Mock provider when keys absent. |
| Notifications | In-app (bell) **and** email (SMTP: Gmail app password / Brevo / Resend). Console logging when SMTP absent. |
| File storage | Cloudinary; local `uploads/` fallback when keys absent |
| Maps | React-Leaflet + OpenStreetMap tiles + marker clustering; Nominatim geocoding |
| Seed data | All 28 states + 8 UTs, capitals + major cities (~90–100 cities), ~250–300 listings at real landmarks |
| Commission | 10% platform fee on base price; 18% GST on platform fee |
| Deployment | Backend Docker → Render; DB → Neon; frontend → Vercel; CI → GitHub Actions |

## 3. Roles

- **DRIVER** — search, book, pay, cancel, review, dispute, manage vehicles.
- **OWNER** — verify, list parking, manage slots/availability/blocks/pricing, approve bookings, view earnings and payouts.
- **ADMIN** — verify owners and listings, manage users/locations, monitor bookings, resolve disputes, mark payouts, manage pricing guidelines, reports.

A user has exactly one role. Registration offers DRIVER or OWNER; ADMIN is seeded only.

## 4. Architecture

### 4.1 Backend (`backend/`)

- **Spring Web MVC** — REST under `/api/v1/**`.
- **Spring Security + JWT** — access token 15 min, refresh token 7 days (stored hashed in `refresh_tokens`, rotated on use, revocable). BCrypt strength 12. Method-level `@PreAuthorize` role checks plus ownership checks in services.
- **Spring Data JPA (Hibernate) + PostgreSQL**, **Flyway** for schema.
- **Bean Validation**; global `@RestControllerAdvice` returning RFC 7807 `ProblemDetail` with `code` and `fieldErrors`.
- **springdoc-openapi** — Swagger UI at `/swagger-ui`.
- **Pluggable providers**, selected by presence of config:
  - `PaymentProvider` → `RazorpayPaymentProvider` | `MockPaymentProvider`
  - `EmailSender` → `SmtpEmailSender` (Spring Mail + Thymeleaf HTML templates) | `LoggingEmailSender`
  - `FileStorage` → `CloudinaryFileStorage` | `LocalFileStorage`
- **@Scheduled** lifecycle jobs (every minute).
- **OpenPDF** for invoice PDFs.
- **Bucket4j** rate limiting on auth endpoints.
- Profiles: `dev`, `prod`, `test`. All secrets via environment variables; `.env.example` documents them.

Package-by-feature under `com.smartparking`:

```
auth · user · owner · vehicle · location (state/city) · listing · slot ·
availability · search · pricing · booking · payment · invoice · earning ·
notification · email · review · dispute · admin · report · storage · common
```

Each feature owns its controller, service, repository, entities and DTOs. `common` holds error handling, security config, base entities, utilities.

### 4.2 Frontend (`frontend/`)

- React Router with role-guarded route groups (`/driver/*`, `/owner/*`, `/admin/*`).
- TanStack Query for server state; Axios instance with JWT attach + auto-refresh interceptor.
- React Hook Form + Zod for forms.
- React-Leaflet + `react-leaflet-cluster`; Nominatim geocoding (debounced, India-biased via `countrycodes=in`).
- Recharts for dashboards.
- Razorpay Checkout script loaded on demand.
- Light/dark theme; responsive with bottom tab bar on mobile; skeletons and empty states.

### 4.3 Search performance

Bounding-box prefilter on indexed `lat`/`lng`, then exact Haversine distance ordering in SQL; availability filter via `NOT EXISTS` against overlapping bookings/blocks. Paginated. No PostGIS dependency (works identically on local Postgres and Neon). Target: <3 s end to end, typically <300 ms server time.

## 5. Data Model

All tables have `id` (bigint identity), `created_at`, `updated_at` unless noted.

### Accounts
- **users** — name, email (unique, lowercase), phone, password_hash, role (`DRIVER|OWNER|ADMIN`), status (`ACTIVE|SUSPENDED`), email_verified, avatar_url.
- **owner_profiles** — user_id (PK/FK), verification_status (`UNSUBMITTED|PENDING|VERIFIED|REJECTED`), document_url, document_type, payout_upi, payout_bank_account, payout_ifsc, payout_account_name, rejection_reason, verified_at.
- **vehicles** — user_id, type (`TWO_WHEELER|FOUR_WHEELER`), plate_number, make_model, is_default.
- **refresh_tokens** — user_id, token_hash, expires_at, revoked_at.
- **email_tokens** — user_id, token_hash, purpose (`VERIFY_EMAIL|RESET_PASSWORD`), expires_at, used_at.

### Locations
- **states** — name, code, type (`STATE|UT`).
- **cities** — state_id, name, slug, lat, lng, is_capital. Unique (state_id, slug).

### Listings
- **parking_listings** — owner_id, city_id, title, description, address, pincode, lat, lng (indexed), listing_type (`METRO|OFFICE|COMMERCIAL|RESIDENTIAL|EVENT`), open_24x7 (boolean), amenities (text[]: `COVERED, CCTV, EV_CHARGING, SECURITY_GUARD, WHEELCHAIR_ACCESS, WELL_LIT`), rules (text), auto_approve, status (`DRAFT|PENDING_REVIEW|APPROVED|REJECTED|SUSPENDED`), rejection_reason, price_per_hour, price_per_day, price_per_month (numeric(10,2); hourly required, daily/monthly optional), cancellation_policy (`FLEXIBLE|MODERATE|STRICT`), avg_rating, review_count.
- **listing_photos** — listing_id, url, public_id, sort_order.
- **parking_slots** — listing_id, label, vehicle_type, size (`SMALL|MEDIUM|LARGE`), active.
- **availability_rules** — listing_id, day_of_week (1–7), open_time, close_time. Absence of rules = closed; a `open_24x7` flag on the listing overrides rules.
- **availability_blocks** — listing_id, slot_id (nullable = whole listing), start_time, end_time, reason.

### Bookings
- **bookings** — booking_code (unique, e.g. `PK-8F3K2Q`), driver_id, listing_id, slot_id, vehicle_id, start_time, end_time (timestamptz), pricing_mode (`HOURLY|DAILY|MONTHLY|MIXED`), base_amount, platform_fee, gst_amount, total_amount, status, hold_expires_at, approval_deadline, cancelled_by, cancel_reason, refund_amount, confirmed_at, completed_at.
  - Status: `PENDING_PAYMENT → AWAITING_APPROVAL → CONFIRMED → ACTIVE → COMPLETED`; terminal also `CANCELLED`, `REJECTED`, `EXPIRED`.
  - **Exclusion constraint** (`btree_gist`): no two rows with the same `slot_id` and overlapping `tstzrange(start_time, end_time)` where status ∈ {PENDING_PAYMENT, AWAITING_APPROVAL, CONFIRMED, ACTIVE}.
- **booking_events** — booking_id, from_status, to_status, actor, note (audit trail / status timeline).

### Payments
- **payments** — booking_id, provider (`RAZORPAY|MOCK`), provider_order_id, provider_payment_id, provider_signature, amount, currency (`INR`), method, status (`CREATED|CAPTURED|FAILED|REFUNDED|PARTIALLY_REFUNDED`), failure_reason, raw_payload (jsonb).
- **refunds** — payment_id, provider_refund_id, amount, status (`PENDING|PROCESSED|FAILED`), reason.
- **owner_earnings** — booking_id, owner_id, gross (= base_amount), commission, net, status (`HELD|PENDING_PAYOUT|PAID|REVERSED`), payout_reference, paid_at.
- **invoices** — invoice_number (unique, `INV-2026-000123`), booking_id, payment_id, pdf_url.
- **webhook_events** — provider_event_id (unique), event_type, payload (jsonb), processed_at.

### Other
- **notifications** — user_id, type, title, body, link, read_at.
- **reviews** — booking_id (unique), listing_id, driver_id, rating (1–5), comment.
- **disputes** — booking_id, raised_by, category (`NO_ACCESS|SLOT_OCCUPIED|OVERSTAY|DAMAGE|PAYMENT|OTHER`), description, status (`OPEN|UNDER_REVIEW|RESOLVED`), admin_notes, resolution (`REFUND_FULL|REFUND_PARTIAL|NO_REFUND|WARNING`), resolution_amount, resolved_at.
- **platform_settings** — key/value: `commission_percent=10`, `gst_percent=18`, `hold_minutes=10`, `approval_hours=2`, per-city-tier min/max hourly price guidelines (advisory warnings to owners, not hard limits).

## 6. Core Logic

### 6.1 Pricing (`PricingService`)
For duration `d`:
- `hourly = ceil(hours) × price_per_hour`
- `daily = ceil(days) × price_per_day` (if set), also mixed: `full_days × daily + min(remaining_hours × hourly, daily)`
- `monthly = ceil(d / 30 days) × price_per_month` (if set), plus mixed analog
- `base_amount` = minimum of available options; `platform_fee = round(base × 10%)`; `gst = round(platform_fee × 18%)`; `total = base + platform_fee + gst`. All in INR with 2 decimals, rounding HALF_UP.
- Minimum booking 1 hour, max 90 days. Start must be ≥ now + 0 min (rounded to 15-minute slots in UI).

### 6.2 Availability
A slot is available for `[s, e)` iff: slot active, listing APPROVED, listing open for the whole window (24×7 or every covered day/time within rules), no overlapping block (listing-wide or slot), no overlapping live booking. Booking creation picks the first available matching slot inside a transaction; the exclusion constraint is the final guarantee (violation → 409 `SLOT_UNAVAILABLE`).

### 6.3 Booking + payment flow
1. `POST /bookings` (listingId, vehicleId, start, end) → validates, prices, inserts `PENDING_PAYMENT` with `hold_expires_at = now + 10 min`, creates provider order → returns booking + `{orderId, amount, keyId}`.
2. Frontend opens Razorpay Checkout.
3. `POST /payments/verify` (bookingId, orderId, paymentId, signature) → HMAC-SHA256(`orderId|paymentId`, key_secret) check → payment CAPTURED → invoice generated → booking `CONFIRMED` (auto_approve) or `AWAITING_APPROVAL` with `approval_deadline = now + 2 h` → earning row `HELD` → notifications + emails to driver and owner.
4. Webhook `POST /payments/webhook` — verifies `X-Razorpay-Signature` against webhook secret, dedupes by event id; handles `payment.captured` (same as step 3 if not yet done), `payment.failed`, `refund.processed`, `refund.failed`. Idempotent: confirmation logic runs once per booking.
5. Payment failure/dismiss: booking stays `PENDING_PAYMENT` until hold expires; user may retry within hold.

### 6.4 Owner approval
Approve → `CONFIRMED`. Reject or deadline passes → `REJECTED` + full refund (including platform fee) + earning `REVERSED`.

### 6.5 Cancellation & refunds
Driver cancellation refund on `base_amount` by policy, hours-before-start `h`:

| Policy | Refund |
|---|---|
| FLEXIBLE | h ≥ 1 → 100%; else 50% |
| MODERATE | h ≥ 24 → 100%; 2 ≤ h < 24 → 50%; h < 2 → 0% |
| STRICT | h ≥ 48 → 50%; else 0% |

Platform fee + GST refunded only when owner/system/admin causes cancellation. Not cancellable once `ACTIVE`. Refund via `PaymentProvider.refund()`; earnings adjusted (`net` recomputed on retained amount, or `REVERSED` if zero). Owner may cancel a confirmed booking → full refund to driver.

### 6.6 Scheduled jobs (every minute)
- Expire `PENDING_PAYMENT` past `hold_expires_at` → `EXPIRED`.
- Auto-reject `AWAITING_APPROVAL` past `approval_deadline` → refund.
- `CONFIRMED` with `start_time ≤ now` → `ACTIVE`.
- `ACTIVE` with `end_time ≤ now` → `COMPLETED`; earning `HELD → PENDING_PAYOUT`; review-request notification.
- Reminder email/notification ~1 h before start (once).

### 6.7 Payouts
Admin views pending earnings grouped by owner, marks a batch paid with a reference (UTR); earnings → `PAID`; owner notified. Real money movement (Razorpay Route) is out of scope.

### 6.8 Disputes
Driver or owner raises on a booking (CONFIRMED/ACTIVE/COMPLETED, within 7 days of end). Admin moves to UNDER_REVIEW, adds notes, resolves with a resolution; refund resolutions trigger provider refund.

### 6.9 Owner verification
Owner uploads ID/property document → `PENDING`. Only `VERIFIED` owners can submit listings for review. Admin approves/rejects with reason. Listings then require admin approval (`PENDING_REVIEW → APPROVED|REJECTED`). Editing price/location of an approved listing keeps it live; owner can pause (→ DRAFT).

## 7. API Surface (summary)

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/verify-email`, `/auth/forgot-password`, `/auth/reset-password`; `GET /me`, `PATCH /me` |
| Locations | `GET /states`, `GET /states/{code}/cities`, `GET /cities?q=` |
| Search | `GET /search?lat&lng&radiusKm&start&end&vehicleType&types&amenities&maxPricePerHour&open24x7&sort&page` ; `GET /listings/{id}`; `GET /listings/{id}/availability?from&to` *(Phase 6)*; `GET /listings/{id}/quote?start&end` ; `GET /listings/{id}/reviews` *(Phase 6)* |
| Vehicles | CRUD `/me/vehicles` |
| Bookings (driver) | `POST /bookings`, `GET /bookings`, `GET /bookings/{id}`, `POST /bookings/{id}/cancel`, `GET /bookings/{id}/refund-preview`, `POST /bookings/{id}/review`, `POST /bookings/{id}/disputes` |
| Payments | `POST /payments/verify`, `POST /payments/webhook`, `GET /bookings/{id}/checkout`, `GET /bookings/{id}/receipt`, `POST /payments/mock/pay` (mock provider only), `GET /me/payments`. ~~`POST /bookings/{id}/payments/retry`~~ and ~~`GET /invoices/{id}/pdf`~~ are superseded (see §19: re-open `/bookings/{id}/checkout` to retry; the invoice is `/bookings/{id}/receipt`) |
| Owner | `GET /owner/profile`, `PUT /owner/profile/payout`, `POST /owner/verification`, `GET /owner/verification/document-url`, CRUD `/owner/listings`, `/owner/listings/{id}/photos`, `/owner/listings/{id}/photos/order`, `/slots`, `/slots/bulk`, `/hours`, `/blocks`, `POST /owner/listings/{id}/submit|pause|resume`; `GET /owner/bookings`, `POST /owner/bookings/{id}/approve|reject|cancel`; `GET /owner/earnings`, `GET /owner/stats` |
| Notifications | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all`, `GET /notifications/unread-count` |
| Uploads | `POST /uploads` (multipart, image/pdf ≤5 MB) |
| Admin | `GET /admin/stats`, `GET /admin/queues`, `/admin/owners?status`, `GET /admin/owners/{id}/document-url`, `POST /admin/owners/{id}/verify|reject`, `/admin/listings?status`, `POST /admin/listings/{id}/approve|reject|suspend`, `/admin/users`, `POST /admin/users/{id}/suspend|activate`, CRUD `/admin/states`, `/admin/cities`, `/admin/bookings`, `/admin/disputes`, `POST /admin/disputes/{id}/resolve`, `/admin/payments`, `/admin/payouts`, `POST /admin/payouts/mark-paid`, `GET/PUT /admin/settings`, `GET /admin/reports/usage|revenue?from&to&stateId&cityId&format=csv` |

All prefixed `/api/v1`. Paginated list responses: `{content, page, size, totalElements, totalPages}`.

## 8. Screens

**Public:** Home (search hero, browse-by-state, featured cities, how it works, owner CTA) · Search results (list + filters | clustered map; mobile toggle) · Listing detail (gallery, map, slots, prices, availability calendar, rules, policy, reviews, sticky booking card with live quote) · State/city browse (`/in/:state/:city`) · Login · Register (driver/owner) · Forgot/reset password · Verify email.

**Driver:** Checkout → Razorpay → Success (booking code, QR, receipt) · My bookings (Upcoming/Active/Past/Cancelled; status timeline, cancel with refund preview, directions, dispute, review) · Vehicles · Payments & receipts · Notifications · Profile.

**Owner:** Overview (earnings, occupancy, upcoming, pending approvals, charts) · Listings + 6-step wizard (location pin → photos → slots → pricing & rules → schedule → submit) · Calendar (bookings + blocks per slot) · Booking requests · Bookings history · Earnings & payouts · Verification · Payout details.

**Admin:** KPI dashboard (users/owners, listings, conversion rate, utilization, revenue, top cities/states, trends) · Owner verification queue · Listing approval queue · Users · States & cities · Bookings monitor · Disputes · Payments/refunds/payouts · Pricing guidelines · Reports (CSV export).

**KPI definitions:**
- Booking conversion = bookings reaching CONFIRMED ÷ bookings created (period).
- Slot utilization = booked slot-hours (CONFIRMED/ACTIVE/COMPLETED) ÷ available slot-hours (period, approved listings).
- Revenue = sum of platform_fee (platform) and GMV = sum of total_amount, net of refunds.

## 9. Security

JWT + BCrypt(12); role + ownership checks; Bucket4j on `/auth/**` (e.g. 10 req/min/IP); CORS allowlist from env; secrets only in env; Razorpay signature verification on verify + webhook; server-side amounts only; upload MIME/size validation; Bean Validation on all inputs; JPA parameterized queries; suspended users cannot log in; password reset tokens single-use, 30-minute expiry.

## 10. Error Handling

`ProblemDetail` JSON: `{type, title, status, detail, code, fieldErrors[]}`. Domain codes include `SLOT_UNAVAILABLE` (409), `HOLD_EXPIRED` (410), `PAYMENT_VERIFICATION_FAILED` (400), `OWNER_NOT_VERIFIED` (403), `INVALID_TIME_RANGE` (400), `NOT_CANCELLABLE` (409). Frontend maps them to toasts or inline field errors. Payment/booking transitions are transactional and idempotent.

## 11. Testing

- **Unit (JUnit 5 + Mockito):** PricingService, RefundPolicy, availability evaluation, signature verification, booking state machine.
- **Integration (`@SpringBootTest` + Testcontainers Postgres; fall back to local Postgres test DB if Docker unavailable — H2 cannot model the exclusion constraint):** booking create → verify → confirm; double-booking race → 409; webhook idempotency; cancellation refunds; scheduled transitions; role/ownership authorization.
- **Frontend (Vitest + React Testing Library):** booking card quote, search filters, auth forms, route guards.
- **Manual E2E:** full flow in the browser with a Razorpay test payment before declaring done.

## 12. Seed Data (`dev` profile, idempotent)

- 36 states/UTs; ~90–100 cities (all capitals + major cities) with real coordinates.
- ~250–300 listings at real landmarks (metro/railway stations, IT parks, malls, markets), 2–4 per city, with 2–20 slots each, mixed vehicle types, realistic city-tier pricing (₹10–₹80/h), photos from a small curated set.
- ~30 owners (verified, a few pending), ~50 drivers with vehicles, ~500 historical bookings across statuses with matching payments/earnings/reviews, a few disputes.
- Demo accounts: admin, owner, driver — credentials documented in README only.

## 13. Deployment

- Backend: multi-stage Dockerfile → Render (free); env vars from `.env.example`.
- DB: Neon (Flyway runs on startup).
- Frontend: Vercel (`VITE_API_URL`).
- Cloudinary for files; Razorpay webhook URL pointed at Render backend.
- GitHub Actions: build + test backend and frontend on push.
- Optional `-Pbundle` Maven profile builds the frontend and serves it from Spring Boot as a single JAR.

## 14. Build Phases

1. Scaffold, config, auth, users, states/cities, seed locations
2. Owner verification, listings, slots, availability, uploads
3. Search + map + listing detail
4. Booking, pricing, Razorpay payments, webhooks, invoices
5. Lifecycle jobs, cancellation/refunds, notifications, email
6. Owner dashboard, earnings, reviews, driver pages
7. Admin panel, reports, disputes, payouts, settings
8. Full seed data, polish, tests, docs, deployment

## 15. Out of Scope

Native mobile apps; IoT sensors; gate/barrier automation; real payout money movement (Razorpay Route); live navigation; dynamic pricing; transit integrations; multi-language UI.

## 16. User Prerequisites

Install Java 21 and PostgreSQL 16 (Homebrew). Optional accounts/keys: Razorpay (test), Cloudinary, Neon, Render, Vercel, SMTP. All optional services fall back to mocks for local development.

## 17. Phase 2 Adjustments (approved 2026-10-05)

- **Product name:** ParkEase (repo `xreep/Parkease`). Java package `com.smartparking` and DB names are unchanged.
- **Approvals moved earlier:** Phase 2 ships the owner-verification queue and listing-approval queue (API + minimal admin pages). The full admin panel (users, bookings, disputes, payouts, reports, settings) stays in Phase 7.
- **Partial seed early:** Phase 2 seeds 6 verified demo owners, ~55 approved listings (at least one per state/UT, more in metros), one pending owner verification and one pending listing. The full ~250–300 listing seed stays in Phase 8.
- **Listing status adds `PAUSED`:** `APPROVED → PAUSED → APPROVED` (owner pause/resume, no re-review). Deleting is allowed only in `DRAFT`, `PENDING_REVIEW`, `REJECTED`, `PAUSED`.
- **Amenities** are stored in a `listing_amenities` join table (JPA element collection) instead of a `text[]` column.
- **Weekly hours:** at most one open window per weekday (`close_time > open_time`, no overnight windows) or `open_24x7`. Times are Asia/Kolkata wall-clock times.
- **Location sanity check:** a listing's pin must be within 60 km of its selected city's centre (`LOCATION_OUTSIDE_CITY`).
- **Private documents:** owner verification documents are never publicly addressable. They are fetched via short-lived signed URLs (5 min): an HMAC-signed `/api/v1/files/private` URL for local storage, and Cloudinary `private` delivery with `private_download_url` in Cloudinary mode. Listing photos are public.
- **Uploads:** validated by magic bytes (JPEG/PNG/WebP for photos; plus PDF for documents), ≤5 MB, ≤8 photos per listing.
- **Paginated lists** return `{content, page, size, totalElements, totalPages}`.

## 18. Phase 3 Decisions (approved 2026-10-05)

- **Search window is optional.** `start`/`end` must be given together; without them search returns all approved listings nearby (no free-slot count or quote).
- **Time rules:** instants on 15-minute boundaries, `start ≥ now` (rounded down to the current quarter hour), duration 1 hour – 90 days (`INVALID_TIME_RANGE`).
- **Opening-hours rule:** a non-24×7 listing is available only if the whole window falls on one Asia/Kolkata calendar day inside that weekday's open–close window. Multi-day stays require `open_24x7`.
- **Availability** = APPROVED + ≥1 active slot of the vehicle type + open for the window + no overlapping whole-listing block; free slots exclude slots with an overlapping slot block (Phase 4 also excludes overlapping bookings).
- **Pricing** follows §6.1 (cheapest of hourly / daily / monthly / mixed); platform fee 10% of base, GST 18% of fee, HALF_UP to paise; percentages configurable via `app.pricing.*` until the Phase 7 settings table.
- **Search** = native SQL candidate query (bounding box + Haversine, filters) capped at the 500 nearest candidates, then window/slot evaluation in batch, then sort + paginate. Radius 0.5–25 km (default 5).
- **Public endpoints:** `GET /api/v1/search`, `GET /api/v1/listings/{id}` (APPROVED only), `GET /api/v1/listings/{id}/quote`. Public DTOs never expose status, rejection reason, submission times or owner contact details (owner first name only).
- **Map:** React-Leaflet with `react-leaflet-cluster`; price-label markers; "Search this area" re-centres on the map.
- **Reserve** button on the listing page sends signed-out users to login; actual booking arrives in Phase 4.
- **Parameter names:** search takes `types` (repeatable) and `maxPricePerHour` (> 0, ≤ 100000; otherwise `INVALID_PARAMETER`), not `type`/`maxPrice`. `GET /listings/{id}/availability` and `/reviews` (§7) are not implemented yet; they arrive in Phase 6.

## 19. Phase 4 Decisions (approved 2026-10-08)

- **Scope:** driver vehicles; booking with 10-minute slot hold; checkout via Razorpay (test mode) or a built-in mock provider when keys are absent; signature verification + webhook backup; booking confirmation (auto-approve) or owner approval within 2 hours (reject/timeout → full refund); invoices (PDF generated on download); owner earnings ledger rows; basic driver "My bookings" + booking detail with QR code; owner booking requests. Driver cancellations, ACTIVE/COMPLETED transitions, notifications bell and reminders remain Phase 5.
- **Money split:** driver pays `base + platform fee (10% of base) + GST (18% of fee)`. The owner earns the full `base`; the platform keeps the fee and remits GST. `owner_earnings`: `gross = base`, `commission = platformFee` (recorded for reporting), `net = base`.
- **No double-booking:** PostgreSQL exclusion constraint (`btree_gist`) on `(slot_id, tstzrange(start,end,'[)'))` for live statuses `PENDING_PAYMENT, AWAITING_APPROVAL, CONFIRMED, ACTIVE`; stale holds are expired before allocation; allocation retries the next free slot on a constraint violation (SQLState `23P01`).
- **Availability counts bookings:** search, quote and booking creation exclude slots with an overlapping live booking (a `PENDING_PAYMENT` booking counts only while `hold_expires_at > now`).
- **Late payment:** a payment captured after its hold expired re-confirms the booking if its slot is still free; otherwise it is refunded in full automatically.
- **Midnight rule:** a window ending exactly at 00:00 IST on the next day counts as ending at the end of the start day; it is allowed when that day's rule closes at 23:59.
- **Abuse limits:** a driver may hold at most 3 unpaid bookings at once (`TOO_MANY_HOLDS`).
- **Payments provider abstraction:** `PaymentProvider` with `RazorpayPaymentProvider` (orders + refunds via SDK, HMAC signature checks in our code) and `MockPaymentProvider` (selected when `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET` are not set). Webhooks require `RAZORPAY_WEBHOOK_SECRET` and a public URL (Phase 8).
- **Verify never returns 410:** when the hold has lapsed and the slot was taken meanwhile, `POST /payments/verify` answers 200 with a `CANCELLED` booking and the payment is refunded in full automatically (the booking page shows the refund). 410 `HOLD_EXPIRED` is only returned by `GET /bookings/{id}/checkout`.
- **Endpoints as built:** `GET /bookings/{id}/checkout`, `GET /bookings/{id}/receipt` (invoice PDF), `POST /payments/verify`, `POST /payments/webhook`, and `POST /payments/mock/pay`, which exists only while `app.payments.mock-enabled` (`PAYMENTS_MOCK_ENABLED`, default true; false in prod, where startup fails without Razorpay keys). These supersede `/bookings/{id}/payments/retry` and `/invoices/{id}/pdf` from §7.
- **Payment safety:** verification and webhooks confirm only a payment of the right order, for exactly the order amount and currency, that is captured at the provider (an authorized payment is captured by the app first). Refunds use an idempotency key so retries never refund twice; an extra captured payment on a paid order, or one for a booking that can no longer be paid, is refunded in full. A reconciliation job (every 5 minutes) asks Razorpay about orders from the last 24 hours whose booking is still `PENDING_PAYMENT` or just `EXPIRED` and confirms or refunds captured payments whose confirmation never reached the server.
- **Checkout UI:** the checkout page never swaps to the "expired" view while a signed payment is awaiting confirmation or a payment is in flight; it keeps the "Confirm my payment" button instead.

## 20. Phase 5 Decisions (approved 2026-10-09)

- **Notifications:** in-app `notifications` table + bell/dropdown/page; every booking/verification/listing email also creates a notification through one `Notifier` facade. No SMS/push.
- **Lifecycle job (every minute):** `CONFIRMED → ACTIVE` at start; `ACTIVE (or CONFIRMED) → COMPLETED` at end (earning `HELD → PENDING_PAYOUT`); `AWAITING_APPROVAL` at start → auto-declined with full refund. Approval deadline = `min(paidAt + 2h, start)`.
- **Driver cancellation:** allowed for `PENDING_PAYMENT` (no money moved), `AWAITING_APPROVAL` (full refund of the total, owner never accepted) and `CONFIRMED` before start (policy refund on the **base** amount; platform fee + GST not refunded). `ACTIVE`/terminal → `NOT_CANCELLABLE`. Policies: FLEXIBLE ≥1 h → 100% else 50%; MODERATE ≥24 h → 100%, 2–24 h → 50%, <2 h → 0%; STRICT ≥48 h → 50% else 0%. Preview endpoint shows the exact refund before confirming.
- **Owner cancellation** of a `CONFIRMED` booking before start: reason required, driver refunded the full total, earning reversed.
- **Partial refunds:** refunds carry an amount ≤ remaining refundable; payment becomes `PARTIALLY_REFUNDED` or `REFUNDED`; `booking.refundAmount` = sum of non-failed refunds; owner earning `net` = base − refunded base (status `REVERSED` when net reaches 0).
- **Reminders:** driver reminder ~1 hour before start (once, `reminder_sent_at`); owner nudge 30 minutes before an approval deadline (once).

## 21. Phase 6 Decisions (approved 2026-10-09)

- **Reviews:** one review per `COMPLETED` booking, written by its driver within 30 days of the booking end; rating 1–5 (integer), optional comment ≤ 1000 chars. Reviews are immutable once posted. The listing owner may post **one** public reply (≤ 500 chars). Listing `avg_rating` (1 decimal, HALF_UP) and `review_count` are recomputed in the same transaction. Public list shows the reviewer as first name + last initial. Owner gets `OWNER_NEW_REVIEW`; the completion notification links to the review form. Admin moderation (hiding) is Phase 7.
- **Availability calendar (public):** `GET /listings/{id}/availability?from&to` (IST dates, ≤ 31 days, within today … today + 90). Per day: `CLOSED` (not open), otherwise the share of open slot-minutes taken by live bookings and blocks → `AVAILABLE` (< 60%), `LIMITED` (60% – < 98%), `FULL` (≥ 98%). Indicative only; the quote at booking time stays authoritative.
- **Owner dashboard:** `GET /owner/stats?from&to` (IST dates; default last 30 days; ≤ 366 days) with KPI totals, a daily series (earnings, bookings) and pending approvals / next upcoming bookings. Occupancy = booked slot-minutes (CONFIRMED/ACTIVE/COMPLETED) ÷ open slot-minutes of the owner's APPROVED listings in the range. Earnings are attributed to the booking's start date; `REVERSED` earnings count as 0.
- **Owner earnings:** `GET /owner/earnings?status&from&to&page&size` (+ `format=csv`) with totals per status. Reversing an earning sets its `net` to 0 (existing reversed rows are fixed by migration).
- **Owner calendar:** `GET /owner/calendar?listingId&from&to` (≤ 14 days): slots, live bookings and blocks for a week grid.
- **Driver pages:** bookings tabs Upcoming / Active / Past / Cancelled (`view=upcoming|active|past|cancelled`); `GET /me/payments` (payments & receipts); `GET /me/stats` (bookings, amount spent net of refunds, hours parked, reviews pending).
- **Charts:** small in-house SVG components (no chart library).
