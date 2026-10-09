# Phase 6 — Owner Dashboard, Earnings, Reviews, Driver Pages

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Owners get a real dashboard (KPIs, charts, upcoming, approvals), an earnings ledger with CSV export and a per-slot week calendar; drivers can review completed bookings and see payments, stats and tabbed bookings; listings show reviews and a public availability calendar.

**Architecture:** New `review` package (entity, service, controllers); `availability` gains a day-level calendar; new `owner/dashboard` services for stats, earnings and calendar (read-only, JPQL/native aggregate queries); `user` gains `/me/payments` and `/me/stats`. Frontend adds in-house SVG charts, owner overview/earnings/calendar/reviews pages, listing reviews + availability calendar, driver payments page and tabs.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / PostgreSQL 16; React 19 + TS + Tailwind 4; TanStack Query; Vitest.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` — §5, §8 and **§21 Phase 6 Decisions** (binding).

**Plan format:** contracts, exact rules and required tests. Binding.

## Global Constraints

- Project root: `/Users/adityaraj/my projects/Parkease` (quote it). `source ~/.zshrc` before `./mvnw`.
- Branch `phase-6-dashboards`. Never push, merge or switch branches. A backend and a frontend agent may work in the tree at the same time: stage only your own paths explicitly (never `git add -A`, `.` or `commit -a`); never stash/reset/checkout outside your area.
- Spring Boot 4.1.1, Java 21, package root `com.smartparking`. Inject `Clock`. Money `BigDecimal` scale 2 HALF_UP. Dates in requests are IST `LocalDate` (`AvailabilityEvaluator.ZONE`); instants in responses.
- Read endpoints are read-only transactions; any booking state change keeps the lock order payment → booking (`BookingLocks`).
- Notifications/emails only through `Notifier`. New `NotificationType` value: `OWNER_NEW_REVIEW` (append; check for a DB check constraint on `notifications.type` and extend it in the migration if present).
- Errors via `ApiException` → ProblemDetail `code`. New codes: `NOT_REVIEWABLE` (409), `ALREADY_REVIEWED` (409), `ALREADY_REPLIED` (409), `INVALID_DATE_RANGE` (400).
- Authorization: owners see only their own listings/bookings/earnings (others → 404); drivers only their own bookings/payments (others → 404); wrong role → 403.
- Pagination `{content, page, size, totalElements, totalPages}`; `size` ≤ 100.
- Frontend: existing UI primitives, light/dark, 375 px with no horizontal scroll, tests with `renderApp` + `axios-mock-adapter`, money via existing formatter (₹), dates in IST via existing time utils.
- Checks: backend `cd backend && ./mvnw -q test`; frontend `cd frontend && npm run lint && npx tsc -b && npm test -- --run && npm run build`.
- Commits: conventional + harness-provided `Co-Authored-By` trailer.

## Shared contracts

```
ReviewDto            { id, rating, comment|null, authorName ("Rahul S."), createdAt, ownerReply|null, ownerRepliedAt|null }
ReviewSummaryDto     { avgRating (1 decimal), reviewCount, distribution: { "1":n, "2":n, "3":n, "4":n, "5":n } }
GET /listings/{id}/reviews?page&size → { summary: ReviewSummaryDto, reviews: Page<ReviewDto> }   (public; listing must be APPROVED or PAUSED, else 404)
POST /bookings/{id}/review  { rating 1..5, comment? ≤1000 } → ReviewDto   (driver)
POST /owner/reviews/{id}/reply { reply 1..500 } → ReviewDto            (owner of the listing)
GET  /owner/reviews?listingId?&page&size → Page<OwnerReviewDto>        OwnerReviewDto = ReviewDto + { listingId, listingTitle, bookingCode }
BookingDetailDto += { reviewable: boolean, review: ReviewDto|null }

