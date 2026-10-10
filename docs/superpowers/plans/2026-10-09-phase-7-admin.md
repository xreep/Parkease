# Phase 7 — Admin Panel, Disputes, Payouts, Settings, Reports

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Admins run the platform: KPI dashboard, user/listing/review/location moderation, bookings monitor with admin cancel, disputes with refunds, payments/refunds with manual retry, owner payouts, platform settings with price guidelines, usage/revenue reports with CSV, and an audit log. Drivers can raise disputes; owners can respond and see pricing guidance.

**Architecture:** `admin` package grows into sub-packages (`admin/users`, `admin/bookings`, `admin/payouts`, `admin/reports`, `admin/audit`, …); new `settings` package (`PlatformSettings` service with typed getters and an in-memory cache invalidated on update) used by pricing/booking; new `dispute` package. Frontend `pages/admin/*` with a sectioned admin nav.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / PostgreSQL 16; React 19 + TS + Tailwind 4; TanStack Query; Vitest.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` — §5, §8 (KPI definitions) and **§22 Phase 7 Decisions** (binding).

## Global Constraints

- Project root: `/Users/adityaraj/my projects/Parkease` (quote it). `source ~/.zshrc` before `./mvnw`. Full suite: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 ./mvnw -q test`.
- Branch `phase-7-admin`. Never push, merge or switch branches. Backend and frontend agents may work in the tree at the same time: stage only your own paths explicitly (never `git add -A`, `.` or `commit -a`); never stash/reset/checkout outside your area.
- **Only Task 1 adds a migration (V15)**; later backend tasks must not add migrations unless unavoidable (then V16+, in order).
- Spring Boot 4.1.1, Java 21, package root `com.smartparking`. Inject `Clock`. Money `BigDecimal` scale 2 HALF_UP. Request dates are IST `LocalDate`; responses use instants.
- Every booking/payment state change keeps the lock order payment → booking (`BookingLocks`); refunds only via `RefundService`.
- Every admin write records an audit row via `AdminAuditService.record(admin, action, targetType, targetId, details)` in the same transaction.
- Notifications/emails only through `Notifier`. New `NotificationType` values (append): `BOOKING_CANCELLED_BY_ADMIN, DISPUTE_OPENED, DISPUTE_RESPONSE, DISPUTE_RESOLVED, LISTING_SUSPENDED, LISTING_REINSTATED, ACCOUNT_SUSPENDED, PAYOUT_SENT`.
- Errors via `ApiException` → ProblemDetail `code`. New codes: `CANNOT_SUSPEND` (409), `DISPUTE_NOT_ALLOWED` (409), `DISPUTE_ALREADY_OPEN` (409), `ALREADY_RESPONDED` (409), `INVALID_RESOLUTION` (400), `NOT_RETRYABLE` (409), `NOTHING_TO_PAY` (409), `INVALID_SETTING` (400), `INVALID_DATE_RANGE` (400, existing).
- Authorization: `/admin/**` ADMIN only (403 otherwise); drivers/owners only their own bookings/disputes (else 404).
- Pagination `{content, page, size, totalElements, totalPages}`, `size` ≤ 100. CSV exports reuse the Phase 6 CSV writer rules (BOM, RFC 4180, formula-injection guard, IST, `Content-Disposition` filename, row cap + `X-Truncated`).
- Frontend: existing primitives, light/dark, 375 px with no horizontal page scroll, tests with `renderApp` + `axios-mock-adapter`, ₹ via `formatINR`, IST via time utils, charts via `components/charts`.
- Checks: backend full suite; frontend `npm run lint && npx tsc -b && npm test -- --run && npm run build`.
- Commits: conventional + harness-provided `Co-Authored-By` trailer.

## Shared contracts

