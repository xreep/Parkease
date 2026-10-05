# Phase 3 — Driver Search, Map and Public Listing Pages

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Drivers search near a place for a time window and vehicle type, see available listings on a clustered map and a list with prices and free slots, open a public listing page with a live price quote, and browse city pages.

**Architecture:** Backend adds a pure `pricing` module (quote calculator + time-window rules), an `AvailabilityEvaluator`, and a `search` package (native candidate query → batch evaluation → sort/paginate) plus public listing endpoints. Frontend adds a place search combobox, a URL-driven search page (list + react-leaflet-cluster map), a public listing page with booking card, and city pages.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / PostgreSQL 16; React 19 + Vite + TS + Tailwind 4; react-leaflet 5 + react-leaflet-cluster 4; TanStack Query; Vitest.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` — §6.1, §6.2, §7, §8 and **§18 Phase 3 Decisions** (binding).

**Plan format:** exact contracts, algorithms, and complete tests for the pricing/availability core; contract-level specs plus required test cases for the rest. Contracts and tests are binding.

## Global Constraints

- Project root: `/Users/adityaraj/my projects/smart parking slot rental & availability platform` — quote it in every shell command. `source ~/.zshrc` before `./mvnw`.
- Branch `phase-3-search` (already created from `main`). Never push, merge or switch branches.
- Spring Boot 4.1.1, Java 21; package root `com.smartparking`; package-by-feature (`pricing`, `search`, plus additions to `listing`, `availability`). Don't rename existing packages.
- All endpoints under `/api/v1`; errors via `ApiException` → ProblemDetail with `code`.
- Inject `java.time.Clock`; never call `Instant.now()` directly in main code.
- Money: `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. Time zone for opening hours: `ZoneId.of("Asia/Kolkata")`.
- No schema changes are needed in this phase (no new migration) unless a task says so.
- Public endpoints must never expose: listing `status`, `rejectionReason`, `submittedAt`, `approvedAt`, owner email/phone/id, storage keys.
- Frontend: brand **ParkEase**, existing UI primitives (`Button`, `TextField`, `Select`, `StatusBadge`, `Dialog`, `Spinner`, `FormError`), Tailwind `brand-*`, light + dark mode, no horizontal scroll at 375 px, accessible labels. Tests use `renderApp` + `axios-mock-adapter`; map components mocked in tests.
- Checks: backend `cd backend && ./mvnw -q test`; frontend `cd frontend && npm test && npm run build && npm run lint` (0 errors).
- Commits: conventional style + harness-provided `Co-Authored-By` trailer.

## Shared contracts

### Error codes (new)
| Code | HTTP | When |
|---|---|---|
| `INVALID_TIME_RANGE` | 400 | only one of start/end; not on 15-min boundary; start < current quarter hour; end ≤ start; duration < 60 min or > 90 days |
| `INVALID_LOCATION` | 400 | lat/lng missing or outside India bounds (lat 6–38, lng 68–98) |
| `NOT_FOUND` | 404 | public listing not APPROVED / unknown |

### Query parameters — `GET /api/v1/search`
`lat` (req), `lng` (req), `radiusKm` (default 5, clamp 0.5–25), `start`, `end` (ISO instants, both or neither), `vehicleType` (`TWO_WHEELER|FOUR_WHEELER`, optional), `types` (repeatable `ListingType`), `amenities` (repeatable `Amenity`, all must match), `maxPricePerHour` (decimal), `open24x7` (`true` → only 24×7), `sort` (`distance` default | `price` | `rating`), `page` (default 0), `size` (default 20, max 50).

### DTOs (JSON field names exact)
```
QuoteDto           { pricingMode: HOURLY|DAILY|MONTHLY|MIXED, durationMinutes, baseAmount, platformFee,
                     gstAmount, totalAmount, breakdown: string }      // e.g. "2 days + 3 hours"
SearchResultDto    { id, title, listingType, address, cityName, stateName, lat, lng, distanceKm (1 decimal),
                     coverPhotoUrl|null, pricePerHour, pricePerDay|null, pricePerMonth|null, amenities[],
                     open24x7, avgRating, reviewCount, totalSlots, freeSlots|null, quote: QuoteDto|null }
SearchResponse     { content: SearchResultDto[], page, size, totalElements, totalPages,
                     center: { lat, lng }, radiusKm, window: { start, end }|null }
PublicListingDto   { id, title, description|null, listingType, address, pincode, lat, lng, cityName, citySlug,
                     stateName, stateSlug, photos: [{ id, url }], amenities[], rules|null, cancellationPolicy,
                     autoApprove, open24x7, hours: [{ dayOfWeek, openTime, closeTime }], pricePerHour,
                     pricePerDay|null, pricePerMonth|null, slotSummary: { twoWheeler, fourWheeler,
                     small, medium, large }, avgRating, reviewCount, ownerFirstName }
ListingQuoteResponse { available: boolean, reason: null|"CLOSED"|"BLOCKED"|"NO_VEHICLE_SLOTS"|"FULLY_BOOKED",
                     freeSlots, totalSlots, quote: QuoteDto }
```
(`slotSummary` counts active slots only; `hours` sorted by day; photos by sortOrder with URLs via `ListingMapper.photoUrl`; amenities by enum order.)