DayAvailabilityDto   { date (YYYY-MM-DD), level: AVAILABLE|LIMITED|FULL|CLOSED, openTime "HH:mm"|null, closeTime "HH:mm"|null, bookedPercent (0..100 int) }
GET /listings/{id}/availability?from&to → { listingId, days: DayAvailabilityDto[] }

OwnerStatsDto {
  from, to,
  totals: { earningsNet, bookings, cancellations, occupancyPercent (1 decimal), avgRating, reviewCount },
  balances: { held, pendingPayout, paid },            // all-time, by earning status
  pendingApprovals: int,
  upcoming: OwnerBookingDto[] (next 5 CONFIRMED/AWAITING_APPROVAL by start),
  series: [{ date, earningsNet, bookings }]           // one entry per day in range, zeros included
}
OwnerEarningDto  { id, bookingId, bookingCode, listingTitle, startTime, endTime, gross, commission, net, status, paidAt|null, payoutReference|null }
GET /owner/earnings?status?&from?&to?&page&size → { totals: { held, pendingPayout, paid, reversedCount }, earnings: Page<OwnerEarningDto> }
GET /owner/earnings?…&format=csv → text/csv (header row; same filters; all rows, max 10 000)
OwnerCalendarDto { listingId, from, to, slots: [{id,label}], bookings: [{id, bookingCode, slotId, startTime, endTime, status, driverName}], blocks: [{id, slotId|null, startTime, endTime, reason|null}] }