```
PlatformSettingsDto { platformFeePercent, gstPercent, holdMinutes, approvalHours, requestMinLeadMinutes,
                      priceGuidelines: [{ tier: 1|2|3, minHourly, maxHourly }] }
GET/PUT /admin/settings  (PUT validates: fee 0..50, gst 0..28, hold 5..60, approval 1..24, lead 0..240, 0 < min ≤ max ≤ 100000; INVALID_SETTING)
GET /pricing-guidelines?cityId → { tier, minHourly, maxHourly }            (authenticated owner or admin)

AdminActionDto  { id, adminName, action, targetType, targetId, details|null, createdAt }
GET /admin/audit?action?&targetType?&page&size → Page<AdminActionDto>

AdminUserDto    { id, firstName, lastName, email, phone|null, role, status, emailVerified, createdAt, bookingsCount, listingsCount }
GET /admin/users?role?&status?&q?&page&size ; POST /admin/users/{id}/suspend { reason 1..300 } ; POST /admin/users/{id}/activate  → AdminUserDto
POST /admin/listings/{id}/suspend { reason } ; POST /admin/listings/{id}/reinstate   → existing admin listing DTO
AdminReviewDto  = ReviewDto + { listingId, listingTitle, bookingCode, hidden, hiddenReason|null }
GET /admin/reviews?hidden?&q?&page ; POST /admin/reviews/{id}/hide { reason } ; POST /admin/reviews/{id}/unhide → AdminReviewDto
AdminStateDto   { id, name, code, slug, type, capitalName, citiesCount }
AdminCityDto    { id, stateId, stateName, name, slug, lat, lng, capital, tier, active, listingsCount }
GET /admin/states ; POST /admin/states ; PATCH /admin/states/{id}
GET /admin/cities?stateId?&q?&active?&page ; POST /admin/cities ; PATCH /admin/cities/{id}   (slug derived from name; unique per state)

AdminBookingSummaryDto { id, bookingCode, status, listingTitle, cityName, driverName, driverEmail, ownerName, startTime, endTime, totalAmount, refundAmount, paymentStatus|null, createdAt }
AdminBookingDetailDto  = summary + { listingId, slotLabel, baseAmount, platformFee, gstAmount, cancelReason, cancelledBy,
                         payment: { id, provider, providerOrderId, providerPaymentId, status, amount, capturedAt }|null,
                         refunds: [{ id, amount, status, attempts, providerRefundId|null, reason, createdAt }],
                         events: BookingDetailDto.Event[], disputes: DisputeSummaryDto[] }
GET /admin/bookings?status?&q?&cityId?&from?&to?&page ; GET /admin/bookings/{id} ; POST /admin/bookings/{id}/cancel { reason 1..300 } → AdminBookingDetailDto

DisputeCategory NO_ACCESS|SLOT_OCCUPIED|OVERSTAY|DAMAGE|PAYMENT|OTHER ; DisputeStatus OPEN|UNDER_REVIEW|RESOLVED ; Resolution REFUND_FULL|REFUND_PARTIAL|NO_REFUND|WARNING
DisputeSummaryDto { id, bookingId, bookingCode, listingTitle, category, status, createdAt, resolvedAt|null }
DisputeDto = summary + { description, raisedByName, ownerResponse|null, ownerRespondedAt|null, resolution|null, resolutionAmount|null, adminNotes|null (admin only; null for driver/owner), refundableRemaining (admin only) }
POST /bookings/{id}/disputes { category, description 10..2000 } → DisputeDto (driver)     GET /disputes (driver's, Page<DisputeSummaryDto>) ; GET /disputes/{id}
GET /owner/disputes?status? → Page<DisputeSummaryDto> ; GET /owner/disputes/{id} ; POST /owner/disputes/{id}/respond { response 1..1000 }
GET /admin/disputes?status?&page ; GET /admin/disputes/{id} ; POST /admin/disputes/{id}/review ; POST /admin/disputes/{id}/resolve { resolution, amount?, notes 1..1000 }
BookingDetailDto += { disputes: DisputeSummaryDto[], disputable: boolean }

AdminPaymentDto { id, bookingId, bookingCode, driverEmail, provider, providerOrderId, providerPaymentId|null, status, amount, refundAmount, createdAt, capturedAt|null }
AdminRefundDto  { id, paymentId, bookingId, bookingCode, amount, status, attempts, providerRefundId|null, reason, createdAt, lastError|null }
GET /admin/payments?status?&q?&from?&to?&page ; GET /admin/refunds?status?&page ; POST /admin/refunds/{id}/retry → AdminRefundDto

PayoutOwnerDto  { ownerId, ownerName, ownerEmail, pendingAmount, earningsCount, payoutMethod: "UPI"|"BANK"|null, payoutMasked|null (e.g. "ra***@okhdfc", "XXXX1234 · HDFC0001234") }
GET /admin/payouts → PayoutOwnerDto[] (owners with pending > 0, largest first) ; GET /admin/payouts?format=csv
GET /admin/payouts/{ownerId}/earnings → OwnerEarningDto[] (PENDING_PAYOUT)
POST /admin/payouts/mark-paid { ownerId, earningIds[] (non-empty, all PENDING_PAYOUT of that owner), reference 3..100 } → { paidCount, paidAmount }

AdminStatsDto { from, to,
  users: { drivers, owners, newDrivers, newOwners, suspended },
  listings: { approved, pendingReview, suspended, paused },
  bookings: { created, confirmed, conversionPercent, cancelled, utilizationPercent },
  money: { gmv, platformRevenue, refunds, ownerEarnings },
  topStates: [{ stateId, name, bookings, gmv }] (5), topCities: [{ cityId, name, stateName, bookings, gmv }] (5),
  series: [{ date, bookings, gmv, revenue }] }
GET /admin/stats?from&to   (default last 30 days, ≤ 366)
UsageRow   { cityId, cityName, stateName, listings, slots, bookings, bookedHours, utilizationPercent, cancellations }
RevenueRow { cityId, cityName, stateName, bookings, gmv, platformFees, gst, refunds, ownerEarnings }
GET /admin/reports/usage|revenue?from&to&stateId?&cityId? → { from, to, rows, totals } ; &format=csv
```

