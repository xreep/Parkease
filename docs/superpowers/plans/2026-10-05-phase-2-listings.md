# Phase 2 — Owner Verification, Listings, Slots, Availability, Uploads, Approvals

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Verified parking owners can create complete listings (location pin, photos, slots, pricing, weekly hours, blocked times) and submit them; an admin approves owners and listings; demo data puts approved listings in every state/UT.

**Architecture:** New backend feature packages `storage`, `listing`, `slot`, `availability`, `admin` plus additions to `owner`, `common`, `email`. Files go through a `FileStorage` interface (Cloudinary when `CLOUDINARY_URL` is set, otherwise local disk with HMAC-signed private URLs). Frontend adds an owner area (`/owner/**`) with a 6-step listing wizard and an admin area (`/admin/**`) with two review queues, using React-Leaflet for the map.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / PostgreSQL 16 / Flyway; cloudinary-http5 2.5.0; React 19 + Vite + TS + Tailwind 4; leaflet + react-leaflet; TanStack Query; RHF + Zod 4; Vitest.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` (sections 5, 6.9, 7, 8, 9 and **17. Phase 2 Adjustments**).

**Plan format note (deliberate):** to keep cost down, this plan gives exact DDL, API contracts, DTO shapes, validation rules, error codes, and complete backend test code, plus full implementation code for the non-obvious parts (storage, signing, submit validation, seeding). For straightforward CRUD and UI the implementer writes code to the contract; the contracts and tests are binding.

## Global Constraints

- Project root: `/Users/adityaraj/my projects/smart parking slot rental & availability platform` — quote it in every shell command. Run `source ~/.zshrc` before `./mvnw`.
- Work on branch `phase-2-listings` (already created from `main`).
- Spring Boot 4.1.1, Java 21. Never downgrade. Boot 4 test imports: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- Package root `com.smartparking`; package-by-feature. Do not rename existing packages.
- All endpoints under `/api/v1`. Errors: `ApiException` → RFC 7807 `ProblemDetail` with `code` (+ optional extra properties). Owner endpoints under `/api/v1/owner/**` (role OWNER, already enforced by SecurityConfig); admin under `/api/v1/admin/**` (role ADMIN).
- Ownership: an owner touching another owner's listing/slot/photo/block gets **404 `NOT_FOUND`** (never 403 — don't leak existence).
- Flyway owns schema (`ddl-auto: validate`). New migration `V4__listings.sql`; never edit V1–V3.
- Inject `java.time.Clock`; never call `Instant.now()`/`LocalDate.now()` without it.
- Money: `BigDecimal`, `NUMERIC(10,2)`, scale 2.
- Paginated responses: `PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)`; query params `page` (0-based, default 0), `size` (default 20, max 100).
- Tests: backend integration tests use `@IntegrationTest` (real Postgres `smartparking_test`, rolled back) and `RecordingEmailSender`; storage in tests is local under `target/test-uploads`. Frontend tests use `renderApp` + `axios-mock-adapter`; mock map components in tests.
- Brand: **ParkEase**. Frontend uses existing `Button`, `TextField`, `Spinner`, `AuthCard`/`FormError` patterns and Tailwind `brand-*` colours; light + dark mode; mobile-first (no horizontal scroll at 375 px).
- Commits: conventional style, each ending with the harness-provided `Co-Authored-By` trailer.

## Shared contracts (used by several tasks)

### Error codes introduced in Phase 2
| Code | HTTP | When |
|---|---|---|
| `FILE_REQUIRED` | 400 | multipart `file` missing/empty |
| `FILE_TOO_LARGE` | 413 | upload > 5 MB |
| `UNSUPPORTED_FILE_TYPE` | 400 | magic bytes not an allowed type |
| `INVALID_SIGNATURE` | 403 | private-file URL signature bad or expired |
| `ALREADY_VERIFIED` | 409 | owner re-submits after VERIFIED |
| `PAYOUT_DETAILS_REQUIRED` | 400 | neither UPI nor (bank account + IFSC) |
| `LOCATION_OUTSIDE_CITY` | 400 | pin > 60 km from city centre |
| `INVALID_PRICING` | 400 | day < hour, or month < day |
| `SLOT_LABEL_TAKEN` | 409 | duplicate slot label in listing |
| `SLOT_LIMIT` | 409 | > 200 slots |
| `PHOTO_LIMIT` | 409 | > 8 photos |
| `INVALID_PHOTO_ORDER` | 400 | reorder ids ≠ listing's photo ids |
| `DUPLICATE_DAY` | 400 | two hours rules for same weekday |
| `INVALID_HOURS` | 400 | close ≤ open |
| `INVALID_BLOCK` | 400 | end ≤ start, end ≤ now, start > now+365d, or slot not in listing |
| `OWNER_NOT_VERIFIED` | 403 | submit while owner not VERIFIED |
| `LISTING_INCOMPLETE` | 400 | submit with missing parts; property `missing`: array of `PHOTOS`,`SLOTS`,`PRICING`,`AVAILABILITY` |
| `INVALID_STATUS` | 409 | action not allowed in current status |
| `LISTING_SUSPENDED` | 409 | owner edits a SUSPENDED listing |

### Enums (exact names)
- `owner.DocumentType`: `AADHAAR, PAN, DRIVING_LICENCE, PASSPORT, VOTER_ID, PROPERTY_DOCUMENT, UTILITY_BILL`
- `listing.ListingType`: `METRO, OFFICE, COMMERCIAL, RESIDENTIAL, EVENT`
- `listing.ListingStatus`: `DRAFT, PENDING_REVIEW, APPROVED, REJECTED, PAUSED, SUSPENDED`
- `listing.CancellationPolicy`: `FLEXIBLE, MODERATE, STRICT`
- `listing.Amenity`: `COVERED, CCTV, EV_CHARGING, SECURITY_GUARD, WHEELCHAIR_ACCESS, WELL_LIT`
- `common.model.VehicleType`: `TWO_WHEELER, FOUR_WHEELER`
- `slot.SlotSize`: `SMALL, MEDIUM, LARGE`

### DTOs (JSON field names exact; times as `"HH:mm"`, instants ISO-8601)
```
PhotoDto            { id, url, sortOrder }
SlotDto             { id, label, vehicleType, size, active }
HoursRuleDto        { dayOfWeek (1=Mon..7=Sun), openTime, closeTime }
BlockDto            { id, slotId|null, slotLabel|null, startTime, endTime, reason|null }
ListingSummaryDto   { id, title, status, cityName, stateName, coverPhotoUrl|null, pricePerHour|null,
                      slotCount (active), rejectionReason|null, updatedAt }
ListingDetailDto    { id, title, description, address, pincode, lat, lng, listingType, cityId, cityName,
                      stateName, status, rejectionReason, open24x7, rules, autoApprove, pricePerHour,
                      pricePerDay, pricePerMonth, cancellationPolicy, amenities (sorted by enum order),
                      photos (by sortOrder), slots (by label), hours (by dayOfWeek), submittedAt, approvedAt,
                      updatedAt }
OwnerProfileDto     { verificationStatus, documentType|null, documentSubmittedAt|null, rejectionReason|null,
                      verifiedAt|null, payoutUpi|null, payoutAccountName|null, payoutIfsc|null,
                      payoutBankAccountLast4|null }
SignedUrlDto        { url, expiresAt }
AdminOwnerDto       { userId, name, email, phone, verificationStatus, documentType, documentSubmittedAt,
                      rejectionReason, verifiedAt, hasPayoutDetails, listingCount }
AdminListingSummaryDto { …all ListingSummaryDto fields…, ownerId, ownerName, ownerEmail, submittedAt }
AdminListingDetailDto  { listing: ListingDetailDto, owner: { id, name, email, phone, verificationStatus } }
QueueCountsDto      { pendingOwners, pendingListings }
PageResponse<T>     { content, page, size, totalElements, totalPages }
```

## File Map

```
backend/
  pom.xml                                                    (+ cloudinary-http5 2.5.0)
  src/main/resources/application.yml                         (+ app.storage.*)
  src/main/resources/db/migration/V4__listings.sql
  src/main/resources/seed/demo-listings.psv
  src/main/java/com/smartparking/
    common/error/ApiException.java, GlobalExceptionHandler.java   (modify: extra properties, 413)
    common/model/VehicleType.java
    common/web/PageResponse.java, GeoUtils.java
    common/security/SecurityConfig.java                     (modify: permit /uploads/**, /api/v1/files/private)
    storage/StorageProperties.java, StoredFile.java, FileStorage.java, FileTypes.java, UploadKind.java,
            UploadValidator.java, ValidatedUpload.java, UrlSigner.java, LocalFileStorage.java,
            CloudinaryFileStorage.java, StorageConfig.java, PrivateFileController.java
    owner/OwnerProfile.java (modify), DocumentType.java, OwnerProfileService.java, OwnerController.java,
          dto/OwnerProfileDto.java, dto/PayoutRequest.java
    listing/ParkingListing.java, ListingPhoto.java, ListingType.java, ListingStatus.java, CancellationPolicy.java,
            Amenity.java, ParkingListingRepository.java, ListingPhotoRepository.java,
            OwnerListingService.java, ListingMapper.java, OwnerListingController.java,
            ListingPhotoService.java, ListingPhotoController.java,
            dto/ListingBasicsRequest.java, PricingRequest.java, PhotoOrderRequest.java,
                ListingSummaryDto.java, ListingDetailDto.java, PhotoDto.java
    slot/ParkingSlot.java, SlotSize.java, ParkingSlotRepository.java, SlotService.java, SlotController.java,
         dto/SlotRequest.java, BulkSlotRequest.java, SlotDto.java
    availability/AvailabilityRule.java, AvailabilityBlock.java, AvailabilityRuleRepository.java,
                 AvailabilityBlockRepository.java, AvailabilityService.java, AvailabilityController.java,
                 dto/HoursRequest.java, HoursRuleDto.java, BlockRequest.java, BlockDto.java
    admin/AdminReviewService.java, AdminOwnerController.java, AdminListingController.java,
          dto/AdminOwnerDto.java, AdminListingSummaryDto.java, AdminListingDetailDto.java,
              ReasonRequest.java, QueueCountsDto.java
    email/EmailTemplates.java                               (modify: 4 templates)
    common/seed/DemoAccountSeeder.java (modify: @Order(1)), DemoListingSeeder.java
  src/test/java/com/smartparking/
    support/OwnerTestSupport.java, ListingTestSupport.java
    storage/FileTypesTest.java, UrlSignerTest.java, LocalFileStorageTest.java, PrivateFileControllerTest.java
    listing/ListingSchemaTest.java, OwnerListingControllerTest.java, ListingPhotoControllerTest.java,
            ListingSubmitTest.java
    owner/OwnerControllerTest.java
    slot/SlotControllerTest.java
    availability/AvailabilityControllerTest.java
    admin/AdminReviewControllerTest.java
    common/seed/DemoListingSeederTest.java
frontend/
  package.json (+ leaflet, react-leaflet, @types/leaflet)
  public/seed/parking-1.svg … parking-6.svg
  src/lib/owner.ts, admin.ts, uploads.ts, format.ts
  src/components/ui/Select.tsx, TextArea.tsx, StatusBadge.tsx, Dialog.tsx, Tabs.tsx (sub-nav)
  src/components/owner/LocationPicker.tsx, PhotoManager.tsx, SlotManager.tsx, HoursEditor.tsx
  src/pages/owner/OwnerLayout.tsx, OwnerHomePage.tsx, OwnerVerificationPage.tsx, MyListingsPage.tsx,
                  ListingWizardPage.tsx, ListingBlocksPage.tsx,
                  wizard/LocationStep.tsx, PhotosStep.tsx, SlotsStep.tsx, PricingStep.tsx, HoursStep.tsx, ReviewStep.tsx
  src/pages/admin/AdminLayout.tsx, AdminHomePage.tsx, OwnerQueuePage.tsx, ListingQueuePage.tsx,
                  AdminListingReviewPage.tsx
  src/App.tsx (routes), components/layout/Navbar.tsx (links), pages/RoleHomePage.tsx (driver only)
README.md (Phase 2 status, CLOUDINARY_URL, new demo owners)
```

---

### Task 1: Schema, entities and repositories for listings

**Files:** `V4__listings.sql`; `common/model/VehicleType.java`; `common/web/PageResponse.java`; `owner/DocumentType.java`; modify `owner/OwnerProfile.java`; all entity/enum/repository files in `listing/`, `slot/`, `availability/` from the File Map (entities + repositories only); test `listing/ListingSchemaTest.java`.

**Interfaces produced:**
- `ParkingListing extends BaseEntity` fields (Lombok `@Getter @Setter`): `User owner` (ManyToOne LAZY, `owner_id`), `City city` (ManyToOne LAZY), `title, description, address, pincode`, `double lat, lng`, `ListingType listingType`, `boolean open24x7` (column `open_24x7`), `String rules`, `boolean autoApprove = true`, `ListingStatus status = DRAFT`, `String rejectionReason`, `BigDecimal pricePerHour, pricePerDay, pricePerMonth`, `CancellationPolicy cancellationPolicy = MODERATE`, `BigDecimal avgRating = 0.0`, `int reviewCount`, `Instant submittedAt, approvedAt`, `Set<Amenity> amenities` (`@ElementCollection(fetch = LAZY) @CollectionTable(name="listing_amenities", joinColumns=@JoinColumn(name="listing_id")) @Column(name="amenity") @Enumerated(STRING)`, initialised to `new HashSet<>()`).
- `ListingPhoto extends BaseEntity`: `ParkingListing listing`, `String url`, `String storageKey` (nullable), `int sortOrder`.
- `ParkingSlot extends BaseEntity`: `ParkingListing listing`, `String label`, `VehicleType vehicleType`, `SlotSize size`, `boolean active = true`.
- `AvailabilityRule extends BaseEntity`: `ParkingListing listing`, `int dayOfWeek`, `LocalTime openTime, closeTime`.
- `AvailabilityBlock extends BaseEntity`: `ParkingListing listing`, `ParkingSlot slot` (nullable), `Instant startTime, endTime`, `String reason`.
- `OwnerProfile`: **remove** `documentUrl`; **add** `DocumentType documentType` (change from String), `String documentKey`, `String documentContentType`, `Instant documentSubmittedAt`.
- Repositories:
  - `ParkingListingRepository extends JpaRepository<ParkingListing, Long>`: `Optional<ParkingListing> findByIdAndOwnerId(Long id, Long ownerId)`; `Page<ParkingListing> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId, Pageable p)`; `Page<ParkingListing> findByStatusOrderBySubmittedAtAsc(ListingStatus s, Pageable p)`; `long countByStatus(ListingStatus s)`; `long countByOwnerId(Long ownerId)`; `boolean existsByOwnerIdAndTitle(Long ownerId, String title)`.
  - `ListingPhotoRepository`: `List<ListingPhoto> findByListingIdOrderBySortOrderAsc(Long listingId)`; `long countByListingId(Long listingId)`; `Optional<ListingPhoto> findByIdAndListingId(Long id, Long listingId)`.
  - `ParkingSlotRepository`: `List<ParkingSlot> findByListingIdOrderByLabelAsc(Long listingId)`; `long countByListingId(Long)`; `long countByListingIdAndActiveTrue(Long)`; `boolean existsByListingIdAndLabelIgnoreCase(Long, String)`; `Optional<ParkingSlot> findByIdAndListingId(Long, Long)`.
  - `AvailabilityRuleRepository`: `List<AvailabilityRule> findByListingIdOrderByDayOfWeekAsc(Long)`; `@Modifying(flushAutomatically=true, clearAutomatically=true) void deleteByListingId(Long)` (use `@Query("delete from AvailabilityRule r where r.listing.id = :listingId")`).
  - `AvailabilityBlockRepository`: `List<AvailabilityBlock> findByListingIdAndEndTimeAfterOrderByStartTimeAsc(Long listingId, Instant after)`; `Optional<AvailabilityBlock> findByIdAndListingId(Long, Long)`.
  - `OwnerProfileRepository` add: `Page<OwnerProfile> findByVerificationStatusOrderByDocumentSubmittedAtAsc(VerificationStatus s, Pageable p)`; `long countByVerificationStatus(VerificationStatus s)`.
- `PageResponse<T>` record + `static <T> PageResponse<T> from(Page<T> page)`.

- [ ] **Step 1: Migration** — `backend/src/main/resources/db/migration/V4__listings.sql`:

```sql
ALTER TABLE owner_profiles DROP COLUMN document_url;
ALTER TABLE owner_profiles
    ADD COLUMN document_key          VARCHAR(500),
    ADD COLUMN document_content_type VARCHAR(100),
    ADD COLUMN document_submitted_at TIMESTAMPTZ;
ALTER TABLE owner_profiles
    ADD CONSTRAINT chk_owner_document_type CHECK (document_type IS NULL OR document_type IN
        ('AADHAAR','PAN','DRIVING_LICENCE','PASSPORT','VOTER_ID','PROPERTY_DOCUMENT','UTILITY_BILL'));

CREATE TABLE parking_listings (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    owner_id            BIGINT           NOT NULL REFERENCES users (id),
    city_id             BIGINT           NOT NULL REFERENCES cities (id),
    title               VARCHAR(120)     NOT NULL,
    description         VARCHAR(2000),
    address             VARCHAR(300)     NOT NULL,
    pincode             VARCHAR(6)       NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    lat                 DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN 6 AND 38),
    lng                 DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN 68 AND 98),
    listing_type        VARCHAR(20)      NOT NULL CHECK (listing_type IN ('METRO','OFFICE','COMMERCIAL','RESIDENTIAL','EVENT')),
    open_24x7           BOOLEAN          NOT NULL DEFAULT FALSE,
    rules               VARCHAR(2000),
    auto_approve        BOOLEAN          NOT NULL DEFAULT TRUE,
    status              VARCHAR(20)      NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','REJECTED','PAUSED','SUSPENDED')),
    rejection_reason    VARCHAR(500),
    price_per_hour      NUMERIC(10, 2)   CHECK (price_per_hour > 0),
    price_per_day       NUMERIC(10, 2)   CHECK (price_per_day > 0),
    price_per_month     NUMERIC(10, 2)   CHECK (price_per_month > 0),
    cancellation_policy VARCHAR(20)      NOT NULL DEFAULT 'MODERATE' CHECK (cancellation_policy IN ('FLEXIBLE','MODERATE','STRICT')),
    avg_rating          NUMERIC(2, 1)    NOT NULL DEFAULT 0,
    review_count        INT              NOT NULL DEFAULT 0,
    submitted_at        TIMESTAMPTZ,
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX idx_listings_owner ON parking_listings (owner_id);
CREATE INDEX idx_listings_status ON parking_listings (status);
CREATE INDEX idx_listings_city ON parking_listings (city_id);
CREATE INDEX idx_listings_lat_lng ON parking_listings (lat, lng);

CREATE TABLE listing_amenities (
    listing_id BIGINT      NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
    amenity    VARCHAR(30) NOT NULL CHECK (amenity IN ('COVERED','CCTV','EV_CHARGING','SECURITY_GUARD','WHEELCHAIR_ACCESS','WELL_LIT')),
    PRIMARY KEY (listing_id, amenity)
);

CREATE TABLE listing_photos (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    listing_id  BIGINT       NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    storage_key VARCHAR(500),
    sort_order  INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_listing_photos_listing ON listing_photos (listing_id);

CREATE TABLE parking_slots (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    listing_id   BIGINT      NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
    label        VARCHAR(20) NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL CHECK (vehicle_type IN ('TWO_WHEELER','FOUR_WHEELER')),
    size         VARCHAR(10) NOT NULL CHECK (size IN ('SMALL','MEDIUM','LARGE')),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parking_slots_label ON parking_slots (listing_id, lower(label));

CREATE TABLE availability_rules (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    listing_id  BIGINT      NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
    day_of_week INT         NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    open_time   TIME        NOT NULL,
    close_time  TIME        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (close_time > open_time),
    UNIQUE (listing_id, day_of_week)
);

CREATE TABLE availability_blocks (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    listing_id BIGINT       NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
    slot_id    BIGINT       REFERENCES parking_slots (id) ON DELETE CASCADE,
    start_time TIMESTAMPTZ  NOT NULL,
    end_time   TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);
CREATE INDEX idx_blocks_listing_time ON availability_blocks (listing_id, start_time);
```

Note: `owner_profiles.document_type` already exists as `VARCHAR(50)` from V1 — only the CHECK is added.

- [ ] **Step 2: Failing test** — `backend/src/test/java/com/smartparking/listing/ListingSchemaTest.java` (`@IntegrationTest`): autowire repos + `UserRepository` + `CityRepository` + `EntityManager`. Tests:
  1. `persistsListingWithAmenitiesPhotosSlotsRulesAndBlocks`: save owner (`TestUsers.newUser("schema@example.com", Role.OWNER)`), take `cityRepository.findBySlugs("maharashtra","pune")`, build a listing (title "Test", address "Addr", pincode "411001", lat 18.52, lng 73.85, type OFFICE, amenities {CCTV, COVERED}, pricePerHour 30.00), save; add photo (url "/x.png", sortOrder 0), slot ("A-01", FOUR_WHEELER, MEDIUM), rule (1, 09:00, 18:00), block (now+1h → now+3h); `em.flush(); em.clear();` reload listing by id and assert status DRAFT, amenities == {CCTV, COVERED}, `photoRepo.countByListingId == 1`, `slotRepo.countByListingIdAndActiveTrue == 1`, rules size 1, blocks after now size 1, `cancellationPolicy == MODERATE`, `autoApprove == true`.
  2. `slotLabelsAreUniquePerListingCaseInsensitive`: two slots "a-01" and "A-01" in same listing → `assertThatThrownBy(() -> { slotRepo.saveAndFlush(second); }).isInstanceOf(DataIntegrityViolationException.class)`.
  3. `ownerProfileStoresDocumentFields`: owner profile with `documentType = AADHAAR`, key "local/private/x", content type "application/pdf", submitted now → flush/clear/reload equals.

- [ ] **Step 3: Run** `cd backend && ./mvnw -q test -Dtest=ListingSchemaTest` → FAIL (classes missing).
- [ ] **Step 4: Implement** entities/enums/repos/`PageResponse` per Interfaces. `OwnerProfile.documentType` becomes `@Enumerated(EnumType.STRING) DocumentType`. `DemoAccountSeeder` currently sets `documentType` to `"AADHAAR"` — change to `DocumentType.AADHAAR`. Fix any compile errors from the removed `documentUrl`.
- [ ] **Step 5: Run full suite** `./mvnw -q test` → all pass (Hibernate validate passes against V4).
- [ ] **Step 6: Commit** `feat: listings, photos, slots and availability schema`

---

### Task 2: File storage (local + Cloudinary), upload validation, signed private URLs

**Files:** `pom.xml` (+ `com.cloudinary:cloudinary-http5:2.5.0`); `application.yml` (+ `app.storage`); `src/test/resources/application-test.yml` (+ `app.storage.local-dir: target/test-uploads`); all `storage/*` files; `common/error/GlobalExceptionHandler.java` (413 handler); `common/security/SecurityConfig.java` (permit GET `/uploads/**` and GET `/api/v1/files/private`); tests `storage/FileTypesTest`, `UrlSignerTest`, `LocalFileStorageTest`, `PrivateFileControllerTest`.

**Interfaces produced:**
- `enum UploadKind { IMAGE, DOCUMENT }` — IMAGE allows `image/jpeg, image/png, image/webp`; DOCUMENT allows those + `application/pdf`.
- `record ValidatedUpload(byte[] bytes, String contentType, String extension)`.
- `UploadValidator.validate(MultipartFile file, UploadKind kind) → ValidatedUpload` (static). Errors: null/empty → 400 `FILE_REQUIRED`; size > 5 MB → 413 `FILE_TOO_LARGE`; detected type null or not allowed → 400 `UNSUPPORTED_FILE_TYPE`.
- `record StoredFile(String key, String url, String contentType, long size)` (`url` null for private files).
- `interface FileStorage { StoredFile storePublic(ValidatedUpload u, String folder); StoredFile storePrivate(ValidatedUpload u, String folder); void delete(String key); SignedUrl privateUrl(String key, Duration ttl); }` with `record SignedUrl(String url, Instant expiresAt)` nested or top-level in `storage`. `delete` must never throw (log and continue) and ignore null keys and keys it doesn't own.
- Folder names: `listing-photos`, `owner-documents`.
- `UrlSigner(String base64Secret)`: `String sign(String key, long expiresEpochSeconds)` → URL-safe Base64 (no padding) of HMAC-SHA256 with key bytes = SHA-256(`"file-url:" + base64Secret`) over `key + "|" + expires`; `boolean verify(String key, long expires, String signature, Instant now)` → constant-time compare (`MessageDigest.isEqual`) and `now.getEpochSecond() <= expires`.
- `StorageProperties` (`app.storage`): `String localDir`, `String publicBaseUrl`, `String cloudinaryUrl`.
- `StorageConfig`: `@Bean UrlSigner` (from `JwtProperties.secret()`), `@Bean FileStorage` → Cloudinary if `cloudinaryUrl` has text, else `LocalFileStorage`; implements `WebMvcConfigurer.addResourceHandlers` mapping `/uploads/public/**` → `file:<absolute localDir>/public/` **only when local**.
- `PrivateFileController`: `GET /api/v1/files/private?key=&expires=&sig=` → verify; bad/expired → 403 `INVALID_SIGNATURE`; serves bytes with stored content type and `Content-Disposition: inline`, `Cache-Control: private, no-store`. Only handles `local/` keys.

**YAML** (append under `app:` in `application.yml`):
```yaml
  storage:
    local-dir: ${STORAGE_LOCAL_DIR:uploads}
    public-base-url: ${PUBLIC_BASE_URL:http://localhost:8080}
    cloudinary-url: ${CLOUDINARY_URL:}
```

**Key formats:** local `local/public/<folder>/<uuid>.<ext>` and `local/private/<folder>/<uuid>.<ext>`; Cloudinary `cloudinary/<upload|private>/<publicId>.<format>` (resource_type always `image` — Cloudinary treats PDF as image).

- [ ] **Step 1: Failing tests** (full code):

`backend/src/test/java/com/smartparking/storage/FileTypesTest.java`
```java
package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileTypesTest {

    static byte[] bytes(int... values) {
        byte[] b = new byte[Math.max(values.length, 16)];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    @Test
    void detectsJpegPngWebpPdf() {
        assertThat(FileTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(FileTypes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png");
        assertThat(FileTypes.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isEqualTo("image/webp");
        assertThat(FileTypes.detect(bytes('%', 'P', 'D', 'F', '-'))).isEqualTo("application/pdf");
    }

    @Test
    void rejectsUnknownAndTinyInput() {
        assertThat(FileTypes.detect(bytes('G', 'I', 'F', '8', '9', 'a'))).isNull();
        assertThat(FileTypes.detect(new byte[] {(byte) 0xFF})).isNull();
        assertThat(FileTypes.detect(null)).isNull();
    }

    @Test
    void extensionsMatchTypes() {
        assertThat(FileTypes.extension("image/jpeg")).isEqualTo("jpg");
        assertThat(FileTypes.extension("image/png")).isEqualTo("png");
        assertThat(FileTypes.extension("image/webp")).isEqualTo("webp");
        assertThat(FileTypes.extension("application/pdf")).isEqualTo("pdf");
    }
}
```

`backend/src/test/java/com/smartparking/storage/UrlSignerTest.java`
```java
package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UrlSignerTest {

    UrlSigner signer = new UrlSigner("dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2");
    Instant now = Instant.parse("2026-10-05T10:00:00Z");
    long expires = now.getEpochSecond() + 300;

    @Test
    void validSignatureVerifies() {
        String sig = signer.sign("local/private/owner-documents/a.pdf", expires);
        assertThat(sig).matches("[A-Za-z0-9_-]+");
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, sig, now)).isTrue();
    }

    @Test
    void tamperedKeyExpiryOrSignatureFails() {
        String sig = signer.sign("local/private/owner-documents/a.pdf", expires);
        assertThat(signer.verify("local/private/owner-documents/b.pdf", expires, sig, now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires + 1, sig, now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, sig + "x", now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, null, now)).isFalse();
    }

    @Test
    void expiredSignatureFails() {
        String sig = signer.sign("k", expires);
        assertThat(signer.verify("k", expires, sig, now.plusSeconds(301))).isFalse();
    }
}
```

`backend/src/test/java/com/smartparking/storage/LocalFileStorageTest.java`
```java
package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};
    Clock clock = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    UrlSigner signer = new UrlSigner("dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2");

    LocalFileStorage storage() {
        return new LocalFileStorage(dir, "http://localhost:8080", signer, clock);
    }

    @Test
    void storesPublicFileWithServableUrl() throws Exception {
        StoredFile f = storage().storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");

        assertThat(f.key()).matches("local/public/listing-photos/[0-9a-f-]{36}\\.png");
        assertThat(f.url()).isEqualTo("http://localhost:8080/uploads/public/listing-photos/"
                + f.key().substring("local/public/listing-photos/".length()));
        assertThat(Files.readAllBytes(dir.resolve(f.key().substring("local/".length())))).isEqualTo(PNG);
    }

    @Test
    void storesPrivateFileWithoutPublicUrlAndSignsDownloadUrl() {
        LocalFileStorage s = storage();
        StoredFile f = s.storePrivate(new ValidatedUpload(PNG, "image/png", "png"), "owner-documents");

        assertThat(f.url()).isNull();
        FileStorage.SignedUrl signed = s.privateUrl(f.key(), Duration.ofMinutes(5));
        assertThat(signed.url()).startsWith("http://localhost:8080/api/v1/files/private?key=");
        assertThat(signed.expiresAt()).isEqualTo(Instant.parse("2026-10-05T10:05:00Z"));
    }

    @Test
    void deleteRemovesFileAndIgnoresUnknownKeys() {
        LocalFileStorage s = storage();
        StoredFile f = s.storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");

        s.delete(f.key());
        s.delete(null);
        s.delete("cloudinary/upload/abc.png");
        s.delete("local/public/../../etc/passwd");

        assertThat(Files.exists(dir.resolve(f.key().substring("local/".length())))).isFalse();
    }

    @Test
    void resolvesOnlySafePrivateKeys() {
        LocalFileStorage s = storage();
        assertThat(s.resolvePrivate("local/private/../public/x.png")).isEmpty();
        assertThat(s.resolvePrivate("local/public/listing-photos/x.png")).isEmpty();
    }
}
```

`backend/src/test/java/com/smartparking/storage/PrivateFileControllerTest.java` (`@IntegrationTest`): autowire `MockMvc`, `FileStorage`, `UrlSigner`. Store a private PNG via `storePrivate(...)`, get `privateUrl(key, 5 min)`, strip the host, `GET` it without auth → 200, content type `image/png`, body equals bytes, header `Cache-Control` contains `no-store`. Then same URL with `sig` altered → 403 code `INVALID_SIGNATURE`. Then `expires` in the past with a valid signature for that past value → 403. Then `GET /uploads/public/<folder>/<file>` for a stored public file → 200.

- [ ] **Step 2: Run** → FAIL (classes missing).
- [ ] **Step 3: Implement.** Reference implementations (use as written):

```java
// FileTypes.java
package com.smartparking.storage;

public final class FileTypes {
    private FileTypes() {}

    public static String detect(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        return null;
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException("Unsupported type " + contentType);
        };
    }
}
```

```java
// UploadValidator.java
package com.smartparking.storage;

import com.smartparking.common.error.ApiException;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public final class UploadValidator {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp");

    private UploadValidator() {}

    public static ValidatedUpload validate(MultipartFile file, UploadKind kind) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Please choose a file to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files must be 5 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("FILE_REQUIRED", "The file could not be read");
        }
        String type = FileTypes.detect(bytes);
        boolean allowed = type != null && (IMAGES.contains(type) || (kind == UploadKind.DOCUMENT && type.equals("application/pdf")));
        if (!allowed) {
            String expected = kind == UploadKind.IMAGE ? "JPG, PNG or WebP images" : "JPG, PNG, WebP or PDF files";
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Only " + expected + " are allowed");
        }
        return new ValidatedUpload(bytes, type, FileTypes.extension(type));
    }
}
```

```java
// LocalFileStorage.java (core logic)
package com.smartparking.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileStorage implements FileStorage {
    private static final Pattern SAFE_KEY =
            Pattern.compile("^local/(public|private)/[a-z0-9-]+/[0-9a-f-]{36}\\.(jpg|png|webp|pdf)$");

    private final Path root;
    private final String publicBaseUrl;
    private final UrlSigner signer;
    private final Clock clock;

    public LocalFileStorage(Path root, String publicBaseUrl, UrlSigner signer, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.signer = signer;
        this.clock = clock;
    }

    public Path root() { return root; }

    @Override
    public StoredFile storePublic(ValidatedUpload upload, String folder) {
        String key = write("public", folder, upload);
        String url = publicBaseUrl + "/uploads/" + key.substring("local/".length());
        return new StoredFile(key, url, upload.contentType(), upload.bytes().length);
    }

    @Override
    public StoredFile storePrivate(ValidatedUpload upload, String folder) {
        return new StoredFile(write("private", folder, upload), null, upload.contentType(), upload.bytes().length);
    }

    @Override
    public void delete(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) return;
        try {
            Files.deleteIfExists(root.resolve(key.substring("local/".length())).normalize());
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }

    @Override
    public SignedUrl privateUrl(String key, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        long expires = expiresAt.getEpochSecond();
        String url = publicBaseUrl + "/api/v1/files/private?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + expires + "&sig=" + signer.sign(key, expires);
        return new SignedUrl(url, expiresAt);
    }

    /** Path of a private file for a safe key, or empty. */
    public Optional<Path> resolvePrivate(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || !key.startsWith("local/private/")) return Optional.empty();
        Path path = root.resolve(key.substring("local/".length())).normalize();
        return path.startsWith(root) && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private String write(String visibility, String folder, ValidatedUpload upload) {
        String key = "local/" + visibility + "/" + folder + "/" + UUID.randomUUID() + "." + upload.extension();
        Path path = root.resolve(key.substring("local/".length()));
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, upload.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
        return key;
    }
}
```

(`resolvePrivate` for the "x.png" test cases returns empty because the names aren't UUIDs / not private — matches the test.)

```java
// CloudinaryFileStorage.java (core logic)
package com.smartparking.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CloudinaryFileStorage implements FileStorage {
    private final Cloudinary cloudinary;
    private final Clock clock;

    public CloudinaryFileStorage(Cloudinary cloudinary, Clock clock) {
        this.cloudinary = cloudinary;
        this.clock = clock;
    }

    @Override
    public StoredFile storePublic(ValidatedUpload upload, String folder) {
        Map<?, ?> r = upload(upload, folder, "upload");
        return new StoredFile(key("upload", r), (String) r.get("secure_url"), upload.contentType(), upload.bytes().length);
    }

    @Override
    public StoredFile storePrivate(ValidatedUpload upload, String folder) {
        Map<?, ?> r = upload(upload, folder, "private");
        return new StoredFile(key("private", r), null, upload.contentType(), upload.bytes().length);
    }

    @Override
    public void delete(String key) {
        Parsed p = parse(key);
        if (p == null) return;
        try {
            cloudinary.uploader().destroy(p.publicId(), ObjectUtils.asMap("resource_type", "image", "type", p.type()));
        } catch (Exception e) {
            log.warn("Could not delete Cloudinary asset {}", key, e);
        }
    }

    @Override
    public SignedUrl privateUrl(String key, Duration ttl) {
        Parsed p = parse(key);
        if (p == null || !p.type().equals("private")) throw new IllegalArgumentException("Not a private Cloudinary key");
        Instant expiresAt = clock.instant().plus(ttl);
        try {
            String url = cloudinary.privateDownload(p.publicId(), p.format(), ObjectUtils.asMap(
                    "resource_type", "image", "type", "private", "expires_at", expiresAt.getEpochSecond()));
            return new SignedUrl(url, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign Cloudinary URL", e);
        }
    }

    private Map<?, ?> upload(ValidatedUpload upload, String folder, String type) {
        try {
            return cloudinary.uploader().upload(upload.bytes(), ObjectUtils.asMap(
                    "folder", "parkease/" + folder, "resource_type", "image", "type", type));
        } catch (IOException e) {
            throw new UncheckedIOException("Upload to Cloudinary failed", e);
        }
    }

    private static String key(String type, Map<?, ?> r) {
        return "cloudinary/" + type + "/" + r.get("public_id") + "." + r.get("format");
    }

    record Parsed(String type, String publicId, String format) {}

    static Parsed parse(String key) {
        if (key == null || !key.startsWith("cloudinary/")) return null;
        String rest = key.substring("cloudinary/".length());
        int slash = rest.indexOf('/');
        int dot = rest.lastIndexOf('.');
        if (slash < 0 || dot < slash) return null;
        return new Parsed(rest.substring(0, slash), rest.substring(slash + 1, dot), rest.substring(dot + 1));
    }
}
```

`PrivateFileController` (only active as a bean when the storage is local: `@ConditionalOnBean`-style is fragile — instead inject `FileStorage` and return 404 `NOT_FOUND` if it is not a `LocalFileStorage`). Uses `Clock` for `verify(..., clock.instant())`, then `resolvePrivate`; missing file → 404. Content type from `FileTypes.detect(bytes)`.

`GlobalExceptionHandler`: add `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 413 `FILE_TOO_LARGE` "Files must be 5 MB or smaller". (Must be declared before the generic handler; specific handlers win anyway.)

`SecurityConfig`: add `.requestMatchers(HttpMethod.GET, "/uploads/**", "/api/v1/files/private").permitAll()` next to the existing public GET line.

- [ ] **Step 4: Run** focused tests then `./mvnw -q test` → all pass.
- [ ] **Step 5: Commit** `feat: file storage with local and Cloudinary backends, upload validation and signed private URLs`

---

### Task 3: API exception extras + geo helper

**Files:** modify `common/error/ApiException.java`, `common/error/GlobalExceptionHandler.java`; create `common/web/GeoUtils.java`; tests `common/error/GlobalExceptionHandlerTest.java` (add one case to the existing `ErrorTestController`), `common/web/GeoUtilsTest.java`.

**Interfaces produced:**
- `ApiException`: `private final Map<String, Object> properties = new LinkedHashMap<>()`; `public ApiException with(String name, Object value)` (returns `this`); `public Map<String, Object> getProperties()` (unmodifiable view). `handleApi` copies each property onto the `ProblemDetail` via `setProperty`.
- `GeoUtils.distanceKm(double lat1, double lng1, double lat2, double lng2)` — Haversine, Earth radius 6371.0 km.

- [ ] **Step 1: Tests.** Add to `ErrorTestController` (test sources): `@GetMapping("/extra") void extra() { throw ApiException.badRequest("LISTING_INCOMPLETE", "Missing").with("missing", List.of("PHOTOS", "SLOTS")); }` and to `GlobalExceptionHandlerTest`: GET `/api/v1/test-errors/extra` → 400, `$.code` `LISTING_INCOMPLETE`, `$.missing[0]` `PHOTOS`, `$.missing[1]` `SLOTS`.
  `GeoUtilsTest`: Mumbai (19.0760, 72.8777) → Pune (18.5204, 73.8567) ≈ 120 km (`isCloseTo(120.0, within(3.0))`); same point → 0.0 (`within(1e-9)`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: problem detail extra properties and geo distance helper`

---

### Task 4: Owner profile, payout details and verification document upload

**Files:** `owner/OwnerProfileService.java`, `owner/OwnerController.java`, `owner/dto/OwnerProfileDto.java`, `owner/dto/PayoutRequest.java`; test support `support/OwnerTestSupport.java`; test `owner/OwnerControllerTest.java`.

**Interfaces produced:**
- `OwnerTestSupport` (test sources):
  - `static final MockMultipartFile` builders: `png(String name)` (PNG magic bytes + 16 zero bytes, content type `image/png`, param name `"file"`), `pdf()` (`"%PDF-1.4\n%%EOF"` bytes, `application/pdf`), `gif()` (`GIF89a` + zeros, declared `image/png` to prove sniffing).
  - `static String unverifiedOwner(MockMvc mvc, String email)` → registers OWNER via `AuthTestSupport.register` and returns `"Bearer <access>"`.
  - `static String verifiedOwner(MockMvc mvc, UserRepository users, OwnerProfileRepository profiles, String email)` → as above, then sets the profile `VERIFIED` (`verifiedAt = now`) through the repository; returns bearer.
- `OwnerProfileService`: `OwnerProfileDto get(Long ownerId)`; `OwnerProfileDto updatePayout(Long ownerId, PayoutRequest r)`; `OwnerProfileDto submitDocument(Long ownerId, DocumentType type, MultipartFile file)`; `SignedUrlDto documentUrl(Long ownerId)`; `OwnerProfile requireProfile(Long ownerId)`; `boolean isVerified(Long ownerId)`.
- Endpoints:
  - `GET /api/v1/owner/profile` → `OwnerProfileDto`.
  - `PUT /api/v1/owner/profile/payout` body `PayoutRequest(String upiId, String bankAccount, String ifsc, String accountName)` → `OwnerProfileDto`.
    Validation: `upiId` optional `@Pattern("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64}$")`; `bankAccount` optional `@Pattern("^[0-9]{9,18}$")`; `ifsc` optional `@Pattern("^[A-Z]{4}0[A-Z0-9]{6}$")`; `accountName` `@NotBlank @Size(max=100)`. Business rule: blank fields count as absent; require `upiId` OR (`bankAccount` AND `ifsc`), else 400 `PAYOUT_DETAILS_REQUIRED`. Saving replaces all four fields (blank → null).
  - `POST /api/v1/owner/verification` multipart `documentType` (enum) + `file` → `OwnerProfileDto` with status `PENDING`. VERIFIED → 409 `ALREADY_VERIFIED`. Allowed from UNSUBMITTED, REJECTED, PENDING (replaces). Stores via `storePrivate(..., "owner-documents")`; deletes the previous `documentKey`; sets `documentType`, `documentKey`, `documentContentType`, `documentSubmittedAt = now`, `rejectionReason = null`.
  - `GET /api/v1/owner/verification/document-url` → `SignedUrlDto` (TTL 5 min); 404 `NOT_FOUND` "No document uploaded" if no key.
- `payoutBankAccountLast4` = last 4 digits or null.

- [ ] **Step 1: Failing tests** — `backend/src/test/java/com/smartparking/owner/OwnerControllerTest.java`:

```java
package com.smartparking.owner;

import static com.smartparking.support.OwnerTestSupport.gif;
import static com.smartparking.support.OwnerTestSupport.pdf;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;

    @Test
    void newOwnerProfileIsUnsubmitted() throws Exception {
        String auth = unverifiedOwner(mvc, "o1@example.com");
        mvc.perform(get("/api/v1/owner/profile").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("UNSUBMITTED"))
                .andExpect(jsonPath("$.documentType").isEmpty());
    }

    @Test
    void driversCannotUseOwnerEndpoints() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "d1@example.com", "DRIVER")));
        mvc.perform(get("/api/v1/owner/profile").header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesUpiPayoutAndMasksBankAccount() throws Exception {
        String auth = unverifiedOwner(mvc, "o2@example.com");
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"ravi.k@okaxis","bankAccount":"123456789012","ifsc":"HDFC0001234","accountName":"Ravi Kumar"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutUpi").value("ravi.k@okaxis"))
                .andExpect(jsonPath("$.payoutBankAccountLast4").value("9012"))
                .andExpect(jsonPath("$.payoutIfsc").value("HDFC0001234"));
    }

    @Test
    void payoutNeedsUpiOrBankWithIfsc() throws Exception {
        String auth = unverifiedOwner(mvc, "o3@example.com");
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bankAccount":"123456789012","accountName":"Ravi Kumar"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYOUT_DETAILS_REQUIRED"));
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"not a upi","accountName":"Ravi"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("upiId"));
    }

    @Test
    void uploadingDocumentMovesToPendingAndGivesSignedUrl() throws Exception {
        String auth = unverifiedOwner(mvc, "o4@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "AADHAAR").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.documentType").value("AADHAAR"))
                .andExpect(jsonPath("$.documentSubmittedAt").isNotEmpty());
        mvc.perform(get("/api/v1/owner/verification/document-url").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("/api/v1/files/private?key=local%2Fprivate%2Fowner-documents%2F")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void rejectsDisguisedFileTypes() throws Exception {
        String auth = unverifiedOwner(mvc, "o5@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(gif())
                        .param("documentType", "PAN").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void verifiedOwnerCannotResubmit() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "o6@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "PAN").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }

    @Test
    void documentUrlIs404WithoutDocument() throws Exception {
        String auth = unverifiedOwner(mvc, "o7@example.com");
        mvc.perform(get("/api/v1/owner/verification/document-url").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run** → FAIL (404s / missing support class).
- [ ] **Step 3: Implement** per Interfaces. Owner id comes from `@AuthenticationPrincipal AuthUser`.
- [ ] **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: owner payout details and verification document upload`

---

### Task 5: Listing create/read/update/delete (basics) and pricing

**Files:** `listing/OwnerListingService.java`, `listing/ListingMapper.java`, `listing/OwnerListingController.java`, `listing/dto/ListingBasicsRequest.java`, `PricingRequest.java`, `ListingSummaryDto.java`, `ListingDetailDto.java`, `PhotoDto.java`; `slot/dto/SlotDto.java`, `availability/dto/HoursRuleDto.java` (DTO records only — services come later); test support `support/ListingTestSupport.java`; test `listing/OwnerListingControllerTest.java`.

**Interfaces produced:**
- `OwnerListingService`:
  - `ParkingListing requireOwned(Long ownerId, Long listingId)` → 404 `NOT_FOUND` "Listing not found".
  - `ParkingListing requireEditable(Long ownerId, Long listingId)` → `requireOwned` + SUSPENDED → 409 `LISTING_SUSPENDED`. **All later write operations (pricing, photos, slots, hours, blocks) use this.**
  - `ListingDetailDto create(Long ownerId, ListingBasicsRequest r)`; `ListingDetailDto get(...)`; `PageResponse<ListingSummaryDto> list(Long ownerId, int page, int size)`; `ListingDetailDto updateBasics(...)`; `ListingDetailDto updatePricing(...)`; `void delete(Long ownerId, Long listingId)`.
- `ListingMapper`: `ListingDetailDto toDetail(ParkingListing l, List<ListingPhoto> photos, List<ParkingSlot> slots, List<AvailabilityRule> rules)`; `ListingSummaryDto toSummary(ParkingListing l, String coverUrl, long activeSlots)`. Service loads children from repositories (no bidirectional collections on the entity).
- `ListingBasicsRequest(@NotNull Long cityId, @NotBlank @Size(max=120) String title, @Size(max=2000) String description, @NotBlank @Size(max=300) String address, @NotBlank @Pattern(regexp="^[1-9][0-9]{5}$", message="must be a valid 6-digit PIN code") String pincode, @NotNull @DecimalMin("6.0") @DecimalMax("38.0") Double lat, @NotNull @DecimalMin("68.0") @DecimalMax("98.0") Double lng, @NotNull ListingType listingType)`. Unknown `cityId` → 400 `VALIDATION_FAILED`-style: throw `ApiException.badRequest("INVALID_CITY", "Unknown city")`. Distance from city centre > 60 km → 400 `LOCATION_OUTSIDE_CITY` "The map pin must be within 60 km of <City>".
- `PricingRequest(@NotNull @DecimalMin("1.00") @DecimalMax("10000.00") @Digits(integer=8, fraction=2) BigDecimal pricePerHour, @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer=8, fraction=2) BigDecimal pricePerDay, @DecimalMin("1.00") @DecimalMax("1000000.00") @Digits(integer=8, fraction=2) BigDecimal pricePerMonth, @NotNull CancellationPolicy cancellationPolicy, @NotNull Boolean autoApprove, @NotNull Set<Amenity> amenities, @Size(max=2000) String rules)`. Rules: `pricePerDay` (if set) ≥ `pricePerHour`; `pricePerMonth` (if set) ≥ `pricePerDay` when both set, else ≥ `pricePerHour` → else 400 `INVALID_PRICING`. Prices stored with `setScale(2, HALF_UP)`.
- Endpoints (`/api/v1/owner/listings`):
  - `POST` → 201 `ListingDetailDto` (status DRAFT).
  - `GET ?page&size` → `PageResponse<ListingSummaryDto>` newest-updated first.
  - `GET /{id}` → `ListingDetailDto`.
  - `PUT /{id}` (basics) → `ListingDetailDto`.
  - `PUT /{id}/pricing` → `ListingDetailDto`.
  - `DELETE /{id}` → 204; allowed only in DRAFT, PENDING_REVIEW, REJECTED, PAUSED (else 409 `INVALID_STATUS` "Pause the listing before deleting it"); deletes stored photo files via `FileStorage.delete(storageKey)` for each photo, then the listing (DB cascades children).
- Edits never change status (APPROVED stays APPROVED, per spec).
- `ListingTestSupport` (test sources):
  - `static Long puneCityId(CityRepository cities)`.
  - `static String basicsJson(Long cityId, String title)` → Pune location (lat 18.5204, lng 73.8567, pincode "411001", type OFFICE, address "FC Road, Shivajinagar").
  - `static Long createListing(MockMvc mvc, String auth, Long cityId)` → POST, expect 201, return `$.id` as Long.
  - `static void makeComplete(MockMvc mvc, String auth, Long id)` → uploads `OwnerTestSupport.png("p.png")` to photos, creates slot `{"label":"A-01","vehicleType":"FOUR_WHEELER","size":"MEDIUM"}`, PUT pricing `{"pricePerHour":30,"cancellationPolicy":"MODERATE","autoApprove":true,"amenities":["CCTV"]}`, PUT hours `{"open24x7":true,"rules":[]}` — each expecting 2xx. (Used from Task 8; endpoints from Tasks 6–7 must exist by then.)

- [ ] **Step 1: Failing tests** — `backend/src/test/java/com/smartparking/listing/OwnerListingControllerTest.java`:

```java
package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.basicsJson;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.location.CityRepository;
import com.smartparking.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerListingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    String auth;
    Long pune;

    @BeforeEach
    void setUp() throws Exception {
        auth = unverifiedOwner(mvc, "lister@example.com");
        pune = puneCityId(cities);
    }

    @Test
    void createsDraftListingAndReadsItBack() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.cityName").value("Pune"))
                .andExpect(jsonPath("$.stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.photos.length()").value(0))
                .andExpect(jsonPath("$.autoApprove").value(true))
                .andExpect(jsonPath("$.cancellationPolicy").value("MODERATE"));
        mvc.perform(get("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].slotCount").value(0));
    }

    @Test
    void rejectsPinFarFromCity() throws Exception {
        String json = basicsJson(pune, "Far").replace("18.5204", "28.6139").replace("73.8567", "77.2090");
        mvc.perform(post("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_OUTSIDE_CITY"))
                .andExpect(jsonPath("$.detail").value(containsString("Pune")));
    }

    @Test
    void validatesBasics() throws Exception {
        mvc.perform(post("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cityId":%d,"title":"","address":"x","pincode":"012345","lat":18.5,"lng":73.8,"listingType":"OFFICE"}"""
                                .formatted(pune)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void otherOwnersGet404() throws Exception {
        Long id = createListing(mvc, auth, pune);
        String other = unverifiedOwner(mvc, "other@example.com");
        mvc.perform(get("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
    }

    @Test
    void savesPricingAndAmenities() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(put("/api/v1/owner/listings/" + id + "/pricing").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pricePerHour":30,"pricePerDay":200,"pricePerMonth":3500,"cancellationPolicy":"FLEXIBLE",
                                 "autoApprove":false,"amenities":["WELL_LIT","CCTV"],"rules":"No overnight parking"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerHour").value(30.0))
                .andExpect(jsonPath("$.pricePerDay").value(200.0))
                .andExpect(jsonPath("$.autoApprove").value(false))
                .andExpect(jsonPath("$.amenities[0]").value("CCTV"))
                .andExpect(jsonPath("$.amenities[1]").value("WELL_LIT"));
    }

    @Test
    void rejectsInconsistentPricing() throws Exception {
        Long id = createListing(mvc, auth, pune);
        mvc.perform(put("/api/v1/owner/listings/" + id + "/pricing").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pricePerHour":50,"pricePerDay":40,"cancellationPolicy":"MODERATE","autoApprove":true,"amenities":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRICING"));
    }

    @Test
    void deleteAllowedForDraftButNotApproved() throws Exception {
        Long draft = createListing(mvc, auth, pune);
        mvc.perform(delete("/api/v1/owner/listings/" + draft).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        Long approved = createListing(mvc, auth, pune);
        listings.findById(approved).orElseThrow().setStatus(ListingStatus.APPROVED);
        mvc.perform(delete("/api/v1/owner/listings/" + approved).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void suspendedListingsCannotBeEdited() throws Exception {
        Long id = createListing(mvc, auth, pune);
        listings.findById(id).orElseThrow().setStatus(ListingStatus.SUSPENDED);
        mvc.perform(put("/api/v1/owner/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(basicsJson(pune, "New title")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_SUSPENDED"));
    }
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: owner listing CRUD with location check and pricing`

---

### Task 6: Slots (single, bulk, update, delete) and weekly hours

**Files:** `slot/SlotService.java`, `slot/SlotController.java`, `slot/dto/SlotRequest.java`, `BulkSlotRequest.java`; `availability/AvailabilityService.java` (hours part), `availability/AvailabilityController.java` (hours endpoints), `availability/dto/HoursRequest.java`; tests `slot/SlotControllerTest.java`, `availability/AvailabilityControllerTest.java` (hours cases; block cases added in Task 7).

**Interfaces produced:**
- `SlotRequest(@NotBlank @Size(max=20) @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9 -]*$") String label, @NotNull VehicleType vehicleType, @NotNull SlotSize size, Boolean active)` (null `active` → true; label trimmed).
- `BulkSlotRequest(@NotBlank @Size(max=10) @Pattern(regexp="^[A-Za-z0-9-]*$") String prefix, @Min(1) @Max(999) int startNumber, @Min(1) @Max(50) int count, @NotNull VehicleType vehicleType, @NotNull SlotSize size)` → labels `prefix + zero-padded number (2 digits, or 3 if startNumber+count-1 ≥ 100)`, e.g. prefix "A-", start 1, count 3 → `A-01, A-02, A-03`. All-or-nothing: any existing label → 409 `SLOT_LABEL_TAKEN` naming the first clash.
- Max 200 slots per listing → 409 `SLOT_LIMIT`.
- Endpoints (`/api/v1/owner/listings/{id}/slots`): `GET` → `List<SlotDto>` by label; `POST` → 201 `SlotDto`; `POST /bulk` → 201 `List<SlotDto>`; `PUT /{slotId}` → `SlotDto` (label change also checked for clash, excluding itself); `DELETE /{slotId}` → 204.
- `HoursRequest(@NotNull Boolean open24x7, @NotNull @Valid List<RuleRequest> rules)`; `RuleRequest(@Min(1) @Max(7) int dayOfWeek, @NotNull LocalTime openTime, @NotNull LocalTime closeTime)` (JSON `"09:00"`). Rules: duplicate day → 400 `DUPLICATE_DAY`; `closeTime <= openTime` → 400 `INVALID_HOURS`; when `open24x7` true the rules list is ignored and existing rules deleted.
- Endpoints: `GET /api/v1/owner/listings/{id}/hours` → `{ "open24x7": bool, "rules": [HoursRuleDto] }`; `PUT` same path with `HoursRequest` → same shape. Replace-all semantics.

- [ ] **Step 1: Failing tests** — `SlotControllerTest` (`@IntegrationTest`, owner via `unverifiedOwner`, listing via `createListing`):
  1. `addsSlotAndListsByLabel`: POST `{"label":"B-02","vehicleType":"TWO_WHEELER","size":"SMALL"}` → 201 `$.active` true; POST `A-01` FOUR_WHEELER MEDIUM; GET → `$[0].label` "A-01", `$[1].label` "B-02".
  2. `rejectsDuplicateLabelCaseInsensitive`: POST "A-01" then "a-01" → 409 `SLOT_LABEL_TAKEN`.
  3. `bulkCreatesPaddedLabels`: POST bulk `{"prefix":"C-","startNumber":1,"count":3,"vehicleType":"FOUR_WHEELER","size":"LARGE"}` → 201, `$.length()` 3, `$[0].label` "C-01", `$[2].label` "C-03".
  4. `bulkIsAllOrNothing`: POST "C-02" single, then bulk C- 1..3 → 409 `SLOT_LABEL_TAKEN`; GET → length 1.
  5. `updatesAndDeletesSlot`: create, PUT `{"label":"A-09","vehicleType":"FOUR_WHEELER","size":"LARGE","active":false}` → 200 `$.active` false `$.size` "LARGE"; DELETE → 204; GET → length 0.
  6. `slotOfAnotherListingIs404`: create slot on listing X; PUT/DELETE it through listing Y (same owner) → 404.
  7. `enforcesSlotLimit`: bulk 50 ×4 (prefixes A-,B-,C-,D-) → OK (200 slots); one more single → 409 `SLOT_LIMIT`.

  `AvailabilityControllerTest` (hours part):
  1. `savesWeeklyHours`: PUT `{"open24x7":false,"rules":[{"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"},{"dayOfWeek":6,"openTime":"10:00","closeTime":"16:30"}]}` → 200, `$.rules.length()` 2, `$.rules[1].closeTime` "16:30"; GET returns the same.
  2. `open24x7ClearsRules`: save rules then PUT `{"open24x7":true,"rules":[{"dayOfWeek":1,"openTime":"08:00","closeTime":"20:00"}]}` → `$.open24x7` true, `$.rules.length()` 0.
  3. `rejectsDuplicateDays` → 400 `DUPLICATE_DAY`; 4. `rejectsCloseBeforeOpen` (`"closeTime":"07:00"` vs open 08:00) → 400 `INVALID_HOURS`.

  (Write these as full JUnit + MockMvc code in the same style as `OwnerListingControllerTest`.)
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: parking slots with bulk creation and weekly opening hours`

---

### Task 7: Photos and blocked times

**Files:** `listing/ListingPhotoService.java`, `listing/ListingPhotoController.java`, `listing/dto/PhotoOrderRequest.java`; `availability/AvailabilityService.java` (+ blocks), `AvailabilityController.java` (+ blocks), `availability/dto/BlockRequest.java`, `BlockDto.java`; tests `listing/ListingPhotoControllerTest.java`, `availability/AvailabilityControllerTest.java` (+ block cases).

**Interfaces produced:**
- Photos (`/api/v1/owner/listings/{id}/photos`): `POST` multipart `file` → 201 `PhotoDto` (validated `UploadKind.IMAGE`, stored `storePublic(..., "listing-photos")`, `sortOrder` = current count); 9th → 409 `PHOTO_LIMIT` "A listing can have at most 8 photos" (checked **before** storing); `DELETE /{photoId}` → 204, deletes file via storage, re-numbers remaining `sortOrder` 0..n-1; `PUT /order` body `PhotoOrderRequest(@NotEmpty List<Long> photoIds)` → `List<PhotoDto>` in new order; ids must be exactly the listing's photo set (same size, no duplicates) else 400 `INVALID_PHOTO_ORDER`. First photo = cover.
- Blocks (`/api/v1/owner/listings/{id}/blocks`): `GET` → upcoming blocks (`endTime > now`) ordered by start; `POST` body `BlockRequest(Long slotId, @NotNull Instant startTime, @NotNull Instant endTime, @Size(max=200) String reason)` → 201 `BlockDto`; `DELETE /{blockId}` → 204. Rules → 400 `INVALID_BLOCK`: `endTime <= startTime`; `endTime <= now`; `startTime > now + 365 days`; `slotId` given but not in this listing (message "Slot not found in this listing").

- [ ] **Step 1: Failing tests.**
  `ListingPhotoControllerTest`: (1) upload PNG → 201, `$.url` starts with `http://localhost:8080/uploads/public/listing-photos/`, `$.sortOrder` 0; GET listing → `$.photos.length()` 1. (2) upload GIF disguised → 400 `UNSUPPORTED_FILE_TYPE`. (3) 8 uploads OK, 9th → 409 `PHOTO_LIMIT`. (4) upload 3, `PUT /order` with reversed ids → 200 and `$[0].id` = third id; GET listing → `$.photos[0].id` = third id and `coverPhotoUrl` in summary list = that photo's url. (5) `PUT /order` with a missing id → 400 `INVALID_PHOTO_ORDER`. (6) delete middle photo → 204; remaining sortOrders are 0 and 1. (7) the stored file of a deleted photo no longer exists: autowire `FileStorage`, cast to `LocalFileStorage`, check `Files.exists(root.resolve(key without "local/"))` is false after delete (get the key via `ListingPhotoRepository` before deleting).
  `AvailabilityControllerTest` additions — use a fixed reference: `Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS)` (tests may read the real clock; the service uses `Clock`): (1) whole-listing block base→base+4h with reason "Maintenance" → 201 `$.slotId` null; GET → length 1. (2) slot block with a slot of this listing → `$.slotLabel` "A-01". (3) end before start → 400 `INVALID_BLOCK`. (4) already-ended block (now-3h → now-1h) → 400 `INVALID_BLOCK`. (5) start > 366 days ahead → 400. (6) slot of another listing → 400 `INVALID_BLOCK`. (7) delete → 204 and GET length 0. (8) other owner deleting → 404.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: listing photos with ordering and blocked availability windows`

---

### Task 8: Submit for review, pause, resume

**Files:** `listing/OwnerListingService.java` (+ submit/pause/resume), `OwnerListingController.java` (+ endpoints); test `listing/ListingSubmitTest.java`.

**Interfaces produced:**
- `POST /api/v1/owner/listings/{id}/submit` → `ListingDetailDto` status `PENDING_REVIEW`, `submittedAt = now`, `rejectionReason = null`.
  Order of checks: (1) status must be DRAFT or REJECTED else 409 `INVALID_STATUS`; (2) owner profile VERIFIED else 403 `OWNER_NOT_VERIFIED` "Verify your identity before submitting listings"; (3) completeness — collect all missing parts in this order: `PHOTOS` (0 photos), `SLOTS` (0 active slots), `PRICING` (`pricePerHour` null), `AVAILABILITY` (not `open24x7` and 0 rules); if any → 400 `LISTING_INCOMPLETE` "Complete all steps before submitting" with property `missing`.
- `POST /{id}/pause` → APPROVED → PAUSED else 409 `INVALID_STATUS`. `POST /{id}/resume` → PAUSED → APPROVED else 409.

Reference implementation for the completeness check:

```java
List<String> missing = new ArrayList<>();
if (photos.countByListingId(listing.getId()) == 0) missing.add("PHOTOS");
if (slots.countByListingIdAndActiveTrue(listing.getId()) == 0) missing.add("SLOTS");
if (listing.getPricePerHour() == null) missing.add("PRICING");
if (!listing.isOpen24x7() && rules.findByListingIdOrderByDayOfWeekAsc(listing.getId()).isEmpty()) missing.add("AVAILABILITY");
if (!missing.isEmpty()) {
    throw ApiException.badRequest("LISTING_INCOMPLETE", "Complete all steps before submitting").with("missing", missing);
}
```

- [ ] **Step 1: Failing tests** — `ListingSubmitTest` (`@IntegrationTest`):
  1. `unverifiedOwnerCannotSubmit`: unverified owner, complete listing (`makeComplete`) → 403 `OWNER_NOT_VERIFIED`.
  2. `incompleteListingListsMissingParts`: verified owner, bare draft → 400 `LISTING_INCOMPLETE`, `$.missing` equals `["PHOTOS","SLOTS","PRICING","AVAILABILITY"]` (assert each index).
  3. `completeListingGoesToPendingReview`: verified, `makeComplete`, submit → 200 `$.status` `PENDING_REVIEW`, `$.submittedAt` not empty; submitting again → 409 `INVALID_STATUS`.
  4. `rejectedListingCanBeResubmitted`: set status REJECTED with reason via repository → submit → 200 PENDING_REVIEW, `$.rejectionReason` empty.
  5. `pauseAndResumeOnlyFromApprovedOrPaused`: draft pause → 409; set APPROVED via repo → pause 200 `PAUSED` → resume 200 `APPROVED` → resume again 409.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: submit listings for review, pause and resume`

---

### Task 9: Admin review queues (owners and listings) with emails

**Files:** `admin/AdminReviewService.java`, `admin/AdminOwnerController.java`, `admin/AdminListingController.java`, `admin/dto/*` (see File Map); modify `email/EmailTemplates.java`; test `admin/AdminReviewControllerTest.java`.

**Interfaces produced:**
- `ReasonRequest(@NotBlank @Size(max=500) String reason)`.
- Owners (`/api/v1/admin/owners`):
  - `GET ?status=PENDING&page&size` (default `PENDING`) → `PageResponse<AdminOwnerDto>` ordered by `documentSubmittedAt` asc. `hasPayoutDetails` = upi present OR (bank + ifsc present). `listingCount` = `ParkingListingRepository.countByOwnerId`.
  - `GET /{userId}/document-url` → `SignedUrlDto` (5 min); 404 if user isn't an owner or has no document.
  - `POST /{userId}/verify` → `AdminOwnerDto`; only from PENDING else 409 `INVALID_STATUS`; sets VERIFIED, `verifiedAt = now`, `rejectionReason = null`; sends `EmailTemplates.ownerVerified(user, frontendUrl + "/owner/listings")`.
  - `POST /{userId}/reject` body `ReasonRequest` → only from PENDING; sets REJECTED + reason; sends `ownerRejected(user, reason, frontendUrl + "/owner/verification")`.
- Listings (`/api/v1/admin/listings`):
  - `GET ?status=PENDING_REVIEW&page&size` (default `PENDING_REVIEW`) → `PageResponse<AdminListingSummaryDto>` ordered by `submittedAt` asc.
  - `GET /{id}` → `AdminListingDetailDto`.
  - `POST /{id}/approve` → only from PENDING_REVIEW else 409; APPROVED, `approvedAt = now`, `rejectionReason = null`; email `listingApproved(owner, title, frontendUrl + "/owner/listings")`.
  - `POST /{id}/reject` body `ReasonRequest` → only from PENDING_REVIEW; REJECTED + reason; email `listingRejected(owner, title, reason, frontendUrl + "/owner/listings/" + id + "/edit")`.
- `GET /api/v1/admin/queues` → `QueueCountsDto(pendingOwners, pendingListings)`.
- `EmailTemplates` adds (reuse the private `build`; subjects exact):
  - `ownerVerified(User, String link)` — subject `"You're verified – ParkEase"`, body "Your identity has been verified. You can now submit parking listings for approval.", CTA "List your parking".
  - `ownerRejected(User, String reason, String link)` — subject `"Verification needs attention – ParkEase"`, body "We could not verify your document. Reason: <reason>. Please upload a clearer or different document.", CTA "Upload again".
  - `listingApproved(User, String title, String link)` — subject `"Your listing is live – ParkEase"`, body "Good news! \"<title>\" has been approved and drivers can now find it.", CTA "View your listings".
  - `listingRejected(User, String title, String reason, String link)` — subject `"Listing needs changes – ParkEase"`, body "\"<title>\" was not approved. Reason: <reason>. Update it and submit again.", CTA "Edit listing".
  (`build` HTML-escapes body text; keep that.)

- [ ] **Step 1: Failing tests** — `AdminReviewControllerTest` (`@IntegrationTest`). Admin token: create a user via `TestUsers.newUser("admin-t@example.com", Role.ADMIN)` saved with a BCrypt hash of `AuthTestSupport.PASSWORD` (autowire `PasswordEncoder`), then log in via `/api/v1/auth/login` and take the access token. Clear `RecordingEmailSender` in `@BeforeEach`. Cases:
  1. `listsPendingOwnersAndVerifiesOne`: owner uploads PDF (`multipart` to `/api/v1/owner/verification`) → admin GET `/api/v1/admin/owners` → `$.content[0].email` owner email, `$.content[0].documentType` "AADHAAR", `$.content[0].hasPayoutDetails` false; GET `/{userId}/document-url` → 200 `$.url` contains `/api/v1/files/private`; POST verify → 200 `$.verificationStatus` VERIFIED; email to owner subject contains "verified"; GET owner profile as the owner → VERIFIED; verify again → 409 `INVALID_STATUS`.
  2. `rejectsOwnerWithReason`: POST reject `{"reason":"Photo is blurry"}` → 200 REJECTED, `$.rejectionReason` "Photo is blurry"; email body contains "Photo is blurry"; empty reason → 400 `VALIDATION_FAILED`.
  3. `approvesListing`: verified owner (`verifiedOwner`), `createListing` + `makeComplete` + submit → admin GET `/api/v1/admin/listings` → `$.content[0].ownerEmail`; GET `/{id}` → `$.listing.status` PENDING_REVIEW, `$.owner.verificationStatus` VERIFIED; POST approve → 200 `AdminListingDetailDto` with `$.listing.status` APPROVED, `$.listing.approvedAt` not empty; email subject contains "live"; `GET /api/v1/admin/queues` → `$.pendingListings` 0.
  4. `rejectsListingAndOwnerCanResubmit`: reject `{"reason":"Add a clearer entrance photo"}` → `$.listing.status` REJECTED; owner GET listing → `$.rejectionReason` matches; owner submit again → 200 PENDING_REVIEW.
  5. `nonAdminsAreForbidden`: owner token GET `/api/v1/admin/owners` → 403.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Full suite** pass.
- [ ] **Step 5: Commit** `feat: admin queues for owner verification and listing approval`

---

### Task 10: Demo owners, listings and review-queue items (dev profile)

**Files:** `src/main/resources/seed/demo-listings.psv`; `common/seed/DemoListingSeeder.java`; modify `common/seed/DemoAccountSeeder.java` (add `@Order(1)`); `frontend/public/seed/parking-1.svg` … `parking-6.svg`; test `common/seed/DemoListingSeederTest.java`.

**Interfaces produced:**
- `DemoListingSeeder` — `@Component @Profile("dev") @Order(2) implements ApplicationRunner`; public `@Transactional void seed()`; idempotent (skips a listing when `existsByOwnerIdAndTitle`); constructor takes repositories, `PasswordEncoder`, `FileStorage`, `Clock`, `@Value("${app.seed.demo-password}") String demoPassword` (so the test can construct it directly).
- Demo owners (all `emailVerified = true`, password = demo password, owner profile VERIFIED with `documentType` AADHAAR, `verifiedAt = now`, UPI payout `<firstname>@okaxis`, account name = name):

| key | email | name | phone |
|---|---|---|---|
| `west` | `owner@parkease.dev` (existing, Priya Sharma) | — | — |
| `north` | `owner.north@parkease.dev` | Amit Khanna | 9000000011 |
| `south` | `owner.south@parkease.dev` | Karthik Iyer | 9000000012 |
| `east` | `owner.east@parkease.dev` | Sourav Das | 9000000013 |
| `northeast` | `owner.northeast@parkease.dev` | Lalremruata Pachuau | 9000000014 |
| `central` | `owner.central@parkease.dev` | Neha Joshi | 9000000015 |

- Review-queue demo items:
  - Pending owner `owner.pending@parkease.dev` (Vikram Singh, 9000000016): profile PENDING, `documentType` DRIVING_LICENCE, document = a tiny valid PDF stored with `FileStorage.storePrivate(new ValidatedUpload(pdfBytes, "application/pdf", "pdf"), "owner-documents")` where `pdfBytes` = `"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF"` (US-ASCII); `documentSubmittedAt = now`. Only stored the first time.
  - One `PENDING_REVIEW` listing owned by `west`: "Viman Nagar Residency Parking", Pune, "Off Airport Road, Viman Nagar, Pune", 411014, 18.5679, 73.9143, RESIDENTIAL, ₹25/₹150/₹2500, 2W 4 / 4W 4, not 24×7, amenities COVERED;CCTV, photo 3, `submittedAt = now`.
- Every PSV row becomes an **APPROVED** listing (`approvedAt = now`, `submittedAt = now`), `cancellationPolicy` MODERATE, `autoApprove` true, description `"Secure <type-lowercase> parking at <title>. Reserve in advance on ParkEase."`, rules `"Park only in your assigned slot. Follow staff instructions."`. Slots: four-wheelers `A-01…A-NN` MEDIUM, two-wheelers `B-01…B-NN` SMALL. Hours: if `open24x7` true → 24×7; else Mon–Sat 08:00–22:00 and Sun 09:00–21:00. Photos: two per listing, url `/seed/parking-<n>.svg` and `/seed/parking-<n % 6 + 1>.svg`, `storageKey` null.
- PSV columns (pipe-separated, header line starts with `#`, skip blank lines):
  `owner|stateSlug|citySlug|title|address|pincode|lat|lng|type|pricePerHour|pricePerDay|pricePerMonth|twoWheelers|fourWheelers|open24x7|amenities(;)|photo`

- [ ] **Step 1: Seed file** — `backend/src/main/resources/seed/demo-listings.psv` (use verbatim):

```
# owner|stateSlug|citySlug|title|address|pincode|lat|lng|type|hour|day|month|2W|4W|24x7|amenities|photo
south|andhra-pradesh|vijayawada|Benz Circle Commercial Parking|Benz Circle, MG Road, Vijayawada|520010|16.4997|80.6560|COMMERCIAL|30|200|3500|4|8|false|CCTV;WELL_LIT|1
south|andhra-pradesh|visakhapatnam|RK Beach Road Parking|Beach Road near RK Beach, Visakhapatnam|530002|17.7145|83.3233|COMMERCIAL|25|180|3000|6|10|false|WELL_LIT;SECURITY_GUARD|2
northeast|arunachal-pradesh|itanagar|Ganga Market Parking|Ganga Market, Itanagar|791111|27.0866|93.6100|COMMERCIAL|15|100|1800|3|6|false|WELL_LIT|3
northeast|assam|guwahati|Paltan Bazaar Station Parking|Station Road, Paltan Bazaar, Guwahati|781008|26.1806|91.7520|METRO|20|150|2500|6|12|true|CCTV;SECURITY_GUARD;WELL_LIT|4
east|bihar|patna|Patna Junction Parking|Fraser Road near Patna Junction, Patna|800001|25.6036|85.1372|METRO|20|150|2500|6|12|true|CCTV;SECURITY_GUARD|5
central|chhattisgarh|raipur|Pandri Market Parking|Pandri Cloth Market, Raipur|492004|21.2565|81.6560|COMMERCIAL|15|100|1800|4|8|false|WELL_LIT|6
west|goa|panaji|Church Square Parking|18th June Road near Church Square, Panaji|403001|15.4986|73.8290|COMMERCIAL|40|250|4000|4|6|false|CCTV;WELL_LIT|1
west|gujarat|ahmedabad|SG Highway Office Parking|Near Iscon Cross Roads, SG Highway, Ahmedabad|380054|23.0258|72.5070|OFFICE|30|200|3500|8|10|false|COVERED;CCTV;EV_CHARGING|2
west|gujarat|surat|Ring Road Textile Market Parking|Ring Road, Surat|395002|21.1882|72.8370|COMMERCIAL|20|150|2500|4|10|false|CCTV|3
north|haryana|gurugram|Cyber City Office Parking|DLF Cyber City, Gurugram|122002|28.4949|77.0887|OFFICE|60|400|6000|10|6|false|COVERED;CCTV;EV_CHARGING;SECURITY_GUARD|4
north|himachal-pradesh|shimla|Cart Road Lift Parking|Near Lift, Cart Road, Shimla|171001|31.1033|77.1722|COMMERCIAL|50|300|5000|2|8|true|COVERED;SECURITY_GUARD|5
east|jharkhand|ranchi|Firayalal Chowk Parking|Main Road near Firayalal Chowk, Ranchi|834001|23.3615|85.3250|COMMERCIAL|20|120|2200|4|8|false|CCTV|6
south|karnataka|bengaluru|MG Road Metro Parking|Near MG Road Metro Station, Bengaluru|560001|12.9756|77.6066|METRO|40|250|4500|8|12|true|COVERED;CCTV;SECURITY_GUARD;WELL_LIT|1
south|karnataka|bengaluru|Whitefield ITPL Office Parking|ITPL Main Road, Whitefield, Bengaluru|560066|12.9855|77.7363|OFFICE|35|220|4000|12|8|false|COVERED;CCTV;EV_CHARGING|2
south|karnataka|mysuru|Palace South Gate Parking|Near Palace South Gate, Mysuru|570001|12.3027|76.6553|COMMERCIAL|20|120|2000|4|10|false|WELL_LIT;SECURITY_GUARD|3
south|kerala|kochi|Edappally Junction Parking|NH 66 near Edappally, Kochi|682024|10.0261|76.3083|COMMERCIAL|30|200|3500|6|10|false|COVERED;CCTV|4
south|kerala|thiruvananthapuram|Technopark Phase 1 Parking|Technopark, Kazhakkoottam, Thiruvananthapuram|695581|8.5581|76.8816|OFFICE|25|150|3000|8|8|false|CCTV;EV_CHARGING|5
central|madhya-pradesh|indore|Rajwada Market Parking|Near Rajwada, Indore|452004|22.7187|75.8558|COMMERCIAL|20|120|2200|4|10|false|CCTV;WELL_LIT|6
central|madhya-pradesh|bhopal|MP Nagar Zone 1 Parking|MP Nagar Zone 1, Bhopal|462011|23.2330|77.4343|OFFICE|20|120|2200|6|8|false|CCTV|1
west|maharashtra|mumbai|Andheri Metro Station Parking|Near Andheri Metro Station, Andheri East, Mumbai|400069|19.1197|72.8468|METRO|60|400|7000|10|10|true|COVERED;CCTV;SECURITY_GUARD;WELL_LIT|2
west|maharashtra|mumbai|BKC Office Parking|G Block, Bandra Kurla Complex, Mumbai|400051|19.0660|72.8692|OFFICE|80|500|9000|12|6|false|COVERED;CCTV;EV_CHARGING;SECURITY_GUARD|3
west|maharashtra|pune|Hinjewadi Phase 1 Office Parking|Rajiv Gandhi Infotech Park, Hinjewadi, Pune|411057|18.5913|73.7389|OFFICE|30|200|3500|12|10|false|COVERED;CCTV;EV_CHARGING|4
west|maharashtra|nagpur|Sitabuldi Main Road Parking|Sitabuldi, Nagpur|440012|21.1458|79.0821|COMMERCIAL|20|120|2200|4|10|false|CCTV|5
northeast|manipur|imphal|Thangal Bazar Parking|Thangal Bazar near Ima Keithel, Imphal|795001|24.8090|93.9380|COMMERCIAL|15|100|1800|2|8|false|WELL_LIT|6
northeast|meghalaya|shillong|Police Bazar Parking|Police Bazar, Shillong|793001|25.5760|91.8826|COMMERCIAL|30|180|3000|4|6|false|CCTV;SECURITY_GUARD|1
northeast|mizoram|aizawl|Bara Bazar Parking|Bara Bazar, Aizawl|796001|23.7250|92.7180|COMMERCIAL|15|100|1800|2|6|false|WELL_LIT|2
northeast|nagaland|kohima|Kohima Town Parking|Near Kohima War Cemetery, Kohima|797001|25.6700|94.1060|COMMERCIAL|15|100|1800|2|6|false|WELL_LIT|3
east|odisha|bhubaneswar|Master Canteen Station Parking|Station Square, Bhubaneswar|751001|20.2700|85.8430|METRO|20|120|2200|6|10|true|CCTV;SECURITY_GUARD|4
north|punjab|amritsar|Golden Temple Heritage Street Parking|Near Golden Temple Plaza, Amritsar|143006|31.6220|74.8770|COMMERCIAL|25|150|2500|6|12|true|COVERED;CCTV;SECURITY_GUARD|5
north|punjab|ludhiana|Ferozepur Road Parking|Ferozepur Road, Ludhiana|141001|30.9010|75.8150|COMMERCIAL|20|150|2500|6|10|false|CCTV|6
north|rajasthan|jaipur|MI Road Commercial Parking|Mirza Ismail Road, Jaipur|302001|26.9170|75.8070|COMMERCIAL|25|150|2500|6|10|false|CCTV;WELL_LIT|1
north|rajasthan|udaipur|City Palace Parking|Near City Palace Gate, Udaipur|313001|24.5770|73.6830|COMMERCIAL|30|180|3000|4|10|false|SECURITY_GUARD|2
northeast|sikkim|gangtok|MG Marg Parking|Near MG Marg, Gangtok|737101|27.3290|88.6120|COMMERCIAL|40|250|4000|2|6|false|COVERED;SECURITY_GUARD|3
south|tamil-nadu|chennai|Guindy Metro Parking|Near Guindy Metro Station, Chennai|600032|13.0094|80.2133|METRO|30|200|3500|8|12|true|COVERED;CCTV;SECURITY_GUARD|4
south|tamil-nadu|chennai|T Nagar Shopping Parking|Usman Road, T Nagar, Chennai|600017|13.0418|80.2341|COMMERCIAL|40|250|4500|6|12|false|CCTV;WELL_LIT|5
south|tamil-nadu|coimbatore|Gandhipuram Bus Stand Parking|Gandhipuram, Coimbatore|641012|11.0168|76.9675|METRO|20|120|2200|6|10|true|CCTV|6
south|telangana|hyderabad|HITEC City Office Parking|Cyber Towers, HITEC City, Hyderabad|500081|17.4504|78.3810|OFFICE|40|250|4500|12|10|false|COVERED;CCTV;EV_CHARGING;SECURITY_GUARD|1
south|telangana|hyderabad|Ameerpet Metro Parking|Near Ameerpet Metro Station, Hyderabad|500016|17.4375|78.4482|METRO|30|200|3500|8|12|true|CCTV;SECURITY_GUARD|2
northeast|tripura|agartala|Post Office Chowmuhani Parking|Post Office Chowmuhani, Agartala|799001|23.8340|91.2790|COMMERCIAL|15|100|1800|2|6|false|WELL_LIT|3
north|uttar-pradesh|lucknow|Hazratganj Market Parking|Hazratganj, Lucknow|226001|26.8500|80.9460|COMMERCIAL|25|150|2500|6|12|false|CCTV;WELL_LIT|4
north|uttar-pradesh|noida|Sector 18 Metro Parking|Near Noida Sector 18 Metro Station, Noida|201301|28.5707|77.3261|METRO|40|250|4500|10|12|true|COVERED;CCTV;SECURITY_GUARD|5
north|uttar-pradesh|varanasi|Godowlia Ghat Road Parking|Godowlia, Varanasi|221001|25.3100|83.0090|COMMERCIAL|20|120|2000|4|10|false|SECURITY_GUARD|6
north|uttarakhand|dehradun|Rajpur Road Parking|Rajpur Road near Clock Tower, Dehradun|248001|30.3250|78.0430|COMMERCIAL|20|120|2200|4|8|false|CCTV|1
east|west-bengal|kolkata|Park Street Metro Parking|Near Park Street Metro Station, Kolkata|700016|22.5530|88.3520|METRO|40|250|4500|8|12|true|COVERED;CCTV;SECURITY_GUARD|2
east|west-bengal|kolkata|Salt Lake Sector V Office Parking|Sector V, Salt Lake, Kolkata|700091|22.5735|88.4331|OFFICE|30|200|3500|10|8|false|COVERED;CCTV;EV_CHARGING|3
south|andaman-and-nicobar-islands|sri-vijaya-puram|Aberdeen Bazaar Parking|Aberdeen Bazaar, Sri Vijaya Puram|744101|11.6670|92.7420|COMMERCIAL|20|120|2000|2|6|false|WELL_LIT|4
north|chandigarh|chandigarh|Sector 17 Plaza Parking|Sector 17 Plaza, Chandigarh|160017|30.7398|76.7827|COMMERCIAL|20|120|2200|8|12|false|CCTV;WELL_LIT|5
west|dadra-and-nagar-haveli-and-daman-and-diu|daman|Moti Daman Fort Road Parking|Fort Road, Moti Daman, Daman|396220|20.4150|72.8330|COMMERCIAL|15|100|1800|2|6|false|WELL_LIT|6
north|delhi|new-delhi|Rajiv Chowk Metro Parking|Connaught Place near Rajiv Chowk Metro, New Delhi|110001|28.6328|77.2197|METRO|60|400|7000|10|12|true|COVERED;CCTV;SECURITY_GUARD;WELL_LIT|1
north|delhi|new-delhi|Saket Mall Parking|Press Enclave Marg near Select Citywalk, Saket, New Delhi|110017|28.5286|77.2190|COMMERCIAL|50|300|5500|8|12|false|COVERED;CCTV;EV_CHARGING|2
north|jammu-and-kashmir|srinagar|Lal Chowk Parking|Residency Road near Lal Chowk, Srinagar|190001|34.0700|74.8090|COMMERCIAL|20|120|2200|4|8|false|SECURITY_GUARD|3
north|jammu-and-kashmir|jammu|Raghunath Bazaar Parking|Raghunath Bazaar, Jammu|180001|32.7300|74.8640|COMMERCIAL|20|120|2200|4|8|false|CCTV|4
north|ladakh|leh|Leh Main Bazaar Parking|Main Bazaar, Leh|194101|34.1650|77.5850|COMMERCIAL|30|180|3000|2|6|false|WELL_LIT|5
south|lakshadweep|kavaratti|Kavaratti Jetty Parking|Near Kavaratti Jetty, Kavaratti|682555|10.5630|72.6370|COMMERCIAL|10|60|1200|4|2|false|WELL_LIT|6
south|puducherry|puducherry|Promenade Beach Parking|Goubert Avenue near Promenade, Puducherry|605001|11.9340|79.8350|COMMERCIAL|25|150|2500|4|10|false|CCTV;WELL_LIT|1
```

(55 rows covering all 36 states/UTs. The seeder resolves cities with `CityRepository.findBySlugs(stateSlug, citySlug)` and must throw `IllegalStateException("Unknown city <state>/<city> in demo-listings.psv")` if one is missing.)

- [ ] **Step 2: Failing test** — `DemoListingSeederTest` (`@IntegrationTest`): construct `DemoAccountSeeder` and `DemoListingSeeder` directly (as the existing seeder test does), call `accountSeeder.seed(); listingSeeder.seed(); listingSeeder.seed();`, then `em.flush(); em.clear();`. Assert:
  1. `listings.countByStatus(APPROVED) == 55`; `listings.countByStatus(PENDING_REVIEW) == 1`.
  2. every one of the 36 states has ≥ 1 APPROVED listing (JPQL or iterate `findAll()` and collect `city.state.code` into a set — size 36).
  3. owner `owner.pending@parkease.dev` profile PENDING with non-null `documentKey`; `owner.north@parkease.dev` profile VERIFIED.
  4. "MG Road Metro Parking": `open24x7` true, 8 two-wheeler + 12 four-wheeler active slots (labels `B-01..B-08`, `A-01..A-12`), 2 photos, amenities size 4, price 40.00.
  5. "Sitabuldi Main Road Parking": not 24×7 → 7 hours rules, Sunday (7) 09:00–21:00.
  6. Running `seed()` a third time does not change the counts (idempotent).
- [ ] **Step 3: Run** → FAIL. **Step 4: Implement** the seeder (parse with `String.split("\\|", -1)`; trim fields; `BigDecimal` prices; amenities split on `;`). Add `@Order(1)` to `DemoAccountSeeder`.
- [ ] **Step 5: Seed images** — create `frontend/public/seed/parking-1.svg` … `parking-6.svg`: 1200×800 `viewBox`, each a flat illustration of a parking area (sky gradient background with a distinct hue per file — 1 emerald, 2 sky-blue, 3 amber, 4 violet, 5 rose, 6 teal; a dark asphalt band at the bottom with white parking-bay lines; 2–3 simple rounded-rectangle cars; a blue square "P" sign on a pole). Plain SVG shapes only, no external fonts (use `font-family="Arial, sans-serif"` for the "P"), each file < 6 KB.
- [ ] **Step 6: Full suite** pass. Then boot once (`./mvnw -q spring-boot:run` in background), check `curl -s localhost:8080/api/v1/health`, stop it, confirm nothing listens on 8080.
- [ ] **Step 7: Commit** `feat: demo owners, approved listings in every state and review-queue examples`

---

## Frontend tasks

Frontend conventions for Tasks 11–17:
- Data access lives in `src/lib/owner.ts` / `src/lib/admin.ts`: typed API functions + TanStack Query hooks. Query keys: `['owner','profile']`, `['owner','listings',page]`, `['owner','listing',id]`, `['owner','hours',id]`, `['owner','blocks',id]`, `['admin','queues']`, `['admin','owners',status,page]`, `['admin','listings',status,page]`, `['admin','listing',id]`. After a mutation, invalidate the affected keys.
- Every form: React Hook Form + Zod; server `fieldErrors` mapped onto known fields; other errors via `FormError` or `toast.error(errorMessage(e))`.
- Tests mock HTTP with `axios-mock-adapter` on `api` and render with `renderApp(path)` after `tokenStore.set('a','r')` and mocking `GET /me` with the right role. Map components are mocked with `vi.mock('../../components/owner/LocationPicker', ...)` (path relative to the test) to a stub with two number inputs labelled `Latitude`/`Longitude` and a button "Place pin here" that calls `onChange({lat: 18.5204, lng: 73.8567})`.
- All visible text below in quotes is exact (tests look for it).

### Task 11: Frontend foundations — dependencies, data layer, shared UI

**Files:** `frontend/package.json`; `src/lib/owner.ts`, `admin.ts`, `uploads.ts`, `format.ts`; `src/components/ui/Select.tsx`, `TextArea.tsx`, `StatusBadge.tsx`, `Dialog.tsx`, `Tabs.tsx`; tests `src/lib/format.test.ts`, `src/lib/uploads.test.ts`, `src/components/ui/shared.test.tsx`.

**Interfaces produced:**
- `npm install leaflet react-leaflet` and `npm install -D @types/leaflet` (use the versions npm resolves; react-leaflet must support React 19).
- `format.ts`: `formatINR(value: number | string | null): string` → `"₹30"` for 30, `"₹1,250.50"` for 1250.5, `"—"` for null (use `Intl.NumberFormat('en-IN', { style:'currency', currency:'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 })`); `formatDateTime(iso: string): string` → `Intl.DateTimeFormat('en-IN', { dateStyle:'medium', timeStyle:'short' })`; `listingStatusLabel(s)` → `DRAFT:"Draft", PENDING_REVIEW:"Pending review", APPROVED:"Live", REJECTED:"Changes needed", PAUSED:"Paused", SUSPENDED:"Suspended"`; `verificationStatusLabel(s)` → `UNSUBMITTED:"Not submitted", PENDING:"Under review", VERIFIED:"Verified", REJECTED:"Rejected"`; `DAY_NAMES = ['Monday',…,'Sunday']` (index 0 = dayOfWeek 1); `AMENITY_LABELS` → `COVERED:"Covered", CCTV:"CCTV", EV_CHARGING:"EV charging", SECURITY_GUARD:"Security guard", WHEELCHAIR_ACCESS:"Wheelchair access", WELL_LIT:"Well lit"`; `LISTING_TYPE_LABELS` → `METRO:"Metro / transit hub", OFFICE:"Office complex", COMMERCIAL:"Market / commercial", RESIDENTIAL:"Residential", EVENT:"Event venue"`; `DOCUMENT_TYPE_LABELS` → `AADHAAR:"Aadhaar card", PAN:"PAN card", DRIVING_LICENCE:"Driving licence", PASSPORT:"Passport", VOTER_ID:"Voter ID", PROPERTY_DOCUMENT:"Property document", UTILITY_BILL:"Utility bill"`.
- `uploads.ts`: `uploadFile<T>(url: string, file: File, fields?: Record<string,string>, onProgress?: (pct: number) => void): Promise<T>` → `api.post(url, FormData{file, ...fields}, { onUploadProgress })` returning `data`. Client-side pre-checks before sending: `file.size > 5*1024*1024` → throws `Error("Files must be 5 MB or smaller")`.
- `owner.ts`: TS types mirroring the DTOs in "Shared contracts" (`OwnerProfile`, `ListingSummary`, `ListingDetail`, `Photo`, `Slot`, `HoursRule`, `Hours {open24x7, rules}`, `Block`, `Page<T>`, enum unions) and functions: `getOwnerProfile`, `savePayout`, `submitDocument(type, file, onProgress)`, `getDocumentUrl`, `listMyListings(page)`, `getListing(id)`, `createListing(body)`, `updateBasics(id, body)`, `deleteListing(id)`, `savePricing(id, body)`, `uploadPhoto(id, file, onProgress)`, `deletePhoto(id, photoId)`, `reorderPhotos(id, ids)`, `listSlots(id)`, `addSlot(id, body)`, `addSlotsBulk(id, body)`, `updateSlot(id, slotId, body)`, `deleteSlot(id, slotId)`, `getHours(id)`, `saveHours(id, body)`, `listBlocks(id)`, `addBlock(id, body)`, `deleteBlock(id, blockId)`, `submitListing(id)`, `pauseListing(id)`, `resumeListing(id)`; hooks `useOwnerProfile()`, `useMyListings(page)`, `useListing(id)`, `useHours(id)`, `useBlocks(id)`.
- `admin.ts`: types `AdminOwner`, `AdminListingSummary`, `AdminListingDetail`, `QueueCounts`; functions `getQueues`, `listOwners(status, page)`, `getOwnerDocumentUrl(userId)`, `verifyOwner(userId)`, `rejectOwner(userId, reason)`, `listListings(status, page)`, `getAdminListing(id)`, `approveListing(id)`, `rejectListing(id, reason)`; hooks `useQueues()`, `useAdminOwners(status, page)`, `useAdminListings(status, page)`, `useAdminListing(id)`.
- UI: `Select` (same API/look as `TextField`: `label`, `error`, `hint`, children `<option>`s, ref-forwarding via props); `TextArea` (same pattern, `rows` default 4); `StatusBadge({ kind: 'listing' | 'verification', status })` → pill with label from `format.ts` (colours: Live/Verified emerald, Pending/Under review amber, Changes needed/Rejected red, Draft/Not submitted slate, Paused sky, Suspended red); `Dialog({ open, title, onClose, children })` → accessible modal (`role="dialog"`, `aria-modal`, labelled by title, Escape closes, focus moves into dialog), plus `ReasonDialog({ open, title, confirmLabel, onConfirm(reason), onClose })` with a `TextArea` labelled "Reason" (required, max 500 → error "Please give a reason"); `Tabs({ items: {to, label, end?}[] })` → horizontal `NavLink` sub-nav, scrollable on mobile.

- [ ] **Step 1: Tests.**
  `format.test.ts`: `formatINR(30)` → `"₹30"`; `formatINR(1250.5)` → `"₹1,250.5"` or `"₹1,250.50"` — assert with regex `/^₹1,250\.50?$/`; `formatINR(null)` → `"—"`; `listingStatusLabel('APPROVED')` → `"Live"`.
  `uploads.test.ts`: mock `POST /x` echoing `config.data instanceof FormData` and the `documentType` field → resolves with data; a 6 MB `File` rejects with "Files must be 5 MB or smaller" and makes no request (`mock.history.post.length === 0`).
  `shared.test.tsx`: `StatusBadge` renders "Pending review"; `ReasonDialog` — submitting empty shows "Please give a reason"; typing "Blurry" and clicking the confirm button calls `onConfirm("Blurry")`; pressing Escape calls `onClose`.
- [ ] **Step 2: Run** `npm test` → FAIL. **Step 3: Implement.** **Step 4:** `npm test && npm run build && npm run lint` pass.
- [ ] **Step 5: Commit** `feat(frontend): owner/admin data layer, formatting helpers and shared UI`

---

### Task 12: Owner area shell, owner home and verification page

**Files:** `src/pages/owner/OwnerLayout.tsx`, `OwnerHomePage.tsx`, `OwnerVerificationPage.tsx`; modify `src/App.tsx`, `src/pages/RoleHomePage.tsx` (driver/admin only for now — owner no longer uses it), `src/components/layout/Navbar.tsx` (owner "Dashboard" → `/owner`, unchanged route); tests `src/pages/owner/OwnerVerification.test.tsx`.

**Routes** (all inside `RequireRole roles={['OWNER']}` and `OwnerLayout`): `/owner` (home), `/owner/verification`, `/owner/listings` (Task 15), `/owner/listings/new` and `/owner/listings/:id/edit` (Tasks 13–15), `/owner/listings/:id/blocks` (Task 16). `OwnerLayout` = page container + heading "Owner dashboard" + `Tabs` [Overview `/owner` (end), Listings `/owner/listings`, Verification `/owner/verification`] + `<Outlet/>`.

**OwnerHomePage:** verification card showing `StatusBadge` + copy per status — UNSUBMITTED: "Verify your identity to start listing parking." button-link "Start verification" → `/owner/verification`; PENDING: "We're reviewing your document. This usually takes a day."; REJECTED: "Your verification was rejected: <reason>" + "Upload again"; VERIFIED: "You're verified. You can submit listings for approval." Listings card: total listings (from `useMyListings(0).totalElements`) and button-link "Add a listing" → `/owner/listings/new` and "Manage listings" → `/owner/listings`.

**OwnerVerificationPage:** two sections.
1. "Identity verification": `StatusBadge`; rejection reason (if any) in a red note; if VERIFIED show "Your identity is verified." and a "View document" button; otherwise a form: `Select` "Document type" (options from `DOCUMENT_TYPE_LABELS`), file input labelled "Document file" (`accept="image/jpeg,image/png,image/webp,application/pdf"`), hint "JPG, PNG, WebP or PDF, up to 5 MB", upload progress text "Uploading… <pct>%", submit button "Submit for verification". On success toast "Document submitted for review". "View document" (shown when a document exists) calls `getDocumentUrl()` and `window.open(url, '_blank', 'noopener')`.
2. "Payout details": form with "UPI ID", "Bank account number", "IFSC code" (uppercased on submit), "Account holder name"; hint "Add a UPI ID, or a bank account with IFSC."; client-side Zod mirrors the backend (UPI regex, 9–18 digits, IFSC regex, name required, and the either/or rule → form error "Add a UPI ID, or a bank account with IFSC."); submit "Save payout details" → toast "Payout details saved". Prefill UPI, IFSC and name from the profile; the bank-account field shows placeholder "•••• <last4>" when saved.

- [ ] **Step 1: Tests** — `OwnerVerification.test.tsx`:
  1. Owner with UNSUBMITTED profile: `/owner` shows "Not submitted" and link "Start verification".
  2. On `/owner/verification`: choose "PAN card", upload a `new File(['%PDF-1.4'], 'pan.pdf', {type:'application/pdf'})` via `userEvent.upload(screen.getByLabelText('Document file'), file)`, click "Submit for verification" → POST `/owner/verification` called with FormData containing `documentType` `PAN`; mock returns PENDING profile → page shows "Under review".
  3. Payout: type only account name and click "Save payout details" → shows "Add a UPI ID, or a bank account with IFSC." and makes no PUT; then type UPI "ravi@okaxis" → PUT `/owner/profile/payout` body has `upiId: 'ravi@okaxis'`.
  4. REJECTED profile with reason "Blurry photo" → page shows "Blurry photo".
- [ ] **Step 2–4:** run (fail) → implement → `npm test && npm run build && npm run lint`.
- [ ] **Step 5: Commit** `feat(frontend): owner dashboard shell, verification and payout pages`

---

### Task 13: Listing wizard shell and Step 1 (location with map)

**Files:** `src/components/owner/LocationPicker.tsx`; `src/pages/owner/ListingWizardPage.tsx`; `src/pages/owner/wizard/LocationStep.tsx`; routes in `App.tsx`; test `src/pages/owner/ListingWizard.test.tsx` (Step 1 cases; later tasks add cases).

**LocationPicker** (`{ value: {lat,lng} | null, center: {lat,lng}, onChange(pos) }`): React-Leaflet `MapContainer` (height 320 px, rounded, `scrollWheelZoom` false) with OSM tiles (`https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png`, attribution "© OpenStreetMap contributors"), a **draggable** marker using `L.divIcon` (an emerald pin built with HTML/CSS — avoids Leaflet's default image assets), click-on-map sets the pin, and the map re-centres when `center` changes. Import `leaflet/dist/leaflet.css` in this component. Below the map show "Lat <lat.toFixed(5)>, Lng <lng.toFixed(5)>" or "Click the map to drop a pin".

**ListingWizardPage:** route `/owner/listings/new` renders only Step 1 (no id yet). Route `/owner/listings/:id/edit?step=N` (N 1–6, default 1) loads `useListing(id)` and renders a stepper with labels exactly: "Location", "Photos", "Slots", "Pricing", "Hours", "Review"; steps are links (`?step=N`) — all clickable once the listing exists. Each step gets `{ listing, onSaved(next?: number) }`; `onSaved` invalidates `['owner','listing',id]` and navigates to `?step=<next>`. Header shows listing title + `StatusBadge`; if `listing.status === 'REJECTED'` show a red note "Changes requested: <rejectionReason>"; if `SUSPENDED` show "This listing was suspended by ParkEase and can't be edited." and disable step forms.

**LocationStep:** fields — `Select` "State" (from `useStates()`), `Select` "City" (from `useStateDetail(stateSlug)` once a state is chosen), "Listing title", `Select` "Parking type" (`LISTING_TYPE_LABELS`), "Address", "PIN code", `TextArea` "Description (optional)"; button "Find address on map" → Nominatim `GET https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=in&q=<address>, <city>, <state>` via `fetch` (not `api`); first result sets the pin, none → toast "Address not found — place the pin manually"; then `LocationPicker` centred on the city. Zod: title 1–120, address 1–300, pincode `/^[1-9][0-9]{5}$/` ("Enter a valid 6-digit PIN code"), city required ("Choose a city"), pin required ("Place the pin on the map"). Submit button "Save and continue": no id → `createListing` then `navigate('/owner/listings/<id>/edit?step=2', {replace:true})`; with id → `updateBasics` then `onSaved(2)`. Server `LOCATION_OUTSIDE_CITY` → `FormError` with its detail. When editing, prefill all fields (state from `listing.stateName` → match by name in `useStates()`).

- [ ] **Step 1: Tests** (`ListingWizard.test.tsx`, LocationPicker mocked as described): owner on `/owner/listings/new`; mock `/states` (Maharashtra only) and `/states/maharashtra` (Pune city id 51); fill State, City, title "FC Road Parking", type "Office complex", address, PIN "411004", click "Place pin here", click "Save and continue" → POST `/owner/listings` body `{cityId:51, title:'FC Road Parking', listingType:'OFFICE', pincode:'411004', lat:18.5204, lng:73.8567, ...}`; mock 201 `{id: 7, ...}` and `GET /owner/listings/7` → app navigates to the wizard and shows the stepper step "Photos" as current (`aria-current="step"`). Second case: server 400 `LOCATION_OUTSIDE_CITY` detail "The map pin must be within 60 km of Pune" is shown. Third: missing pin → "Place the pin on the map" and no POST.
- [ ] **Step 2–4:** fail → implement → `npm test && npm run build && npm run lint`.
- [ ] **Step 5: Commit** `feat(frontend): listing wizard with map-based location step`

---

### Task 14: Wizard Steps 2–3 — photos and slots

**Files:** `src/components/owner/PhotoManager.tsx`, `SlotManager.tsx`; `src/pages/owner/wizard/PhotosStep.tsx`, `SlotsStep.tsx`; tests added to `ListingWizard.test.tsx`.

**PhotosStep / PhotoManager:** grid of photos (first tile badge "Cover"); hidden multi-file input + button "Add photos" (`accept="image/jpeg,image/png,image/webp"`); files upload **sequentially** with per-file progress "Uploading <name>… <pct>%"; client limit: remaining capacity = 8 − current → extra files skipped with toast "A listing can have at most 8 photos"; each tile has buttons with aria-labels "Move photo <n> left", "Move photo <n> right", "Make photo <n> the cover", "Delete photo <n>" (n = 1-based position) and supports native HTML5 drag-and-drop between tiles; any reorder calls `reorderPhotos` with the full new id list. Empty state "Add at least one photo of the entrance and the parking area." Footer: "Back" → step 1, "Continue" → `onSaved(3)` (disabled with hint "Add at least one photo to continue" when 0 photos).

**SlotsStep / SlotManager:** table/list of slots (label, "Car"/"Two-wheeler", size, active toggle) with "Edit"/"Delete" per row (delete asks `Dialog` confirm "Delete slot <label>?"); form "Add one slot": "Slot label", `Select` "Vehicle type" ("Car" = FOUR_WHEELER, "Two-wheeler" = TWO_WHEELER), `Select` "Size" (Small/Medium/Large), button "Add slot"; form "Add many slots": "Label prefix" (default "A-"), "Start number" (default 1), "How many" (1–50), vehicle type, size, live preview text "Creates A-01 to A-10", button "Add slots". Summary line "<n> slots · <c> car · <t> two-wheeler". `SLOT_LABEL_TAKEN` / `SLOT_LIMIT` details shown as `FormError`. Footer "Back"/"Continue" (`onSaved(4)`, disabled until ≥1 active slot, hint "Add at least one slot to continue").

- [ ] **Step 1: Tests:** (a) on step 2 with 2 photos, clicking "Make photo 2 the cover" sends `PUT /owner/listings/7/photos/order` with `{photoIds:[b,a]}`; (b) uploading 1 file posts to `/owner/listings/7/photos`; (c) step 3 bulk form with prefix "B-", start 1, count 3 shows preview "Creates B-01 to B-03" and POSTs `/owner/listings/7/slots/bulk` with `{prefix:'B-',startNumber:1,count:3,vehicleType:'TWO_WHEELER',size:'SMALL'}`; (d) server 409 `SLOT_LABEL_TAKEN` detail is displayed.
- [ ] **Step 2–4:** fail → implement → checks pass.
- [ ] **Step 5: Commit** `feat(frontend): photo manager and slot manager wizard steps`

---

### Task 15: Wizard Steps 4–6 (pricing, hours, review/submit) and My listings

**Files:** `src/components/owner/HoursEditor.tsx`; `src/pages/owner/wizard/PricingStep.tsx`, `HoursStep.tsx`, `ReviewStep.tsx`; `src/pages/owner/MyListingsPage.tsx`; tests added to `ListingWizard.test.tsx` + `src/pages/owner/MyListings.test.tsx`.

**PricingStep:** "Price per hour (₹)" (required), "Price per day (₹, optional)", "Price per month (₹, optional)", radio group "Cancellation policy" with the three options and one-line explanations (FLEXIBLE "Full refund up to 1 hour before start", MODERATE "Full refund up to 24 hours before start, 50% after", STRICT "50% refund up to 48 hours before start"), checkbox "Approve bookings automatically", amenity checkboxes (labels from `AMENITY_LABELS`), `TextArea` "Parking rules (optional)". Client Zod mirrors backend ranges and the day ≥ hour / month ≥ day rules ("Daily price can't be lower than the hourly price", "Monthly price can't be lower than the daily price"). "Save and continue" → `savePricing` → `onSaved(5)`.

**HoursStep / HoursEditor:** toggle "Open 24 × 7"; otherwise 7 rows (Monday…Sunday) each with a checkbox "Open on <Day>" and two `type="time"` inputs labelled "<Day> opens" / "<Day> closes"; buttons "Copy Monday to all days" and preset "Weekdays 8 AM – 10 PM". Validation per row: closes > opens ("<Day>: closing time must be after opening time"). "Save and continue" → `saveHours({open24x7, rules})` (only checked days) → `onSaved(6)`. Prefill from `useHours(id)`.

**ReviewStep:** read-only summary cards (location + mini static text of lat/lng, photos thumbnails, slot summary, pricing with `formatINR`, hours summary "Open 24 × 7" or per-day list, amenities). Owner verification gate: if profile not VERIFIED, show note "Verify your identity before submitting" with link to `/owner/verification` and disable submit. Submit button: "Submit for approval" (DRAFT/REJECTED) → `submitListing` → toast "Listing submitted for approval" → navigate `/owner/listings`. Error `LISTING_INCOMPLETE` → list each missing part with a link to its step: PHOTOS "Add photos" (step 2), SLOTS "Add slots" (step 3), PRICING "Set pricing" (step 4), AVAILABILITY "Set opening hours" (step 5). For PENDING_REVIEW show "Waiting for approval"; APPROVED shows "This listing is live." and "Pause listing"; PAUSED shows "Resume listing".

**MyListingsPage** (`/owner/listings`): header "My listings" + button-link "Add a listing"; cards (cover image or placeholder, title, "<city>, <state>", `StatusBadge`, `formatINR(pricePerHour)` + "/hr", "<n> slots", rejection note if REJECTED) with actions: "Edit" → wizard step 1, "Blocked times" → `/owner/listings/:id/blocks`, "Pause"/"Resume" (when APPROVED/PAUSED), "Delete" (when deletable; confirm `Dialog` "Delete <title>? This can't be undone."). Pagination "Previous"/"Next" when `totalPages > 1`. Empty state: "You haven't added any parking yet." + "Add your first listing".

- [ ] **Step 1: Tests:**
  - Wizard step 4: entering hour 50 / day 40 shows "Daily price can't be lower than the hourly price" and no PUT; valid values PUT `/owner/listings/7/pricing` with `pricePerHour: 50`, `autoApprove: true`, `amenities` containing `CCTV` when its box is ticked.
  - Wizard step 5: untick 24×7, tick "Open on Monday", set 09:00–18:00, save → PUT `/owner/listings/7/hours` body `{open24x7:false, rules:[{dayOfWeek:1, openTime:'09:00', closeTime:'18:00'}]}`.
  - Wizard step 6: verified owner, submit → server 400 `LISTING_INCOMPLETE` `missing:['PHOTOS','AVAILABILITY']` → page shows links "Add photos" and "Set opening hours"; success path posts `/owner/listings/7/submit` and lands on "My listings".
  - `MyListings.test.tsx`: renders two listings with "Live" and "Changes needed" badges; "Pause" on the live one posts `/owner/listings/<id>/pause`; empty page shows "You haven't added any parking yet."
- [ ] **Step 2–4:** fail → implement → checks pass.
- [ ] **Step 5: Commit** `feat(frontend): pricing, hours and review steps plus my listings page`

---

### Task 16: Blocked times page

**Files:** `src/pages/owner/ListingBlocksPage.tsx`; route `/owner/listings/:id/blocks`; test `src/pages/owner/ListingBlocks.test.tsx`.

**Behaviour:** heading "Blocked times — <title>"; back link "← My listings"; form: `Select` "Applies to" ("Whole listing" (value "") or each slot label), `datetime-local` inputs "From" and "Until", `TextField` "Reason (optional)", button "Block time". Convert local datetime strings to ISO with `new Date(value).toISOString()`. Client checks: until > from ("'Until' must be after 'From'"). List of upcoming blocks (sorted) showing "<formatDateTime(start)> → <formatDateTime(end)>", slot label or "Whole listing", reason, and a "Remove" button (`aria-label="Remove block <n>"`). Empty: "No blocked times. Your listing follows its weekly hours." Server `INVALID_BLOCK` detail shown via `FormError`.

- [ ] **Step 1: Tests:** posting a block sends `{slotId:null, startTime:<iso>, endTime:<iso>, reason:'Maintenance'}`; until-before-from shows the message and no POST; "Remove block 1" sends DELETE `/owner/listings/7/blocks/<id>`.
- [ ] **Step 2–4:** fail → implement → checks pass.
- [ ] **Step 5: Commit** `feat(frontend): blocked times management for listings`

---

### Task 17: Admin review area

**Files:** `src/pages/admin/AdminLayout.tsx`, `AdminHomePage.tsx`, `OwnerQueuePage.tsx`, `ListingQueuePage.tsx`, `AdminListingReviewPage.tsx`; routes in `App.tsx` (`/admin`, `/admin/owners`, `/admin/listings`, `/admin/listings/:id`, all `RequireRole roles={['ADMIN']}` inside `AdminLayout`); test `src/pages/admin/AdminReview.test.tsx`.

**AdminLayout:** heading "Admin" + `Tabs` [Overview `/admin` (end), Owner verification `/admin/owners`, Listing approvals `/admin/listings`].
**AdminHomePage:** two cards from `useQueues()`: "<n> owners waiting for verification" → link "Review owners"; "<n> listings waiting for approval" → link "Review listings". Note "More admin tools (users, bookings, reports) arrive in a later phase."
**OwnerQueuePage:** status filter `Select` "Show" (Pending / Verified / Rejected → PENDING/VERIFIED/REJECTED; default Pending); rows: name, email, phone, document type label, submitted `formatDateTime`, payout "Payout details added"/"No payout details", listing count; actions "View document" (calls `getOwnerDocumentUrl` then `window.open(url,'_blank','noopener')`), "Verify" (PENDING only; toast "<name> verified"), "Reject" (opens `ReasonDialog` titled "Reject <name>?" confirm "Reject"; toast "<name> rejected"). Empty: "No owners in this list." Pagination as in My listings.
**ListingQueuePage:** filter "Show" (Pending review / Live / Changes needed → PENDING_REVIEW/APPROVED/REJECTED); rows: cover, title, city/state, owner name + email, submitted time, link "Review" → `/admin/listings/:id`. Empty: "No listings in this list."
**AdminListingReviewPage:** full read-only detail (photos gallery, map preview using `LocationPicker` in read-only mode — add prop `readOnly` that disables dragging/clicking, address, type, slots summary, pricing, hours, amenities, rules, owner card with verification badge); actions when PENDING_REVIEW: "Approve" (toast "Listing approved", navigate `/admin/listings`) and "Reject" (`ReasonDialog` "Reject this listing?", toast "Listing rejected", navigate back).

- [ ] **Step 1: Tests** (admin user mocked on `/me`, `LocationPicker` mocked): home shows "2 owners waiting for verification"; owner queue "Verify" posts `/admin/owners/5/verify` and shows toast text (assert the POST); "Reject" → dialog → reason "Blurry" → posts `/admin/owners/5/reject` with `{reason:'Blurry'}`; listing review "Approve" posts `/admin/listings/9/approve`; a driver visiting `/admin` is redirected to `/driver`.
- [ ] **Step 2–4:** fail → implement → checks pass.
- [ ] **Step 5: Commit** `feat(frontend): admin queues for owner verification and listing approval`

---

### Task 18: Docs and end-to-end verification

**Files:** `README.md`; `backend/.env.example` (add commented `# CLOUDINARY_URL=cloudinary://<api_key>:<api_secret>@<cloud_name>`, `# PUBLIC_BASE_URL=http://localhost:8080`, `# STORAGE_LOCAL_DIR=uploads`); root `.gitignore` already ignores `backend/uploads/`.

- [ ] **Step 1: README updates:** Status table — Phase 2 ✅ (scope: owner verification, listings wizard, slots, hours, blocked times, photo/document uploads, admin approval queues, demo listings in every state); "Uploads" section (local `backend/uploads/` by default; set `CLOUDINARY_URL` to use Cloudinary; documents are private and opened via 5-minute signed links); demo accounts table adds `owner.north@`, `owner.south@`, `owner.east@`, `owner.northeast@`, `owner.central@`, `owner.pending@parkease.dev` (same password) and notes the pending owner/listing appear in the admin queues.
- [ ] **Step 2: Full checks:** backend `./mvnw test` (all pass), frontend `npm test && npm run build && npm run lint`.
- [ ] **Step 3: Commit** `docs: Phase 2 README and environment template`
- [ ] **Step 4 (controller): manual browser check** — owner verification upload; full wizard on a new listing incl. map pin, 2 photos + reorder, bulk slots, pricing, hours, review → submit; admin verifies the pending owner (view document opens), approves the submitted listing; owner sees "Live", pauses/resumes; blocked time add/remove; mobile 375 px; dark mode.
