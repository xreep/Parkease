# ParkEase — Smart Parking Slot Rental & Availability Platform

A web platform where private parking owners rent out unused slots and drivers find, reserve and pay for parking in advance — near metro stations, office complexes and commercial hubs across India.

- **Backend:** Spring Boot 4.1 (Java 21), PostgreSQL 16, Flyway, Spring Security + JWT
- **Frontend:** React + Vite + TypeScript, Tailwind CSS v4, TanStack Query
- **Docs:** [Design spec](docs/superpowers/specs/2026-10-04-smart-parking-design.md) · API docs at `http://localhost:8080/swagger-ui` when running

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Scaffold, auth (register/login/refresh/verify/reset), profile, all-India states & cities | ✅ |
| 2 | Owner verification, listings wizard, slots, opening hours, blocked times, photo/document uploads, admin approval queues, demo listings in every state | ✅ |
| 3 | Parking search with map and filters, availability + live price quotes, public listing pages, city pages | ✅ |
| 4 | Booking, pricing, Razorpay payments, invoices | ✅ |
| 5 | Booking lifecycle (active → completed), cancellations with policy refunds, in-app notifications, reminders | ✅ |
| 6 | Owner dashboard, earnings, reviews, driver payments and stats | ✅ |
| 7 | Admin panel, reports, disputes, payouts | ✅ |
| 8 | Full seed data, polish, deployment | ⏳ |

## Prerequisites (macOS)

```bash
brew install openjdk@21 postgresql@16 node
brew services start postgresql@16
createdb smartparking_dev
createdb smartparking_test
```