---

### Task 1: Schema, settings, price guidelines and audit log

**Files:** `V15__admin_disputes_settings.sql`; `settings/*` (`PlatformSettings` service + entity/repo + controller); `admin/audit/*`; wire `PricingService`/booking hold/approval/lead to `PlatformSettings` (fallback to `application.yml` defaults when a key is missing).

- [ ] V15: `platform_settings(key varchar pk, value varchar not null, updated_at, updated_by)`; `admin_actions(id, admin_id fk, action varchar(60), target_type varchar(40), target_id bigint, details varchar(1000), created_at)` + index (created_at desc); `disputes` per §5 + `owner_response varchar(1000)`, `owner_responded_at`, `updated_at`, indexes (booking_id), (status, created_at); `reviews.hidden_at`, `reviews.hidden_reason varchar(300)`; `cities.tier smallint not null default 3 check 1..3`, `cities.active boolean not null default true`; seed tier 1 for Mumbai, Delhi (New Delhi), Bengaluru, Chennai, Kolkata, Hyderabad, Pune, Ahmedabad (match by slug present in V2 — check the actual slugs), tier 2 for state capitals (`is_capital`) not in tier 1, else 3. Default guideline rows: tier1 20–150, tier2 10–100, tier3 5–80.
- [ ] `PlatformSettings`: typed getters, cached in memory, cache refreshed on update (single instance is fine; document it). PUT validation per contract; audit row with changed keys.
- [ ] Bookings use settings at reserve/quote time; existing tests keep passing with defaults.
- [ ] `GET /pricing-guidelines?cityId` (owner/admin).
- [ ] `AdminAuditService` + `GET /admin/audit`; retrofit audit rows into existing admin writes (owner verify/reject, listing approve/reject).
- [ ] **Tests:** migration tiers seeded (sample metro/capital/other); settings get/put/validation/403; quote uses changed fee% for new quotes only (existing booking amounts unchanged); guidelines by city tier; audit rows for retrofitted actions; audit list filters.
- [ ] Commit: `feat: platform settings, price guidelines and admin audit log`

### Task 2: Users, listings, reviews and locations moderation

**Files:** `admin/users/*`, `admin/AdminListingController` (+ suspend/reinstate), `admin/reviews/*`, `admin/locations/*`; auth filter suspension check; search/public listing exclusion of suspended owners; review aggregates excluding hidden.

- [ ] Users: list/search (q matches name/email, case-insensitive), counts; suspend: not ADMIN, not self (`CANNOT_SUSPEND`), reason required, revokes refresh tokens, sets SUSPENDED, notifies (email) — requests by suspended users rejected with 403 `ACCOUNT_SUSPENDED` within ≤ 60 s (cache status lookups ≤ 60 s); activate restores.
- [ ] Suspended owners' listings excluded from search, public listing detail (404), availability, reviews list, and new reservations (`LISTING_UNAVAILABLE` or existing code).
- [ ] Listing suspend (APPROVED/PAUSED only, reason) → SUSPENDED + owner notification `LISTING_SUSPENDED`; reinstate (SUSPENDED only) → APPROVED + `LISTING_REINSTATED`.
- [ ] Reviews hide/unhide (reason) → recompute aggregates (lock listing row as in ReviewService); hidden excluded from public list, owner list shows them flagged? (owner list: include with `hidden` flag false/true — keep owner view unchanged but exclude hidden from averages).
- [ ] Locations CRUD per contract (validation: lat 6..38, lng 68..98 for India, name 2..100, tier 1..3); slug from name, unique per state (409 `SLUG_TAKEN`); `active=false` hides city from public city lists/search suggestions but keeps existing listings.
- [ ] Audit rows for every write.
- [ ] **Tests:** each endpoint happy path + 403 + validation; suspension blocks API within the cache window (use a test hook to clear cache) and login; suspended owner listing hidden from search/detail; suspend/reinstate listing rules; hide review updates aggregates; city CRUD and slug conflict.
- [ ] Commit: `feat: admin moderation for users, listings, reviews and locations`