## File Map

```
backend/src/main/java/com/smartparking/
  pricing/PricingProperties.java, Quote.java (record), QuoteDto.java, PricingMode.java, PricingService.java,
          TimeWindow.java
  availability/AvailabilityEvaluator.java
  search/SearchCandidateRepository.java (native SQL via JdbcClient or EntityManager), SearchCriteria.java,
         SearchService.java, SearchController.java, dto/SearchResultDto.java, SearchResponse.java
  listing/PublicListingService.java, PublicListingController.java,
          dto/PublicListingDto.java, ListingQuoteResponse.java
  common/security/SecurityConfig.java (permit GET /api/v1/search, /api/v1/listings/**)
  src/main/resources/application.yml (+ app.pricing)
backend/src/test/java/com/smartparking/
  pricing/PricingServiceTest.java, TimeWindowTest.java
  availability/AvailabilityEvaluatorTest.java
  search/SearchControllerTest.java
  listing/PublicListingControllerTest.java
frontend/
  package.json (+ react-leaflet-cluster)
  src/lib/search.ts, time.ts, places.ts
  src/components/search/PlaceSearch.tsx, SearchForm.tsx, ResultCard.tsx, ResultsMap.tsx, FiltersPanel.tsx
  src/components/listing/PhotoGallery.tsx, BookingCard.tsx
  src/pages/SearchPage.tsx, ListingPage.tsx, CityPage.tsx; modify HomePage.tsx, StatePage.tsx, App.tsx, Navbar.tsx
README.md (Phase 3 status)
```

---

### Task 1: Pricing calculator and time-window rules

**Files:** `pricing/*` (all), `application.yml` (+ `app.pricing`), tests `pricing/PricingServiceTest.java`, `pricing/TimeWindowTest.java`.

**Interfaces produced:**
- `PricingProperties` (`app.pricing`): `BigDecimal platformFeePercent` (default 10), `BigDecimal gstPercent` (default 18). YAML: `app.pricing.platform-fee-percent: 10`, `gst-percent: 18`.
- `enum PricingMode { HOURLY, DAILY, MONTHLY, MIXED }`.
- `record Quote(PricingMode pricingMode, long durationMinutes, BigDecimal baseAmount, BigDecimal platformFee, BigDecimal gstAmount, BigDecimal totalAmount, String breakdown)`; `QuoteDto` mirrors it (`static QuoteDto from(Quote)`).
- `PricingService` (`@Service`, ctor `PricingProperties`): `Quote quote(BigDecimal pricePerHour, BigDecimal pricePerDay /*nullable*/, BigDecimal pricePerMonth /*nullable*/, Instant start, Instant end)`; overload `Quote quote(ParkingListing l, Instant start, Instant end)`.
- `TimeWindow` (`record TimeWindow(Instant start, Instant end)`): `static TimeWindow of(Instant start, Instant end, Clock clock)` validating and throwing `ApiException.badRequest("INVALID_TIME_RANGE", <message>)`; `static Optional<TimeWindow> optional(Instant start, Instant end, Clock clock)` (both null → empty; one null → INVALID_TIME_RANGE "Choose both a start and an end time"); `long minutes()`; `boolean overlaps(Instant otherStart, Instant otherEnd)` (half-open: `otherStart < end && otherEnd > start`).
  Validation messages: not on quarter hour (seconds or nanos ≠ 0, or minute % 15 ≠ 0) → "Times must be on 15-minute steps"; `start < floorToQuarter(now)` → "Start time can't be in the past"; `end <= start` → "End time must be after start time"; `< 60 min` → "Bookings must be at least 1 hour"; `> 90 days` → "Bookings can be at most 90 days".