DriverPaymentDto { id, bookingId, bookingCode, listingTitle, amount, status, refundAmount, paidAt|null, invoiceNumber|null, receiptAvailable }
GET /me/payments?page&size → Page<DriverPaymentDto>  (newest first; only payments that reached CAPTURED or later)
DriverStatsDto { totalBookings, completedBookings, amountSpent (captured − refunded), hoursParked (1 decimal, COMPLETED only), pendingReviews }
GET /me/stats → DriverStatsDto
GET /bookings?view=upcoming|active|past|cancelled|all
```

---

### Task 1: Reviews backend

**Files:** migration `V13__reviews.sql`; `review/Review.java`, `ReviewRepository`, `ReviewService`, `ReviewController` (public + driver), `OwnerReviewController`, `review/dto/*`; `BookingDetailDto` + mapper; `NotificationType.OWNER_NEW_REVIEW`; `BookingJobs.completeOne` notification link; `EmailTemplates` (owner new review).

- [ ] `reviews` table per §5: `booking_id` unique FK, `listing_id` FK, `driver_id` FK, `rating smallint check 1..5`, `comment varchar(1000)`, `owner_reply varchar(500)`, `owner_replied_at`, `created_at`, `updated_at`; index `(listing_id, created_at desc)`.
- [ ] Rules: booking must belong to the driver (else 404), be `COMPLETED` and `end ≥ now − 30 days` (else 409 `NOT_REVIEWABLE` with a reason); second review → 409 `ALREADY_REVIEWED` (also guard the unique constraint race → same code). Comment trimmed, blank → null. Recompute listing `avg_rating`/`review_count` in the same tx (lock the listing row with `PESSIMISTIC_WRITE` before recomputing).
- [ ] Owner reply: only the listing's owner (else 404), once (409 `ALREADY_REPLIED`), trimmed 1..500.
- [ ] `reviewable` = driver could review now; `review` = existing review or null.
- [ ] Notifications: owner `OWNER_NEW_REVIEW` ("New 4★ review for {listing}", link `/owner/reviews`) + email; the `BOOKING_COMPLETED` notification link becomes `/driver/bookings/{id}#review`.
- [ ] Public list: newest first; author = first name + last initial + "."; summary distribution has all five keys.
- [ ] **Tests:** happy path and aggregates (two reviews 5 and 4 → 4.5, count 2); non-completed / other driver / older than 30 days / duplicate; owner reply rules; public list hides unapproved listings; distribution; `reviewable` flips after posting; notification created.
- [ ] Commit: `feat: booking reviews with ratings and owner replies`

### Task 2: Public availability calendar

**Files:** `availability/AvailabilityCalendarService.java`, `AvailabilityController` (or `PublicListingController`) endpoint, dto.

- [ ] Validate `from ≤ to`, span ≤ 31 days, `from ≥ today(IST)`, `to ≤ today + 90` → else 400 `INVALID_DATE_RANGE`. Listing must be APPROVED (else 404).
- [ ] Per day: opening from rules/open24x7 (24x7 → `00:00`–`24:00`; closed day → `CLOSED`, times null). Open slot-minutes = active slots × open minutes. Taken = for each slot, minutes of the open window covered by live bookings (`PENDING_PAYMENT` unexpired holds, `AWAITING_APPROVAL`, `CONFIRMED`, `ACTIVE`) or blocks (slot-specific or listing-wide) — overlaps on the same slot counted once. `bookedPercent = floor(taken*100/open)`; levels per §21. For today, minutes before now count as taken (past time can't be booked).
- [ ] Efficient: one query for bookings and one for blocks over the range; compute in memory.
- [ ] **Tests:** closed day; 24x7 empty → AVAILABLE 0; half the slots booked all day → LIMITED/AVAILABLE thresholds at 59/60/97/98%; listing-wide block → FULL; overlap counted once; range validation; unapproved → 404.
- [ ] Commit: `feat: public day-level availability calendar for listings`

### Task 3: Owner stats, earnings and calendar backend

**Files:** `owner/dashboard/OwnerStatsService`, `OwnerEarningsService` (+ CSV writer), `OwnerCalendarService`, `OwnerDashboardController`, dtos; `RefundService`/earning reversal sets `net = 0`; migration `V14__reversed_earnings_net.sql` (`UPDATE owner_earnings SET net = 0 WHERE status = 'REVERSED'`).

- [ ] Stats per the contract and §21 (range validation: `from ≤ to`, ≤ 366 days, default last 30 days ending today IST; 400 `INVALID_DATE_RANGE`). Bookings = CONFIRMED/ACTIVE/COMPLETED with start in range; cancellations = CANCELLED/REJECTED with start in range (owner's listings); occupancy from the Task 2 minute logic (reuse a shared helper) limited to APPROVED listings; avgRating weighted by review_count.
- [ ] Earnings list: filters optional; newest booking start first; totals over all the owner's earnings (not the page). CSV: `Booking,Listing,Start (IST),End (IST),Gross,Commission,Net,Status,Paid at,Payout reference`, RFC 4180 quoting, neutralise formula injection (prefix `'` when a cell starts with `= + - @`), filename `parkease-earnings-YYYY-MM-DD.csv`.
- [ ] Calendar: listing must be the owner's (404); range ≤ 14 days (400); live bookings (as Task 2 plus COMPLETED) and blocks overlapping the range; driverName = first name + last initial.
- [ ] Reversal sets net 0 everywhere an earning is reversed (refunds, owner cancel, reject); update tests that asserted the old net.
- [ ] **Tests:** stats math on a fixture (earnings, reversed counted 0, occupancy, series zeros), authz, range errors; earnings filters/totals/paging; CSV content, quoting and injection; calendar contents and authz; reversal net 0.
- [ ] Commit: `feat: owner dashboard stats, earnings ledger with CSV and slot calendar`

### Task 4: Driver payments, stats and booking views backend

**Files:** `user/MePaymentsController` (or extend `MeController`), services, dtos; `BookingQueries` views.

- [ ] Views: `upcoming` = PENDING_PAYMENT (unexpired), AWAITING_APPROVAL, CONFIRMED (start asc); `active` = ACTIVE; `past` = COMPLETED (start desc); `cancelled` = CANCELLED, REJECTED, EXPIRED (start desc); `all`. Unknown view → 400 `INVALID_PARAMETER` (keep existing behaviour if already defined).
- [ ] `/me/payments` and `/me/stats` per the contract (driver only; 403 for others).
- [ ] **Tests:** each view's membership and order; payments only own and only captured+; stats math incl. refunds and pending reviews.
- [ ] Commit: `feat: driver payments, stats and booking views`

### Task 5: Reviews and availability UI

**Files:** `components/reviews/*` (Stars, ReviewSummary, ReviewList, ReviewForm), `ListingPage.tsx` (reviews section + availability calendar), `components/listing/AvailabilityCalendar.tsx`, `BookingDetailPage.tsx` (review card at `#review`), `pages/owner/OwnerReviewsPage.tsx` (+ route, nav item), api hooks.

- [ ] Listing page: rating summary with distribution bars, paged reviews ("Show more"), owner replies; empty state "No reviews yet".
- [ ] Availability calendar: month grid (current + next month navigation, max today+90), colour + text legend (Available / Limited / Full / Closed — not colour alone), clicking a non-closed future day pre-fills the booking card date.
- [ ] Booking detail: when `reviewable`, a star picker (keyboard accessible radio group) + comment (1000 counter) → submit → shows the posted review; when `review` exists show it read-only. Scroll to `#review` when the hash is present.
- [ ] Owner reviews page: list across listings with listing filter, reply form (500 counter) for reviews without reply.
- [ ] **Tests:** summary/distribution render; show more; calendar levels/legend and range nav; review submit success + `ALREADY_REVIEWED` error; owner reply.
- [ ] Commit: `feat(frontend): listing reviews, availability calendar and review flows`

### Task 6: Owner dashboard, earnings and calendar UI

**Files:** `components/charts/BarChart.tsx`, `LineChart.tsx` (SVG, responsive, accessible with `<title>`/table fallback), `pages/owner/OwnerHomePage.tsx` (overview), `OwnerEarningsPage.tsx`, `OwnerCalendarPage.tsx`, `OwnerLayout` nav + routes; Phase 5 carry-over: owner cancel dialog shows booking code, driver and time.

- [ ] Overview: range picker (7/30/90 days), KPI cards (earnings, bookings, occupancy, rating, pending approvals with link), balances (held / pending payout / paid), earnings bar chart + bookings line chart, upcoming list. Verification banner stays for unverified owners.
- [ ] Earnings: status filter, date range, totals cards, table (cards on mobile), pagination, "Download CSV" (blob download with the server filename).
- [ ] Calendar: listing select, week navigation (≤ 14-day API window), grid rows = slots, columns = days, bars for bookings (status colour + code) and blocks (hatched), IST, horizontal scroll inside the grid only on small screens.
- [ ] **Tests:** overview renders KPIs and charts from fixture; range change refetches; earnings filters + CSV request; calendar renders bars in the right slot/day; cancel dialog shows code.
- [ ] Commit: `feat(frontend): owner dashboard, earnings ledger and slot calendar`

### Task 7: Driver pages UI

**Files:** `MyBookingsPage.tsx` (4 tabs), `pages/driver/PaymentsPage.tsx` (+ route, nav), `DriverHomePage.tsx` (stats + next booking + "Rate your parking" prompts), Phase 5 carry-over: booking timeline shows refund events as "Refund" (event with `note` starting "Refund"/same from→to status).

- [ ] Tabs Upcoming / Active / Past / Cancelled with per-tab empty states; tab in URL (`?view=`).
- [ ] Payments: list with amount, status badge (incl. partially refunded), refund, receipt download (existing receipt endpoint), pagination.
- [ ] Home: stat cards from `/me/stats`, next upcoming booking card, pending reviews prompt linking to `#review`.
- [ ] **Tests:** tabs switch + URL; payments render + receipt link; home stats; timeline refund label.
- [ ] Commit: `feat(frontend): driver booking tabs, payments and overview stats`

### Task 8: Controller E2E

- [ ] Run backend + frontend locally; as driver complete a booking (lifecycle), review it; as owner reply, view dashboard/earnings (CSV)/calendar; check listing reviews + availability calendar; driver payments/stats/tabs; 375 px spot-check; no server errors. Update README (Phase 6 ✅ + screenshots list of features).