### Task 3: Disputes

**Files:** `dispute/*` (entity, repo, service, driver/owner/admin controllers, dtos); `BookingDetailDto` + mapper; notifications; emails.

- [ ] Driver raise rules: booking is the driver's (404), status CONFIRMED/ACTIVE/COMPLETED and `now ≤ end + 7 days` (`DISPUTE_NOT_ALLOWED`), no OPEN/UNDER_REVIEW dispute for the booking (`DISPUTE_ALREADY_OPEN`). Notifies owner (`DISPUTE_OPENED`) and creates an admin-visible item.
- [ ] Owner respond once (`ALREADY_RESPONDED`), only on their listing's bookings (404), not after RESOLVED (`DISPUTE_NOT_ALLOWED`). Notifies driver.
- [ ] Admin review: OPEN → UNDER_REVIEW. Resolve (OPEN/UNDER_REVIEW only): REFUND_FULL = remaining refundable (0 → `INVALID_RESOLUTION`), REFUND_PARTIAL requires 0 < amount ≤ remaining, NO_REFUND/WARNING no money; notes required. Refund via `RefundService.refund` under payment → booking locks (booking status unchanged unless already cancellable? — leave booking status unchanged); `resolutionAmount` stored; audit row; `DISPUTE_RESOLVED` to driver and owner.
- [ ] `disputable` on BookingDetailDto mirrors the raise rules.
- [ ] **Tests:** raise rules (status, window, duplicate, other driver); owner respond rules; resolve each type incl. amounts and refund rows, payment status/booking.refundAmount, earning net per Phase 5 rules; resolved immutable; authz; notifications.
- [ ] Commit: `feat: booking disputes with owner responses and admin resolutions`

### Task 4: Bookings monitor, payments/refunds and payouts

**Files:** `admin/bookings/*`, `admin/payments/*`, `admin/payouts/*`.

- [ ] Bookings list/search (q = code exact-ish or driver email contains, case-insensitive) with filters; detail; admin cancel (PENDING_PAYMENT, AWAITING_APPROVAL, CONFIRMED, ACTIVE) → CANCELLED (actor ADMIN — add enum value if missing, no migration needed if stored as varchar; check constraint!), full refund of remaining refundable when paid, earning REVERSED, notifications `BOOKING_CANCELLED_BY_ADMIN` to driver and owner; audit.
- [ ] Payments/refunds lists; refund retry only for FAILED (`NOT_RETRYABLE`), reuses the retry path (attempt limits respected or overridden for manual retry — manual retry allowed even after max attempts, resets nothing else); audit.
- [ ] Payouts per contract; masked details (UPI: first 2 chars + *** + @handle; bank: last 4 + IFSC); mark-paid under row locks on earnings (`PESSIMISTIC_WRITE`), all-or-nothing, `NOTHING_TO_PAY` if any id isn't the owner's PENDING_PAYOUT; sets PAID, paidAt, payoutReference; `PAYOUT_SENT` notification + email with amount and reference; audit; CSV of pending payouts.
- [ ] **Tests:** search/filters; admin cancel each status incl. refunds/earning; retry rules; payouts listing/masking/mark-paid/concurrency (double mark-paid → one succeeds); CSV.
- [ ] Commit: `feat: admin bookings monitor, refund retries and owner payouts`

### Task 5: Admin KPIs and reports

**Files:** `admin/reports/*` (`AdminStatsService`, `ReportService`, controller, CSV).

- [ ] Stats per contract and §8/§22 (attributed by booking `created_at` IST date; conversion = confirmed-ever (reached CONFIRMED/ACTIVE/COMPLETED or later cancelled after confirmation — use events or `confirmed_at not null`) ÷ created; utilization via the shared `SlotCoverage` helper over APPROVED listings; GMV = Σ (total − refundAmount) of bookings with captured payments; revenue = Σ platform_fee where payment not REFUNDED; refunds = Σ refundAmount; ownerEarnings = Σ net of non-REVERSED earnings). Aggregate SQL, no per-booking loops beyond utilization.
- [ ] Reports usage/revenue per contract with totals and CSV; filters; range validation.
- [ ] **Tests:** fixture with known numbers across two cities/states (incl. a refunded and a partially refunded booking, an unpaid hold), conversion, utilization, GMV/revenue/refunds, top lists order, series zeros, CSV headers/escaping, 403.
- [ ] Commit: `feat: admin KPI dashboard data and usage/revenue reports`

