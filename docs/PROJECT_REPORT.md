# ParkEase — Smart Parking Slot Rental & Availability Platform

**Project report**

Project: Unified Mentor, "Smart Parking Slot Rental & Availability Platform" (project id 11778)
Repository: https://github.com/xreep/Parkease
Author: [your name, programme and submission date]

## 1. Problem statement

Parking is scarce near metro stations, office complexes and markets in Indian cities, while many private spaces (residential compounds, office basements, shop forecourts) stand empty for most of the day. Drivers circle for a spot or pay for illegal parking; owners earn nothing from space they cannot easily rent out. There is no trusted, pay-in-advance way for the two sides to find each other, agree a price, and be protected when something goes wrong.

## 2. Objectives

1. Let owners list unused parking slots with pricing, opening hours and rules, and earn from them.
2. Let drivers find parking near a destination for an exact time window, see the real price, and reserve it with online payment.
3. Make double booking impossible, and handle approvals, cancellations and refunds fairly and automatically.
4. Give an administrator the tools to verify owners and listings, monitor activity, resolve disputes, record payouts and read reports.
5. Deliver it as a secure, responsive web application that is tested, documented and deployable on free hosting.

## 3. Scope

**In scope.** Three roles (driver, owner, admin); all 28 states and 8 union territories with more than 100 cities; listing creation with verification and approval; map search with filters; availability calendar and live quotes; booking with a payment hold; Razorpay test-mode payments, refunds, webhooks and PDF receipts; booking lifecycle jobs; in-app and email notifications; reviews and replies; disputes; owner earnings and admin payouts (recorded, not transferred); KPI dashboards, reports and CSV exports; admin settings and audit log; demo data; CI and deployment configuration.

**Out of scope.** Native mobile apps, IoT sensors, gate automation, real payout money movement, live navigation, dynamic pricing, transit integrations, multi-language UI, multi-instance scaling (Redis).

## 4. Proposed solution

ParkEase is a web marketplace. Owners register, upload a verification document and, once an admin verifies them, build listings in a six-step wizard. Admins approve each listing. Drivers search by place, time and vehicle, open a listing, and reserve a slot. The slot is held for ten minutes while the driver pays; payment confirmation turns the hold into a confirmed booking (or a request to the owner, for listings that need approval). Background jobs move bookings through their lifecycle, expire stale holds and send reminders. Every money movement (payment, refund, owner earning, payout) is recorded and auditable.

The platform earns a 10% fee on the parking charge plus 18% GST on that fee; the owner receives the full parking charge. Fee, GST and timing values are admin-editable and apply to new bookings.

## 5. System architecture

A React single-page app (Vite, TypeScript, Tailwind) talks over JSON/HTTPS to a Spring Boot 4.1 (Java 21) REST API, which owns a PostgreSQL 16 database migrated by Flyway. External services sit behind interfaces with local fallbacks: Razorpay or a mock payment provider, Cloudinary or local disk, SMTP or console email. The backend is a modular monolith organised by feature (auth, listing, availability, search, pricing, booking, payment, earning, notification, review, dispute, admin and others). Deployment uses Render (Docker), Neon (PostgreSQL), Vercel (frontend) and Cloudinary. See [ARCHITECTURE.md](ARCHITECTURE.md) for diagrams and flows.

## 6. Modules

| Module | What it does |
|---|---|
| Accounts | Registration, login, refresh-token rotation, email verification, password reset, profile, suspension |
| Locations | All-India states and cities with coordinates and price tiers; admin management |
| Owner onboarding | Document upload, admin verification, payout details |
| Listings | Wizard, photos, slots, opening hours, blocked times, pricing, cancellation policy, review, pause and resume |
| Search and availability | Radius search with filters and sorting, map with clustering, evaluation of opening hours, blocks and live bookings, 90-day availability calendar |
| Pricing | Cheapest of hourly, daily, monthly and mixed pricing; fee and GST; advisory price guidelines per city tier |
| Booking | Slot allocation, 10-minute hold, owner approval, cancellation with policy refunds, lifecycle (active, completed), QR code |
| Payments | Order creation, signature-checked verification, webhooks, refunds, reconciliation, PDF receipts |
| Earnings and payouts | Owner ledger (held, pending payout, paid, reversed); admin marks payouts with a reference |
| Notifications | In-app bell and email for every important event, reminders |
| Reviews | One review per completed booking, owner reply, rating aggregates, admin moderation |
| Disputes | Driver reports, owner response, admin resolution with optional refund |
| Dashboards | Owner overview, earnings and calendar; driver stats; admin KPIs and reports |
| Administration | Queues, users, listings, reviews, locations, bookings monitor, payments, payouts, settings, audit log |

## 7. Technology

Backend: Java 21, Spring Boot 4.1, Spring Security with JJWT, Spring Data JPA (Hibernate), Flyway, Bean Validation, Bucket4j, Caffeine, Spring Mail with Thymeleaf, OpenPDF, springdoc-openapi, Razorpay and Cloudinary SDKs, Maven. Database: PostgreSQL 16 with the `btree_gist` extension. Frontend: React 19, Vite, TypeScript, Tailwind CSS v4, React Router, TanStack Query, React Hook Form with Zod, Axios, React-Leaflet, in-house SVG charts. Tooling: JUnit 5, Spring Boot Test and MockMvc, Vitest, React Testing Library, axe-core, oxlint, GitHub Actions, Dependabot, Docker.

