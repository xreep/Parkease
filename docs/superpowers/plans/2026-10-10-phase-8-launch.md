# Phase 8 — Demo Data, Hardening, Deployment, CI, Polish, Docs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make ParkEase demo-ready and deployable: realistic seeded data, hardened security, deploy configs for Render + Neon + Vercel + Cloudinary, GitHub Actions CI, frontend performance/a11y polish and final documentation.

**Spec:** `docs/superpowers/specs/2026-10-04-smart-parking-design.md` — **§23 Phase 8 Decisions** (binding), §9 Security, §13 Deployment.

## Global Constraints

- Project root: `/Users/adityaraj/my projects/Parkease` (quote it). `source ~/.zshrc` before `./mvnw`. Full backend suite: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 ./mvnw -q test`.
- Branch `phase-8-launch`. Never push, merge or switch branches. Backend and frontend agents may work concurrently: stage only your own paths explicitly (never `git add -A`, `.` or `commit -a`); never stash/reset/checkout outside your area. Repo-root files (README, docs/, .github/, render.yaml) belong to the task that names them.
- Never commit secrets or `.env` files; only `.env.example` with placeholders. No real keys in tests or docs.
- No new migrations unless unavoidable (next is V18).
- Existing behaviour must not change except where a task says so; all existing tests keep passing.
- Frontend: existing primitives, light/dark, 375 px, tests with `renderApp` + `axios-mock-adapter`.
- Checks: backend full suite; frontend `npm run lint && npx tsc -b && npm test -- --run && npm run build`.
- Commits: conventional + harness-provided `Co-Authored-By` trailer.

---

### Task 1: Demo data seeder

**Files:** `common/seed/*` (new `DemoActivitySeeder` + helpers), `application-demo.yml`, seeders' `@Profile({"dev","demo"})`.

- [ ] Per §23: deterministic (`new Random(20261010)`), idempotent (marker: skip when a demo marker row/user exists — e.g. `seed.activity@parkease.dev` or a `platform_settings` key `demo_seeded_at`), relative to `Clock`. Create owners (≈30 across major cities, ~4 pending verification), drivers (≈50, 1–2 vehicles each), extra listings for new owners where needed (reuse `DemoListingSeeder` patterns; all states covered stays true), ~500 bookings spread over −90…+14 days: COMPLETED (majority), CONFIRMED/ACTIVE (upcoming/now), CANCELLED (driver/owner/admin with refunds per policy), REJECTED, EXPIRED; payments via the mock provider ids (CAPTURED/REFUNDED/PARTIALLY_REFUNDED), refunds rows, invoices (numbers continue the sequence), owner earnings (HELD/PENDING_PAYOUT/PAID with references/REVERSED), ~200 reviews (ratings skewed 4–5, some 1–3, owner replies on ~40%, one hidden) with listing aggregates recomputed, ~8 disputes (OPEN/UNDER_REVIEW/RESOLVED of each resolution), notifications for recent events, a few audit rows. Must respect DB constraints (no slot overlaps — allocate slots per time window) and money rules (pricing via `PricingService` with the booking's settings snapshot).
- [ ] Insert efficiently (JDBC batch or JPA batching; < 30 s on a laptop). Never modify rows it didn't create.
- [ ] Keep the existing demo accounts (admin@/owner@/driver@ etc.) and attach some activity to them so the demo logins show populated dashboards.
- [ ] **Tests:** seeding twice is idempotent; counts in expected ranges; every booking's payment/earning/refund invariants hold (sum refunds ≤ amount; earning net = gross − refunded base or 0 when reversed; GMV consistent); no overlap violations; admin stats endpoint returns non-zero KPIs; runtime bound.
- [ ] Commit: `feat: realistic demo activity seed for dev and hosted demo`

### Task 2: Security hardening

**Files:** `common/security/*` (generalise `AuthRateLimitFilter` → `RateLimitFilter` with route policies), headers config, `application*.yml`, prod config validation (`@ConfigurationProperties` + `@Validated` or startup check), multipart limits, error handler review.

- [ ] Rate limits per §23 (per client IP; honour `X-Forwarded-For` only from a configured trusted proxy setting, default: Render sends it — make `app.security.trust-forwarded-for` true in prod); `429` ProblemDetail code `RATE_LIMITED` + `Retry-After`. Existing auth limits unchanged.
- [ ] Security headers on API responses (HSTS only in prod); actuator: only `health` exposed, details hidden.
- [ ] Prod fail-fast: `JWT_SECRET` ≥ 32 bytes and not the dev default, `DB_URL` set, `FRONTEND_URL` (CORS) set and https, payments: Razorpay keys required unless `PAYMENTS_MOCK_ENABLED=true` explicitly (existing rule — keep), mail and Cloudinary optional with warnings.
- [ ] Request limits: JSON body ≤ 1 MB, multipart ≤ 5 MB (existing upload rule), max page size already 100.
- [ ] Verify no stack traces/SQL in error bodies (test a forced 500).
- [ ] **Tests:** rate limit 429 + Retry-After on search after N calls (configurable small limits in tests), headers present, actuator endpoints other than health 404/401, prod validation failures (context fails for weak secret), 500 body shape.
- [ ] Commit: `feat: public rate limits, security headers and production config checks`

### Task 3: Deployment configuration and docs

**Files:** `backend/Dockerfile`, `backend/.dockerignore`, `render.yaml` (repo root), `application-prod.yml` (Neon SSL params, pool 5, forwarded headers, logging), `frontend/vercel.json` (SPA rewrite, security headers incl. CSP allowing Razorpay checkout, Cloudinary images, OSM tiles, API origin), `frontend/.env.example`, `backend/.env.example` (complete list), `docs/DEPLOYMENT.md`.

- [ ] Dockerfile: multi-stage (Maven wrapper build with dependency cache → `eclipse-temurin:21-jre` slim), non-root user, `JAVA_OPTS` for small memory (Render free 512 MB: `-XX:MaxRAMPercentage=75`), `PORT` env respected (`server.port=${PORT:8080}`), healthcheck path.
- [ ] `render.yaml`: web service from `backend/Dockerfile`, env var keys listed (values `sync: false`), health check, `SPRING_PROFILES_ACTIVE=prod,demo`.
- [ ] Frontend API base: `VITE_API_URL` used by `lib/api.ts` (default `/api/v1` for dev proxy); verify uploads/receipt/CSV URLs use it.
- [ ] `docs/DEPLOYMENT.md`: step-by-step for Neon (create DB, connection string), Render (blueprint, env vars), Vercel (root `frontend`, env), Cloudinary, SMTP (Brevo), Razorpay test keys + webhook URL `https://<render>/api/v1/payments/webhook` and secret, post-deploy smoke checklist, troubleshooting (cold starts on free tier).
- [ ] **Tests:** `docker build` succeeds locally if Docker is available (else document); backend test that `server.port` honours `PORT`; frontend test that the API base comes from `VITE_API_URL`.
- [ ] Commit: `chore: Docker, Render, Vercel and Neon deployment configuration`

### Task 4: CI

**Files:** `.github/workflows/ci.yml`, `.github/dependabot.yml`, README badge (README edit belongs to Task 6 — leave badge markdown snippet in the report).

- [ ] Jobs: `backend` (ubuntu, Temurin 21, Maven cache, Postgres 16 service with btree_gist available — official image has it — env for test DB, `./mvnw -B test`), `frontend` (Node LTS matching `.nvmrc`/engines, `npm ci`, lint, tsc, tests, build), `audit` (non-blocking: `npm audit --omit=dev --audit-level=high` and OWASP dependency-check or `mvn versions` — keep fast, continue-on-error). Concurrency cancel for the same ref. Triggers: push to main, pull_request.
- [ ] Test config must work with the CI DB (check how tests get their datasource — test profile/env).
- [ ] Commit: `ci: GitHub Actions for backend, frontend and dependency audit`

### Task 5: Frontend polish

**Files:** `App.tsx` (lazy routes + Suspense fallback), `components/ErrorBoundary.tsx`, `pages/ErrorPage.tsx`, `lib/usePageTitle.ts` (or similar) used on every page, `index.html` (meta, OG, theme-color), `public/` (favicon set, manifest), axe checks (`vitest-axe` or `jest-axe`) on key pages, fix any violations found.

- [ ] Code splitting: role areas (driver, owner, admin) and heavy pages (map/search, charts) lazy-loaded; main bundle under the warning limit; no flash of blank page (fallback spinner).
- [ ] Error boundary catches render errors with a friendly page and "Reload" / "Go home"; API 5xx shows a toast (verify existing).
- [ ] Titles: "ParkEase — <page>" everywhere.
- [ ] axe: home, search, listing, login, driver booking detail, owner dashboard, admin overview — no serious/critical violations.
- [ ] **Tests:** lazy route renders after Suspense; error boundary; titles; axe checks.
- [ ] Commit: `feat(frontend): code splitting, error boundary, page titles and accessibility fixes`

### Task 6: Leftovers and final docs

**Files:** small fixes from earlier ledgers (listed below), `README.md` (final), `docs/ARCHITECTURE.md`, `docs/PROJECT_REPORT.md`.

- [ ] Leftovers: (a) refund duplicate email on owner reject (one email); (b) receipt PDF ₹ glyph renders (embed a font that has ₹ or use "Rs." fallback); (c) mobile header collapse polish if any overflow remains; (d) public reviews summary computed from listing aggregates + distribution query only once per listing page load (avoid per-page recompute — return summary only on page 0).
- [ ] README: overview, features by role, tech stack, architecture diagram (Mermaid), screenshots list (placeholders paths `docs/screenshots/*.png`), local setup, demo accounts, testing commands + counts, deployment link to DEPLOYMENT.md, CI badge, project structure, license note.
- [ ] ARCHITECTURE.md: modules, request flow, booking/payment/refund sequence (Mermaid), data model overview, concurrency (lock order), jobs, security model.
- [ ] PROJECT_REPORT.md: problem, objectives, solution, modules, tech, testing summary, results, limitations, future scope (Redis/multi-instance, real payouts, mobile apps, dynamic pricing).
- [ ] **Tests:** for (a), (b), (d).
- [ ] Commit: `docs: final README, architecture and project report; small fixes`

### Task 7: Controller verification and deployment

- [ ] Fresh dev DB: start backend with dev seed → demo data loads in < 30 s; dashboards populated for admin/owner/driver; spot-check money invariants via SQL.
- [ ] Docker build + run locally (if Docker available) against the dev DB.
- [ ] CI green on the PR.
- [ ] With the user: create Neon/Render/Vercel/Cloudinary accounts; user enters secrets in dashboards; deploy; smoke test the hosted site.