### Task 6: Admin shell, overview, reports, settings and audit UI

**Files:** `pages/admin/AdminLayout.tsx` (sectioned nav: Overview, Owners, Listings, Bookings, Disputes, Payments, Payouts, Users, Reviews, Locations, Reports, Settings, Audit — horizontal scroll tabs on mobile), `AdminHomePage.tsx` (KPI overview with range picker, cards, charts, top states/cities, queue counts from existing `/admin/queues`), `AdminReportsPage.tsx`, `AdminSettingsPage.tsx`, `AdminAuditPage.tsx`, `lib/admin.ts` additions.

- [ ] Overview: KPI cards (users, listings, bookings, conversion, utilization, GMV, revenue, refunds), series charts (bookings; GMV vs revenue), top 5 states/cities tables, links to queues.
- [ ] Reports: tabs Usage/Revenue, filters (date range, state → city), table (cards on mobile) with totals row, CSV download (truncation toast).
- [ ] Settings: form (RHF + Zod mirroring server limits), guideline rows per tier, save with confirmation ("applies to new bookings only"), server errors mapped to fields.
- [ ] Audit: filterable paged list.
- [ ] **Tests:** overview renders fixture; range refetch; reports filters + CSV; settings validation + save; audit filters.
- [ ] Commit: `feat(frontend): admin overview, reports, settings and audit log`

### Task 7: Admin management UI

**Files:** `pages/admin/AdminUsersPage.tsx`, listing suspend/reinstate in existing listing review page/queue, `AdminReviewsPage.tsx`, `AdminLocationsPage.tsx`, `AdminBookingsPage.tsx` + `AdminBookingDetailPage.tsx`, `AdminPaymentsPage.tsx` (payments + refunds tabs with retry), `AdminPayoutsPage.tsx`.

- [ ] Users: search/filter, suspend dialog (reason) / activate, disabled for admins/self.
- [ ] Listings: suspend (reason) / reinstate actions with status badge.
- [ ] Reviews: list with hidden filter, hide (reason)/unhide.
- [ ] Locations: states list → cities table with add/edit dialog (name, lat/lng, tier, active).
- [ ] Bookings: search + filters, detail with payment/refunds/timeline/disputes, admin cancel dialog (shows refund that will be issued = paid − refunded).
- [ ] Payments: payments table; refunds tab with FAILED filter and Retry.
- [ ] Payouts: owners with pending totals and masked details, expand to earnings with checkboxes, mark-paid dialog (reference), CSV.
- [ ] **Tests:** each page's main flow + error code mapping (`CANNOT_SUSPEND`, `NOT_RETRYABLE`, `NOTHING_TO_PAY`).
- [ ] Commit: `feat(frontend): admin users, listings, reviews, locations, bookings, payments and payouts`

### Task 8: Disputes UI and owner pricing guidance

**Files:** driver `BookingDetailPage` (Report a problem → `RaiseDisputeDialog`, dispute status card), `pages/driver/DisputesPage.tsx` (optional list in driver nav "Help"), owner `OwnerDisputesPage.tsx` (+ respond), admin `AdminDisputesPage.tsx` + detail with review/resolve dialog (resolution radio, amount for partial ≤ refundableRemaining, notes), owner wizard pricing step warning from `/pricing-guidelines`, owner earnings shows payout reference for PAID.

- [ ] **Tests:** raise flow + `DISPUTE_ALREADY_OPEN`; owner respond; admin resolve partial validation and full; wizard warning shown outside range and not blocking.
- [ ] Commit: `feat(frontend): disputes for drivers, owners and admins, and pricing guidance`

### Task 9: Controller E2E

- [ ] Local run: admin overview numbers vs DB; settings change affects a new quote; suspend/reactivate a user; suspend/reinstate listing; hide a review; add a city; driver raises dispute → owner responds → admin partial refund; admin cancel; refund retry (simulate FAILED); payouts mark-paid; reports CSV; audit shows all; 375 px spot-checks; no server errors. README Phase 7 ✅.