## 8. Key design decisions

- **Database guarantees double-booking safety.** An exclusion constraint on slot and time range for live statuses means no application bug or race can create overlapping bookings; the allocator retries another slot on a conflict.
- **One lock order for money.** Payment, then booking, then earning, everywhere, with a status re-check after locking. Payment confirmation, webhooks, cancellations, refunds, disputes, payouts and jobs therefore serialise instead of deadlocking, and every operation is idempotent.
- **Server-side money.** Prices are computed and stored by the server; payments are accepted only for the right order, amount and currency and only when captured at the provider; refunds use idempotency keys.
- **Providers behind interfaces.** Mock payments, local files and console email let the whole product run and be tested without accounts, and the production profile refuses to start without real payment keys.
- **No PostGIS.** Search uses a bounding-box prefilter on indexed coordinates and exact Haversine distance, which behaves the same on local PostgreSQL and Neon.
- **Refund fairness.** Refunds follow the listing's published policy on the parking charge; fee and GST come back only when the owner, system or an admin causes the cancellation. The refund is shown before the driver confirms.
- **Settings in the database.** Fee, GST and timing are editable by admins and snapshotted on each booking, so history never changes.
- **Frontend resilience.** Route-level code splitting (main bundle about 300 kB), an error boundary, recovery from stale chunks after a deploy, per-page titles, light and dark themes, responsive down to 375 px, contrast and axe checks.
- **Single instance.** Rate limiting, a small user-status cache and scheduled jobs live in memory, which suits the free hosting target and keeps the system simple.

## 9. Testing

The project was built test first, phase by phase: each task started with failing tests, then the implementation, then the full suites, and a separate review of every batch of work fixed defects before it was accepted (many review rounds produced concurrency, money and accessibility fixes that are covered by regression tests).

- **Backend (903 tests).** Unit tests for pricing, refund policies, availability evaluation, signature verification and the cancellation calculator. Integration tests (`@SpringBootTest` with MockMvc against a real PostgreSQL) for registration and authentication, owner verification, the listing lifecycle, search, availability, booking creation, the double-booking race, payment verification and webhook idempotency, late and duplicate payments, cancellations and refunds, owner decisions, scheduled transitions, reviews, disputes, admin actions, payouts and reports, authorisation for every role, rate limits, security headers, the production configuration guard and demo seeding. A real database is used because H2 cannot model the exclusion constraint.
- **Frontend (854 tests).** Vitest and React Testing Library with mocked HTTP for every page and flow: search, listing and booking card, checkout, booking detail and cancellation, owner wizard, dashboards, calendar, admin screens, route guards, token refresh, error boundary and lazy loading, page titles, and axe-core accessibility checks of the home, search, listing, login, booking, owner and admin pages; contrast ratios of the design tokens are asserted.
- **Continuous integration.** Every push and pull request runs the backend suite on PostgreSQL 16, then frontend lint, type check, tests and build, and a non-blocking dependency audit.
- **Manual checks.** Browser runs of key pages in light and dark themes, a Content-Security-Policy check of the production build, and a Razorpay test-mode payment as the end-to-end acceptance step (to be recorded with the deployed demo).

## 10. Results

All eight planned phases are implemented: foundation and accounts, listings and approvals, search and map, booking and payments, lifecycle and notifications, dashboards and reviews, admin panel with disputes and payouts, and the launch phase (realistic demo data, security hardening, deployment configuration, CI, frontend polish and documentation). The system covers every role in the brief and adds real test-mode payments, refunds, notifications, reviews and all-India data. A seeded demo shows the platform with about 100 listings, 30 owners, 50 drivers and 500 bookings in every status, and the production configuration is ready for free-tier hosting.

Measured against the objectives: owners can list and earn; drivers can find, price, book, pay, cancel and review; overlapping bookings are rejected by the database; admins can verify, moderate, resolve, record payouts and export reports; and the application is covered by automated tests and a CI pipeline.

## 11. Limitations

- **Single instance.** In-memory rate limits, status cache and job scheduler do not coordinate across instances.
- **Free-tier behaviour.** The Render free service sleeps when idle, so the first request can take up to a minute; Neon also suspends idle compute; local file storage would be lost on restart, hence Cloudinary.
- **Payouts are bookkeeping.** ParkEase records transfer references; no money is moved to owners.
- **Test-mode payments only,** and webhooks need the deployed public URL.
- **Geocoding** uses the public Nominatim service on explicit searches only (its usage policy), so place lookup is slower and less forgiving than a commercial service.
- **Indicative availability.** The calendar is a guide; the quote at booking time is authoritative.
- **Web only,** English only, no push notifications or SMS.

## 12. Future scope

- **Redis and multi-instance operation:** shared rate limiting and caches, a distributed job lock, horizontal scaling.
- **Real payouts** with Razorpay Route (or similar), automated settlement and reconciliation reports.
- **Native mobile apps** (or a progressive web app) with push notifications and offline QR codes.
- **Dynamic pricing** driven by demand, events and time of day, building on the existing price guidelines.
- **IoT and access integration:** occupancy sensors, boom barriers and licence-plate recognition, so the QR code is replaced by automatic entry.
- Subscriptions and monthly passes, transit and navigation integrations, multi-language UI, and SMS or WhatsApp notifications.