**Algorithm (exact):** `m = minutes` (> 0). Unit prices `H = pricePerHour`, `D = pricePerDay` (nullable), `M = pricePerMonth` (nullable). Constants: day = 1440 min, month = 43200 min (30 days).
- `hourly(x) = ceil(x / 60) × H`.
- `dayBased(x)` (only if D): `floor(x / 1440) × D + min(hourly(x % 1440), D)` (second term 0 when `x % 1440 == 0`).
- `best(x)` = min(hourly(x), dayBased(x) if D).
- `monthBased(m)` (only if M): `floor(m / 43200) × M + min(best(m % 43200), M)` (second term 0 when remainder 0).
- `base = min(hourly(m), dayBased(m) if D, monthBased(m) if M)`. Ties prefer the simpler option in this order: hourly, day-based, month-based.
- `pricingMode`: HOURLY if hourly wins; for day-based: DAILY when the remainder term is 0 or equals `D` (i.e. pure whole days), else MIXED; for month-based: MONTHLY when it is pure whole months (remainder term 0 or equals `M`), else MIXED.
- `breakdown`: human text of the winning composition, e.g. `"3 hours"`, `"1 hour"`, `"2 days"`, `"2 days + 3 hours"`, `"1 month"`, `"1 month + 4 days"`, `"1 month + 2 days + 5 hours"` (a remainder billed at a full day/month counts as one more day/month; singular/plural correct).
- `platformFee = base × platformFeePercent / 100` (scale 2 HALF_UP); `gst = platformFee × gstPercent / 100` (scale 2 HALF_UP); `total = base + platformFee + gst`. `baseAmount` scale 2.

- [ ] **Step 1: Failing tests** — `PricingServiceTest` (plain JUnit, `new PricingService(new PricingProperties(new BigDecimal("10"), new BigDecimal("18")))`, helper `at(String iso)` → Instant, base `T0 = 2026-10-06T04:30:00Z`):

```java
// H = 40, D = 250, M = 4500 unless stated
@Test void exactHours()            // 3h → base 120.00, HOURLY, "3 hours", fee 12.00, gst 2.16, total 134.16
@Test void partialHourRoundsUp()   // 1h15m → 2 hours → 80.00
@Test void dailyCapBeatsHourly()   // 8h → hourly 320 vs day 250 → 250.00 DAILY "1 day"
@Test void mixedDaysAndHours()     // 2d 3h → 2×250 + min(120,250) = 620.00 MIXED "2 days + 3 hours"
@Test void remainderCappedAtDay()  // 2d 10h → 2×250 + min(400,250) = 750.00 DAILY "3 days"
@Test void monthlyWins()           // 30d → months 4500 vs 30×250=7500 → 4500.00 MONTHLY "1 month"
@Test void monthPlusDays()         // 34d → 4500 + min(4×250, 4500) = 5500.00 MIXED "1 month + 4 days"
@Test void noDailyPriceUsesHourly()// D null, M null, 26h → 26×40 = 1040.00 HOURLY "26 hours"
@Test void feesRoundHalfUp()       // H = 33.33, 1h → base 33.33, fee 3.33, gst 0.60, total 37.26
@Test void singularLabels()        // 1h → "1 hour"; 24h (D set) → "1 day"
```
Write each as a real assertion block (`assertThat(q.baseAmount()).isEqualByComparingTo("620.00")`, mode, breakdown, and for `exactHours` and `feesRoundHalfUp` also fee/gst/total).