Add to `~/.zshrc`:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)"
export PATH="$JAVA_HOME/bin:$(brew --prefix postgresql@16)/bin:$PATH"
```

## Run locally

Backend (port 8080, `dev` profile, creates demo accounts):

```bash
cd backend
./mvnw spring-boot:run
```

Frontend (port 5173, proxies `/api` to the backend):

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### Demo accounts (dev profile)

| Role | Email | Password |
|---|---|---|
| Admin | admin@parkease.dev | Demo@1234 |
| Owner (verified) | owner@parkease.dev | Demo@1234 |
| Owner (verified) | owner.north@parkease.dev | Demo@1234 |
| Owner (verified) | owner.south@parkease.dev | Demo@1234 |
| Owner (verified) | owner.east@parkease.dev | Demo@1234 |
| Owner (verified) | owner.northeast@parkease.dev | Demo@1234 |
| Owner (verified) | owner.central@parkease.dev | Demo@1234 |
| Owner (pending verification) | owner.pending@parkease.dev | Demo@1234 |
| Driver | driver@parkease.dev | Demo@1234 |

The dev profile also seeds 55 approved demo listings spread across all 36 states and union territories. The pending owner (`owner.pending@`) and one pending listing, "Viman Nagar Residency Parking", show up in the admin queues (**Admin → Owner verification** and **Listing approvals**), so you can try the review flow straight away.

Change the password with the `DEMO_PASSWORD` environment variable (applies only when the demo accounts are first created).

### Try it: find parking

With both servers running, open http://localhost:5173 and search for a place, for example **"Andheri Metro"** (or pick a city such as Pune from the "Popular" chips). Choose the time and vehicle, then:

- filter by distance, price, listing type, amenities or 24 × 7, and sort by distance, price or rating; results show on the list and on a clustered map;
- open a listing to see photos, opening hours, rules and the cancellation policy, and a live price quote (parking + platform fee + GST) for your times;
- browse a state, pick a city (`/in/<state>/<city>`) to see the parking listed around it.

Reserve signs you in. To book, add a vehicle under **My parking → Vehicles** (or while reserving), pick your times on a listing and press Reserve: the slot is held for 10 minutes while you pay. Afterwards:

- **Drivers** get a booking page with a QR code to show at the entrance (`/driver/bookings`), a downloadable PDF receipt, and a status timeline. Listings that need approval show "Waiting for owner" until the owner responds; if they don't, the driver is refunded in full.
- **Owners** see requests under **Owner dashboard → Bookings**, where they can approve or decline (with a reason), and see upcoming and past bookings with what they earn.

### Try it: cancellations, lifecycle and notifications

- **Lifecycle:** a confirmed booking becomes **Active** when its start time arrives and **Completed** when it ends (background jobs, on a one-minute cycle). Drivers get a "starting soon" reminder when their parking is within the hour of starting, and owners are warned when a request is within 30 minutes of lapsing.
- **Driver cancellations:** open a booking and press **Cancel booking**. The dialog shows what you get back before you confirm. An unpaid booking costs nothing, and a request the owner has not accepted yet is refunded in full. A confirmed booking follows the listing's cancellation policy (below); when a driver cancels a confirmed booking, the platform fee and GST are not refunded. Bookings that have started cannot be cancelled.
- **Owner cancellations:** under **Owner dashboard → Bookings → Upcoming**, an owner can cancel a confirmed booking that has not started. A reason is required (it is sent to the driver) and the driver is refunded in full.
- **Notifications:** the bell in the navigation bar shows unread notifications for every signed-in user (drivers, owners and admins); open it for the latest ten, or go to `/notifications` for the full list. Each important event also sends an email.

### Try it: reviews, dashboards and earnings (Phase 6)

- **Reviews and replies:** after a booking completes, the driver sees **Rate your parking** on the booking page (1 to 5 stars and an optional comment). Reviews show on the listing page with a rating summary, a distribution chart and "Show more". Owners reply once per review under **Owner dashboard → Reviews**, which can be filtered by listing.
- **Availability calendar:** listing pages show a month calendar (today to 90 days ahead) with Available / Limited / Full / Closed days; picking a day fills the booking card.
- **Owner dashboard:** **Overview** has a 7 / 30 / 90 day range, key figures (earnings, bookings, occupancy, rating, pending approvals), held / pending payout / paid balances, earnings and bookings charts and the next bookings. **Earnings** is the ledger with status and date filters and a **Download CSV** export. **Calendar** is a week grid of slots by day with bookings and blocked times (IST).
- **Driver pages:** **Bookings** has Upcoming / Active / Past / Cancelled tabs (the tab is in the URL), **Payments** lists payments, refunds and receipts, and the **Overview** shows booking, spend and parking-hours stats with a prompt for bookings still waiting for a review.

#### Cancellation policy

When a driver cancels a confirmed booking, the refund is a share of the **parking charge**; the platform fee and GST are not refunded. (A request the owner has not accepted yet is refunded in full, fee and GST included.) Each listing has one policy, shown on the listing page and at checkout.

| Policy | Full refund | Half refund | No refund |
|---|---|---|---|
| Flexible | Up to 1 hour before the start | Within 1 hour of the start | n/a |
| Moderate | Up to 24 hours before the start | 2 to 24 hours before the start | Within 2 hours of the start |
| Strict | n/a | Up to 48 hours before the start | Within 48 hours of the start |

Owner cancellations and requests that expire or are declined always refund the driver in full.

### Try it: admin panel, disputes and payouts (Phase 7)

Sign in as an admin and open **Admin** (`/admin`):

- **Overview:** platform KPIs (users, listings, bookings, conversion, utilization, GMV, revenue, refunds) for the last 7 / 30 / 90 days, daily charts, top states and cities, and the owner and listing review queues.
- **Users, listings and reviews:** search users by role and status and suspend (with a reason) or activate them (admins and your own account cannot be suspended); suspend or reinstate approved listings; hide or unhide reviews, which removes them from public lists and ratings.
- **Locations:** add and edit states, and add and edit cities (coordinates, price tier, active) per state.
- **Bookings:** search and filter every booking, open its payment, refunds, timeline and disputes, and cancel a booking for the driver (the dialog shows the refund that will be issued).
- **Disputes:** drivers press **Report a problem** on a booking (up to 7 days after it ends); the owner answers once under **Owner dashboard → Disputes**; an admin takes it under review and resolves it with a full or partial refund, no refund or a warning.
- **Payments and payouts:** payments and refunds tables (failed refunds can be retried), and owners' pending payouts with masked payout details; select earnings, enter the transfer reference and mark them paid (no money moves in ParkEase), or download the pending list as CSV. Owners see the reference on their earnings.
- **Reports, settings and audit:** usage and revenue reports per city with CSV export; platform fee, GST, booking timing and per-tier hourly price guidelines (changes apply to new bookings only; owners see a non-blocking warning outside the range); an audit log of every admin action.

### Payments

Payments use a **mock provider by default**: checkout shows a "test payment" dialog, so no keys or accounts are needed. To try real Razorpay checkout in test mode, create a free Razorpay account, switch the dashboard to **Test mode**, generate API keys, and add them to `backend/.env` (never commit it; see [`backend/.env.example`](backend/.env.example)):

```bash
RAZORPAY_KEY_ID=rzp_test_xxxxxxxx
RAZORPAY_KEY_SECRET=xxxxxxxx
```

With both set, the backend switches to Razorpay automatically; remove them to go back to the mock provider. In test mode use card `4111 1111 1111 1111` with any future expiry and any CVV, or test UPI ID `success@razorpay`.

#### Before using real Razorpay keys

- [ ] Set `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` in `backend/.env` (never commit it).
- [ ] Enable auto-capture in the Razorpay dashboard (the app also captures authorized payments itself).
- [ ] Set `PAYMENTS_MOCK_ENABLED=false` in production (production refuses to start without keys).
- [ ] Once deployed (Phase 8), configure the webhook URL in the Razorpay dashboard and set `RAZORPAY_WEBHOOK_SECRET`.
- [ ] Test with card `4111 1111 1111 1111` (any future expiry, any CVV) or UPI `success@razorpay`.

`RAZORPAY_WEBHOOK_SECRET` is only needed for webhooks. Razorpay has to reach your server, so webhooks need a public URL; that comes with deployment in Phase 8. Until then payments are confirmed when the browser verifies the payment after checkout.

### Uploads

Listing photos and owner verification documents are stored on local disk in `backend/uploads/` by default (git-ignored). To use Cloudinary instead, set `CLOUDINARY_URL=cloudinary://<api_key>:<api_secret>@<cloud_name>`. Photos are public; verification documents are private and can only be opened through signed links that expire after 5 minutes. Other related settings (`PUBLIC_BASE_URL`, `STORAGE_LOCAL_DIR`) are listed in [`backend/.env.example`](backend/.env.example).

### Emails in development

Without SMTP settings, emails (verification, password reset) are printed in the backend console — copy the link from there. To send real emails, set `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` (see `backend/.env.example`).

## Tests

```bash
cd backend && ./mvnw test        # uses the smartparking_test database
cd frontend && npm test
```

## Configuration

All settings come from environment variables — see [`backend/.env.example`](backend/.env.example) and [`frontend/.env.example`](frontend/.env.example). Never commit real secrets.

## Project structure

```
backend/   Spring Boot API — package-by-feature under com.smartparking
frontend/  React single-page app
docs/      Spec and implementation plans
```
