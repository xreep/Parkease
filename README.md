# ParkEase — Smart Parking Slot Rental & Availability Platform

A web platform where private parking owners rent out unused slots and drivers find, reserve and pay for parking in advance — near metro stations, office complexes and commercial hubs across India.

- **Backend:** Spring Boot 4.1 (Java 21), PostgreSQL 16, Flyway, Spring Security + JWT
- **Frontend:** React + Vite + TypeScript, Tailwind CSS v4, TanStack Query
- **Docs:** [Design spec](docs/superpowers/specs/2026-10-04-smart-parking-design.md) · API docs at `http://localhost:8080/swagger-ui` when running

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Scaffold, auth (register/login/refresh/verify/reset), profile, all-India states & cities | ✅ |
| 2 | Owner verification, listings, slots, availability, uploads | ⏳ |
| 3 | Search + map + listing detail | ⏳ |
| 4 | Booking, pricing, Razorpay payments, invoices | ⏳ |
| 5 | Lifecycle jobs, cancellations/refunds, notifications | ⏳ |
| 6 | Owner dashboard, earnings, reviews | ⏳ |
| 7 | Admin panel, reports, disputes, payouts | ⏳ |
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
| Driver | driver@parkease.dev | Demo@1234 |

Change the password with the `DEMO_PASSWORD` environment variable (applies only when the demo accounts are first created).

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