`TimeWindowTest` (clock fixed at `2026-10-06T04:37:00Z` → current quarter 04:30): valid 05:00–07:00 → minutes 120; start 04:30 allowed; start 04:15 → "Start time can't be in the past"; 05:10 → "Times must be on 15-minute steps"; end = start → "End time must be after start time"; 45 min → "at least 1 hour"; 91 days → "at most 90 days"; `optional(null,null)` empty; `optional(x,null)` → "Choose both a start and an end time"; `overlaps` half-open (touching edges don't overlap).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite.** **Step 5: Commit** `feat: pricing calculator and booking time-window rules`

---

### Task 2: Availability evaluator

**Files:** `availability/AvailabilityEvaluator.java`; test `availability/AvailabilityEvaluatorTest.java`.

**Interfaces produced:** `@Component AvailabilityEvaluator` with pure methods (no repositories):
- `boolean isOpen(boolean open24x7, List<AvailabilityRule> rules, TimeWindow w)` — true if 24×7; else convert `w.start()`/`w.end()` to `Asia/Kolkata`; require the same local date (an end of exactly 00:00 on the next day counts as the same day **only** if a rule closes at 23:59 is not involved — simpler rule: require `endLocal.toLocalDate().equals(startLocal.toLocalDate())`); find the rule for `startLocal.getDayOfWeek().getValue()`; require `!startLocal.toLocalTime().isBefore(open)` and `!endLocal.toLocalTime().isAfter(close)`.
- `Result evaluate(ListingAvailabilityInput in, TimeWindow w, VehicleType vehicleType /*nullable*/)` where `record ListingAvailabilityInput(boolean open24x7, List<AvailabilityRule> rules, List<ParkingSlot> slots, List<AvailabilityBlock> blocks)` and `record Result(boolean available, String reason, int freeSlots, int totalSlots)`:
  - candidate slots = active slots matching `vehicleType` (all active if null); `totalSlots` = their count; none → `(false, "NO_VEHICLE_SLOTS", 0, 0)`.
  - not open → `(false, "CLOSED", 0, total)`.
  - any whole-listing block (`slot == null`) overlapping → `(false, "BLOCKED", 0, total)`.
  - free = candidates minus those with an overlapping slot block; free == 0 → `(false, "FULLY_BOOKED", 0, total)`; else `(true, null, free, total)`.
  - (Phase 4 will add booking overlap to the same evaluator.)

- [ ] **Step 1: Failing tests** (plain JUnit, entities built in memory): window Tue 2026-10-06 10:00–12:00 IST (= 04:30–06:30Z).
  1. 24×7 with 2 active 4W slots → available, free 2.
  2. Rules Mon–Sat 08:00–22:00 → open; window 21:00–23:00 IST → CLOSED; Sunday-only rule 09:00–21:00 with a Tuesday window → CLOSED.
  3. Multi-day window (Tue 10:00 → Wed 10:00) on non-24×7 → CLOSED; same on 24×7 → available.
  4. Whole-listing block 11:00–11:30 IST → BLOCKED; block ending exactly at 10:00 → not overlapping → available.
  5. Slot block on one of two slots → free 1; on both → FULLY_BOOKED.
  6. vehicleType TWO_WHEELER with only 4W slots → NO_VEHICLE_SLOTS; inactive slots ignored.
- [ ] **Steps 2–4:** fail → implement → full suite. **Step 5: Commit** `feat: availability evaluator for opening hours, blocks and slots`

---

### Task 3: Public listing page API and quote endpoint

**Files:** `listing/PublicListingService.java`, `PublicListingController.java`, `dto/PublicListingDto.java`, `dto/ListingQuoteResponse.java`; `SecurityConfig` (permit `GET /api/v1/listings/**` and `GET /api/v1/search`); test `listing/PublicListingControllerTest.java`.

**Interfaces produced:**
- `GET /api/v1/listings/{id}` → `PublicListingDto` (APPROVED only; any other status or unknown → 404 `NOT_FOUND` "Listing not found"). `ownerFirstName` = first token of owner name.
- `GET /api/v1/listings/{id}/quote?start&end&vehicleType` → `ListingQuoteResponse` (start/end required → `TimeWindow.of`; `vehicleType` optional). Uses `AvailabilityEvaluator` (blocks loaded with `findByListingIdAndEndTimeAfter...` then filtered by overlap) and `PricingService`. The quote is returned even when unavailable (price is informative).
- No authentication required (works with or without a token).

- [ ] **Step 1: Failing tests** (`@IntegrationTest`, build listings with existing support: `verifiedOwner`, `createListing`, `makeComplete` (24×7, one 4W slot "A-01", ₹30/hr), submit, then approve by setting status APPROVED via repository):
  1. approved listing GET without auth → 200; JSON has `title`, `cityName` "Pune", `citySlug` "pune", `stateSlug` "maharashtra", `slotSummary.fourWheeler` 1, `photos.length()` 1, and **does not** contain `status`, `rejectionReason`, `submittedAt` (`jsonPath("$.status").doesNotExist()` etc.), `ownerFirstName` "Ravi".
  2. draft listing → 404.
  3. quote for a valid future window (use `Clock`-independent times: next whole day at 10:00–13:00 IST computed from `Instant.now()` rounded) → `available` true, `freeSlots` 1, `quote.baseAmount` 90.00, `quote.totalAmount` 100.62 (90 + 9.00 + 1.62).
  4. quote with `vehicleType=TWO_WHEELER` → `available` false, `reason` "NO_VEHICLE_SLOTS".
  5. quote with an overlapping whole-listing block (insert via repository) → `reason` "BLOCKED".
  6. quote with a 30-minute window → 400 `INVALID_TIME_RANGE`.
- [ ] **Steps 2–4:** fail → implement → full suite. **Step 5: Commit** `feat: public listing details and price quote endpoints`

---

### Task 4: Search API

**Files:** `search/*`; test `search/SearchControllerTest.java`.

**Interfaces produced:**
- `SearchCriteria` record built from query params (validation: lat/lng present and within India bounds else 400 `INVALID_LOCATION`; radius clamp; size clamp 1–50; page ≥ 0; window via `TimeWindow.optional`).
- `SearchCandidateRepository.findCandidates(SearchCriteria c)` → `List<Candidate(long id, double distanceKm)>` using one native SQL query (via `JdbcClient` or `EntityManager.createNativeQuery`), ordered by distance, `LIMIT 500`:
  ```sql
  SELECT l.id, (6371 * acos(least(1, greatest(-1,
           cos(radians(:lat)) * cos(radians(l.lat)) * cos(radians(l.lng) - radians(:lng))
         + sin(radians(:lat)) * sin(radians(l.lat)))))) AS distance_km
  FROM parking_listings l
  WHERE l.status = 'APPROVED'
    AND l.lat BETWEEN :minLat AND :maxLat
    AND l.lng BETWEEN :minLng AND :maxLng
    [AND l.listing_type IN (:types)]
    [AND l.price_per_hour <= :maxPrice]
    [AND l.open_24x7 = TRUE]
    [AND (SELECT count(*) FROM listing_amenities a WHERE a.listing_id = l.id AND a.amenity IN (:amenities)) = :amenityCount]
    AND EXISTS (SELECT 1 FROM parking_slots s WHERE s.listing_id = l.id AND s.active [AND s.vehicle_type = :vehicleType])
  ORDER BY distance_km
  LIMIT 500
  ```
  (filter on `distance_km <= :radius` via an outer `SELECT * FROM (...) t WHERE t.distance_km <= :radius ORDER BY distance_km LIMIT 500`). Bounding box: `dLat = radius / 111.0`, `dLng = radius / (111.0 * cos(radians(lat)))`. Bracketed clauses only when the filter is present. The literal `'APPROVED'` keeps the Phase 2 partial index usable.
- `SearchService.search(SearchCriteria c)` → `SearchResponse`:
  1. candidates (≤ 500);
  2. batch-load in a few queries for all candidate ids: listings (with city/state), cover photos (lowest `sortOrder` per listing), active slots, and — only when a window is present — rules and blocks overlapping the window (`start_time < :end AND end_time > :start`); amenities;
  3. if window: run `AvailabilityEvaluator.evaluate`, drop unavailable ones, attach `freeSlots`, `quote`; without window: `freeSlots` null, `quote` null, `totalSlots` = active slots matching vehicle type (or all);
  4. sort: `distance` (asc), `price` (quote total asc when window else `pricePerHour` asc; ties by distance), `rating` (`avgRating` desc, then `reviewCount` desc, then distance);
  5. paginate in memory → `SearchResponse` (`totalElements` = filtered count, `center`, `radiusKm` (clamped), `window`).
  Add repository methods as needed (e.g. `ListingPhotoRepository.findByListingIdInOrderByListingIdAscSortOrderAsc`, `ParkingSlotRepository.findByListingIdInAndActiveTrue`, `AvailabilityRuleRepository.findByListingIdIn`, `AvailabilityBlockRepository` overlap query for ids, `ParkingListingRepository.findAllWithCityAndStateByIdIn` with `join fetch`). No per-row queries.
- `GET /api/v1/search` in `SearchController` (public).

- [ ] **Step 1: Failing tests** — `SearchControllerTest` (`@IntegrationTest`; note the test DB has no demo seed; create listings via existing helpers then set APPROVED + adjust fields through repositories; use Pune centre 18.5204, 73.8567):
  1. `findsApprovedListingsNearestFirst`: two approved listings at ~0.5 km and ~3 km, one DRAFT at 0.2 km → `content.length()` 2, first is the 0.5 km one, `distanceKm` ≈ 0.5 (closeTo 0.1), draft absent, `$.window` null, `freeSlots` null.
  2. `radiusExcludesFarListings`: listing at ~8 km excluded with default radius 5, included with `radiusKm=10`.
  3. `windowFiltersClosedAndBlocked`: listing A 24×7; listing B Mon–Sat 08–22; search a Sunday-or-night window where B is closed → only A; whole-listing block on A in the window → A excluded; response contains `quote.totalAmount` and `freeSlots`.
  4. `vehicleTypeFilter`: listing with only 4W slots excluded for `vehicleType=TWO_WHEELER`.
  5. `filtersByTypeAmenitiesPriceAnd24x7`: `types=OFFICE`, `amenities=CCTV&amenities=COVERED` (listing must have both), `maxPricePerHour=35`, `open24x7=true` each narrowing correctly.
  6. `sortByPriceAndPagination`: three listings, `sort=price&size=2` → page 0 has 2 cheapest by hourly price, `totalElements` 3, `totalPages` 2; `page=1` has the third.
  7. `validation`: missing lat → 400 `INVALID_LOCATION`; lat 51 → 400 `INVALID_LOCATION`; only `start` → 400 `INVALID_TIME_RANGE`.
  8. `noAuthNeeded` (all above run without a token) and `publicFieldsOnly`: result JSON lacks `status`/`rejectionReason`.
  Provide a small test helper in `support/ListingTestSupport` (e.g. `approvedListingAt(mvc, auth, repos…, title, lat, lng, pricePerHour, open24x7)`) to keep tests readable — document it in the report.
- [ ] **Steps 2–4:** fail → implement → full suite (also boot once in dev and `curl -s "localhost:8080/api/v1/search?lat=19.1197&lng=72.8468"` returns the Andheri demo listing first; stop the app). **Step 5: Commit** `feat: public parking search with distance, filters, availability and quotes`

---

## Frontend tasks

Conventions for Tasks 5–8:
- Search state lives in the URL (`/search?place=<label>&lat=&lng=&start=&end=&vehicle=&radius=&types=&amenities=&maxPrice=&open24x7=&sort=&page=`); the page reads it with `useSearchParams` and every filter change writes it back (`replace: true` for filter tweaks, push for a new search).
- Query keys: `['search', <canonical params string>]`, `['listing', id]`, `['quote', id, start, end, vehicle]`. Search results `staleTime` 30 s; placeholder data keeps the previous page visible while loading (`placeholderData: keepPreviousData`).
- Times: the UI shows local (IST in India) date + time pickers; values sent to the API are ISO instants built with `new Date(localValue).toISOString()`; defaults = next quarter hour → +2 hours; pickers use `step=900`.
- Tests mock the map with `vi.mock('../components/search/ResultsMap', …)` (and `LocationPicker` where used) and mock `fetch` for Nominatim.

### Task 5: Search foundations — data layer, time helpers, place search, search form

**Files:** `frontend/package.json` (+ `react-leaflet-cluster` latest 4.x; it ships its own CSS imports — follow its README for React-Leaflet 5); `src/lib/search.ts`, `time.ts`, `places.ts`; `src/components/search/PlaceSearch.tsx`, `SearchForm.tsx`; tests `src/lib/time.test.ts`, `src/lib/search.test.ts`, `src/components/search/SearchForm.test.tsx`.

**Interfaces produced:**
- `time.ts`: `nextQuarter(now = new Date()): Date`; `defaultWindow(now?) → { start: Date, end: Date }` (next quarter → +2 h); `toLocalInputValue(d: Date): string` (`YYYY-MM-DDTHH:mm` local); `fromLocalInputValue(s): Date`; `formatWindow(startIso, endIso): string` → e.g. `"Tue 6 Oct, 10:00 am – 12:00 pm"` (same day) or `"Tue 6 Oct, 10:00 am – Thu 8 Oct, 9:00 am"`; `durationLabel(minutes) → "2 hours" | "1 day 3 hours" | "45 minutes"`.
- `search.ts`: types mirroring `SearchResultDto`, `SearchResponse`, `QuoteDto`, `PublicListingDto`, `ListingQuoteResponse`; `type SearchParams = { place, lat, lng, start?, end?, vehicle?, radius?, types?: ListingType[], amenities?: Amenity[], maxPrice?, open24x7?, sort?, page? }`; `parseSearchParams(usp: URLSearchParams): SearchParams | null` (null when lat/lng missing/invalid); `toSearchParams(p): URLSearchParams` (omit empty/default values; repeat keys for arrays); `toApiQuery(p)` (maps `vehicle`→`vehicleType`, `radius`→`radiusKm`, `maxPrice`→`maxPricePerHour`); hooks `useSearch(p)`, `usePublicListing(id)`, `useQuote(id, start, end, vehicle)` (enabled only when start/end set).
- `places.ts`: `type Place = { label: string; lat: number; lng: number; kind: 'city' | 'place' }`; `searchCities(q)` → `GET /cities?q=` mapped to `{label: "Pune, Maharashtra", kind:'city'}`; `searchNominatim(q, signal)` → `fetch('https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=in&q=' + encodeURIComponent(q))` mapped to `{label: display_name shortened to its first 3 comma parts, kind:'place'}`.
- `PlaceSearch` (`{ value: Place | null, onChange(place), label = "Where are you going?", placeholder = "City, area or landmark" }`): accessible combobox (`role="combobox"`, `aria-expanded`, `aria-controls`, listbox `role="listbox"`, options `role="option"`, `aria-activedescendant`), debounced 300 ms; shows city matches first (heading "Cities"), then Nominatim matches (heading "Places") for queries ≥ 3 chars; ArrowUp/Down/Enter/Escape keyboard support; "No matches" message; aborts stale Nominatim requests.
- `SearchForm` (`{ initial?: SearchParams, compact?: boolean, onSubmit?(p) }`): fields `PlaceSearch`, "From" and "Until" (`datetime-local`), `Select` "Vehicle" (Car / Two-wheeler → FOUR_WHEELER/TWO_WHEELER, default Car), button "Search parking". Validation messages: no place → "Choose a place from the list"; until ≤ from → "'Until' must be after 'From'"; duration < 1 h → "Book at least 1 hour". Default submit navigates to `/search?…` (via `toSearchParams`). `compact` = single-row layout for the search page header.

- [ ] **Step 1: Tests:** time helpers (nextQuarter at 10:07 → 10:15; exactly 10:15:00.000 → 10:30 (always strictly in the future); durationLabel(120) "2 hours", (1620) "1 day 3 hours", (45) "45 minutes"); search param round-trip (arrays, omitted defaults, invalid lat → null); SearchForm: typing "pun" shows "Pune, Maharashtra" (mock `GET /cities?q=pun`), choosing it + submit navigates to `/search?place=Pune%2C+Maharashtra&lat=18.5204&lng=73.8567&start=…&end=…&vehicle=FOUR_WHEELER` (assert via a test route rendering `useLocation().search`); missing place shows "Choose a place from the list"; Nominatim branch called for "andheri metro" (mock `fetch`) and option labelled with the shortened display name appears.
- [ ] **Steps 2–4:** fail → implement → `npm test && npm run build && npm run lint`. **Step 5: Commit** `feat(frontend): search data layer, time helpers and place search`

---

### Task 6: Search results page with list, filters and clustered map

**Files:** `src/components/search/ResultCard.tsx`, `ResultsMap.tsx`, `FiltersPanel.tsx`; `src/pages/SearchPage.tsx`; route `/search` in `App.tsx` (public, inside `AppLayout`); test `src/pages/SearchPage.test.tsx`.

**Behaviour:**
- Header: compact `SearchForm` prefilled from the URL; summary line "<n> parking spots near <place>" and, when a window is set, "<formatWindow> · <durationLabel>".
- Desktop (≥ 1024 px): two columns — results list (scrollable) and sticky map. Mobile: toggle buttons "List" / "Map" (`aria-pressed`).
- `ResultCard`: cover image (or placeholder), title (link to `/listings/:id?start&end&vehicle` preserving the window), "<type label> · <distanceKm> km", rating "★ 4.5 (12)" only if `reviewCount > 0`, prices "₹40/hr" plus "₹250/day" and "₹4,500/month" when present, amenity chips (max 3 + "+n"), "<freeSlots> of <totalSlots> slots free" when a window is set (else "<totalSlots> slots"), and the estimated total "₹134 total" (`formatINR(quote.totalAmount)`) when a quote is present. Hovering/focusing a card highlights its map marker; clicking a marker scrolls its card into view and highlights it.
- `ResultsMap` (`{ results, center, radiusKm, highlightedId, onMarkerClick, onSearchArea(center) }`): React-Leaflet map, `react-leaflet-cluster` `MarkerClusterGroup`, `L.divIcon` price pills ("₹40"), highlighted pill styled differently, centre marker for the searched place, fits bounds to results on new search; when the user pans/zooms more than ~20% away from the searched centre show a floating button "Search this area" → `onSearchArea(map.getCenter())` (updates lat/lng and `place` → "Map area").
- `FiltersPanel` (drawer on mobile via `Dialog`, sidebar section on desktop): "Distance" select (1, 2, 5, 10, 25 km), "Max price per hour" number, "Parking type" checkboxes, "Amenities" checkboxes, "Open 24 × 7 only" checkbox, buttons "Apply filters" (mobile) / live on desktop, and "Clear filters". "Sort by" select: Nearest / Lowest price / Best rated.
- States: loading skeleton cards (3); error "Couldn't load results. Try again." + retry; empty: "No parking found here for these times." with suggestions "Try a larger distance" (button bumps radius to the next option) and "Try different times".
- Pagination: "Previous"/"Next" with "Page x of y" when `totalPages > 1` (scroll list to top on change).
- Invalid/missing lat/lng → render the search form with heading "Find parking" and no results.

- [ ] **Step 1: Tests** (map mocked to a stub that renders one button per result labelled `Marker <title>` and a button "Search this area" calling `onSearchArea({lat: 18.6, lng: 73.9})`):
  1. Renders two results from mocked `GET /search` with query `lat=18.5204&lng=73.8567&...`; shows "2 parking spots near Pune, Maharashtra", prices, "3 of 5 slots free", "₹134.16 total" (or the formatted equivalent), and card link href contains `/listings/7?start=`.
  2. Changing "Sort by" to Lowest price re-requests with `sort=price` and updates the URL.
  3. Ticking amenity "CCTV" requests `amenities=CCTV`; "Clear filters" removes it.
  4. Empty response shows "No parking found here for these times." and "Try a larger distance" re-requests with `radiusKm=10` when current is 5.
  5. Clicking "Search this area" re-requests with lat 18.6 / lng 73.9.
  6. Clicking `Marker <title>` marks that card highlighted (`data-highlighted="true"`).
- [ ] **Steps 2–4:** fail → implement → checks. **Step 5: Commit** `feat(frontend): search results with filters, sorting and clustered map`

---

### Task 7: Public listing page with gallery and booking card

**Files:** `src/components/listing/PhotoGallery.tsx`, `BookingCard.tsx`; `src/pages/ListingPage.tsx`; route `/listings/:id` (public); test `src/pages/ListingPage.test.tsx`.

**Behaviour:**
- `usePublicListing(id)`; 404 → `NotFoundPage`.
- Layout: title, "<type label> · <cityName>, <stateName>", rating if any; `PhotoGallery` (large cover + thumbnails; click opens a `Dialog` lightbox with "Previous"/"Next" buttons, Escape closes, image `alt` = "<title> photo <n>"); sections: "About" (description), "Location" (address with `formatAddress`, read-only `LocationPicker` map, link "Get directions" → `https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>` `target=_blank rel=noopener`), "Slots" ("<fourWheeler> car · <twoWheeler> two-wheeler" + size counts), "Opening hours" ("Open 24 × 7" or per-day list; days without a rule show "Closed"), "Amenities", "Rules", "Cancellation policy" (same explanations as the owner pricing step), "Hosted by <ownerFirstName>".
- `BookingCard` (sticky on desktop, bottom section on mobile): prices list; "From"/"Until" pickers + "Vehicle" select, initialised from the URL `start/end/vehicle` or `defaultWindow()`; live `useQuote` (debounced 300 ms after changes); shows:
  - available → "<freeSlots> slots free", breakdown rows "Parking (<quote.breakdown>)" = base, "Platform fee" = fee, "GST on fee" = gst, and **"Total"** = total;
  - unavailable → message per reason: CLOSED "Closed at these times — check the opening hours.", BLOCKED "Not available at these times.", NO_VEHICLE_SLOTS "No slots for this vehicle type.", FULLY_BOOKED "All slots are taken for these times."; Reserve disabled;
  - `INVALID_TIME_RANGE` errors shown inline from the server detail.
  - "Reserve" button: signed-out → navigate to `/login?next=<current path+query>`; signed-in driver → disabled with helper "Online booking opens in the next update." (Phase 4 replaces this); owners/admins see "Sign in as a driver to book."
  - Keep the chosen times in the URL (`replace`).

- [ ] **Step 1: Tests:** renders title, address, hours ("Sunday" "09:00 – 21:00"), amenities and "Hosted by Priya"; quote request uses URL times and shows "Total" with the formatted total; changing vehicle to Two-wheeler with response `available:false, reason:'NO_VEHICLE_SLOTS'` shows "No slots for this vehicle type." and disables "Reserve"; signed-out Reserve navigates to `/login?next=%2Flistings%2F7%3F…`; 404 shows "This spot is empty"; gallery lightbox opens and "Next" changes the image alt to "… photo 2".
- [ ] **Steps 2–4:** fail → implement → checks. **Step 5: Commit** `feat(frontend): public listing page with gallery and live price quote`

---

### Task 8: Home search, city pages, navigation and docs

**Files:** `src/pages/CityPage.tsx`; modify `HomePage.tsx`, `StatePage.tsx`, `App.tsx`, `components/layout/Navbar.tsx`; `README.md`; test `src/pages/CityPage.test.tsx` (+ update `HomePage.test.tsx` if needed).

**Behaviour:**
- HomePage hero: replace the "Find parking" anchor with the full `SearchForm` (white card on the gradient); below it quick chips "Popular: Mumbai · Delhi · Bengaluru · Hyderabad · Chennai · Kolkata · Pune" → each navigates to `/search` for that city (city centre coordinates from `/cities?q=` lookups are not needed — hard-code these 7 cities' lat/lng from V2 data: Mumbai 19.0760,72.8777; New Delhi 28.6139,77.2090; Bengaluru 12.9716,77.5946; Hyderabad 17.3850,78.4867; Chennai 13.0827,80.2707; Kolkata 22.5726,88.3639; Pune 18.5204,73.8567) with the default window and Car.
- StatePage: city cards become links to `/in/:stateSlug/:citySlug`.
- CityPage `/in/:stateSlug/:citySlug`: loads `GET /states/{state}/cities/{city}` (404 → NotFound); heading "Parking in <City>"; subtitle "<State>"; `SearchForm` prefilled with the city; results = `useSearch({lat, lng, radius: 15, sort: 'distance'})` without a window (list + map using `ResultCard`/`ResultsMap`, "View all on the search page" link); empty state "No listed parking in <City> yet." + link "List your space" (`/register?role=OWNER`).
- Navbar: add "Find parking" → `/search` (for everyone).
- README: Phase 3 ✅ (search with map, filters, availability + live quotes, public listing pages, city pages); add search example to "Try it" section (e.g. "search 'Andheri Metro'").

- [ ] **Step 1: Tests:** home search form present ("Search parking" button) and clicking chip "Pune" navigates to `/search?place=Pune…&lat=18.5204&lng=73.8567…`; StatePage city card link href `/in/maharashtra/pune`; CityPage shows "Parking in Pune" and a result title from mocked `/search` (request has `radiusKm=15` and no `start`); unknown city → "This spot is empty".
- [ ] **Steps 2–4:** fail → implement → checks (backend suite too, unchanged). **Step 5: Commit** `feat(frontend): home search, city pages and navigation`

---

### Task 9 (controller): Manual browser verification
Home search "Andheri" → results near Andheri with map clusters and prices; filters/sort; "Search this area" after panning; open a listing → quote updates when times change; night window on a non-24×7 listing shows "Closed at these times"; signed-out Reserve → login; city page Pune; mobile 375 px list/map toggle; dark mode.
