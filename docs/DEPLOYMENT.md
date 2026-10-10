# Deploying ParkEase

ParkEase runs on free tiers of four services:

| Part | Service | Config in this repo |
|---|---|---|
| API (Spring Boot, Docker) | Render web service | `render.yaml`, `backend/Dockerfile` |
| Database | Neon (PostgreSQL) | Flyway migrations run on startup |
| Frontend (React SPA) | Vercel | `frontend/vercel.json` |
| Photos and owner documents | Cloudinary | `CLOUDINARY_URL` |
| E-mail (optional) | Any SMTP, e.g. Brevo | `MAIL_*` |
| Payments | Razorpay (test mode) | `RAZORPAY_*` |

Secrets are only ever entered in the hosts' dashboards. Nothing in the repository contains a real key; the full list of
backend settings, with comments, is `backend/.env.example`.

Do the steps in this order, because each one produces a value the next one needs.

```
Neon (DB_URL) -> Cloudinary (CLOUDINARY_URL) -> Render (backend URL) -> Vercel (frontend URL)
              -> back to Render (FRONTEND_URL) -> Razorpay webhook -> smoke test
```

## 0. Generate the secrets you choose yourself

```bash
openssl rand -base64 48        # JWT_SECRET (the app refuses anything shorter than 32 bytes or the dev default)
openssl rand -base64 24        # RAZORPAY_WEBHOOK_SECRET (any string works; you enter the same one in Razorpay)
```

Pick a `DEMO_PASSWORD` too if you deploy the demo (profile `prod,demo`): at least 8 characters with a letter and a digit.
Every seeded account (admin, owners, drivers) shares it, and the demo is public, so do not reuse a real password.

## 1. Neon (database)

1. Sign up at neon.tech, create a project (region: Singapore, `ap-southeast-1`, matches the Render service).
2. Use the default database and the default owner role. Flyway needs `CREATE EXTENSION btree_gist` (migration V6), and
   the project's default owner role is allowed to; a restricted role is not.
3. Open **Connection Details**, unpooled or pooled host both work, and build the JDBC URL from the parts:

   ```
   DB_URL=jdbc:postgresql://<host>/<database>?sslmode=require
   DB_USERNAME=<role>
   DB_PASSWORD=<password>
   ```

   `sslmode=require` is mandatory: Neon only accepts TLS, and the backend logs a warning without it. Do not paste Neon's
   `postgresql://user:pass@host/db` string as is; JDBC wants the user and password separately.
4. The free tier suspends compute after a few idle minutes and wakes on the next connection (about a second). The
   backend's pool is capped at 5 connections and recycles them every 5 minutes, so it recovers from that on its own.

## 2. Cloudinary (uploads)

Render's disk is ephemeral: every restart or redeploy deletes files stored locally, which would lose every listing
photo and owner document. In production, use Cloudinary.

1. Create a free account at cloudinary.com.
2. Dashboard -> **API Keys**: copy the **API environment variable**:

   ```
   CLOUDINARY_URL=cloudinary://<api_key>:<api_secret>@<cloud_name>
   ```

Without it the backend still starts, but logs `CLOUDINARY_URL is not set ... ephemeral` and falls back to local disk.
Photos are public Cloudinary URLs; owner documents are private and served through short-lived signed links.

## 3. Render (backend)

1. Push the repository to GitHub.
2. In Render: **New -> Blueprint**, pick the repository. Render reads `render.yaml` and proposes the `parkease-api`
   Docker web service (free plan, health check `/actuator/health`, profiles `prod,demo`).
3. Render asks for every variable marked `sync: false`. Fill them in:

   | Variable | Value |
   |---|---|
   | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from step 1 |
   | `JWT_SECRET` | from step 0 |
   | `DEMO_PASSWORD` | from step 0 (needed by the `demo` profile; remove `,demo` from `SPRING_PROFILES_ACTIVE` for a clean database) |
   | `FRONTEND_URL` | the exact Vercel origin, e.g. `https://parkease.vercel.app` (no trailing slash). You can enter a placeholder now and correct it in step 5, but the backend refuses to start until it is an `https://` URL. |
   | `PUBLIC_BASE_URL` | this service's URL, e.g. `https://parkease-api.onrender.com` |
   | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | Razorpay test keys, step 6 (**required**: production has no mock payments) |
   | `RAZORPAY_WEBHOOK_SECRET` | from step 0 |
   | `CLOUDINARY_URL` | from step 2 |
   | `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | optional, step 7; leave `MAIL_HOST` empty to only log e-mails |

   `CORS_ALLOWED_ORIGINS` is optional: in production CORS allows exactly `FRONTEND_URL` unless you set a comma
   separated list of exact `https://` origins.
4. Apply. Render builds `backend/Dockerfile` (a few minutes: it downloads Maven and the dependencies), starts the
   container and waits for `/actuator/health`. Flyway creates the schema on the first start; with the `demo` profile
   the first start also seeds the demo data (under 30 seconds).
5. Open `https://<service>.onrender.com/actuator/health`: it must answer `{"status":"UP"}`.

**What the backend refuses to start without** (the log names every problem at once): a `JWT_SECRET` that is not base64,
is shorter than 32 bytes or is one of the development secrets from the repository; a missing `DB_URL`; a `FRONTEND_URL`
or `CORS_ALLOWED_ORIGINS` that is missing, not `https://`, or contains a wildcard; missing Razorpay keys.

**Docker.** The image is built by Render from the Dockerfile; it was not built on the machine this repository was
developed on (no Docker installed there), so the first Render build is the first real build. To build it yourself:
`docker build -t parkease-api backend`. It is multi-stage (Maven wrapper with a cached dependency layer, then
`eclipse-temurin:21-jre`), runs as an unprivileged user, listens on `$PORT` and uses
`JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC ..."`. On a 512 MB instance, if Render reports out-of-memory
restarts, set `JAVA_OPTS` to `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError`.

## 4. Vercel (frontend)

1. **Add New -> Project**, import the repository, and set **Root Directory** to `frontend`. The framework preset is
   Vite; `vercel.json` already sets the SPA rewrite, the build and the security headers.
2. Environment variables (Production):

   | Variable | Value |
   |---|---|
   | `VITE_API_URL` | `https://<service>.onrender.com/api/v1` |
   | `VITE_PUBLIC_URL` | the site's own origin, e.g. `https://parkease.vercel.app` (used for the link-preview image) |

3. Deploy. The Content-Security-Policy in `frontend/vercel.json` only allows API calls and images from
   `https://*.onrender.com`. If you put the backend on a custom domain, add that origin to `connect-src` and `img-src`
   in `vercel.json`, otherwise the browser blocks every request.

## 5. Point the backend at the frontend

In Render, set `FRONTEND_URL` to the real Vercel origin (exactly as the browser shows it: scheme and host, no path, no
trailing slash) and let the service redeploy. It is both the only allowed CORS origin and the base of the links in
e-mails.

## 6. Razorpay (test mode)

1. razorpay.com -> sign up -> switch the dashboard to **Test mode**.
2. **Settings -> API Keys -> Generate Test Key**: put the key id (`rzp_test_...`) in `RAZORPAY_KEY_ID` and the secret in
   `RAZORPAY_KEY_SECRET` on Render.
3. **Settings -> Webhooks -> Add new webhook**:
   - URL: `https://<service>.onrender.com/api/v1/payments/webhook`
   - Secret: the same value as `RAZORPAY_WEBHOOK_SECRET`
   - Events: `payment.captured`, `payment.failed`, `refund.processed`, `refund.failed`
4. Test payments: card `4111 1111 1111 1111` with any future expiry and CVV, or UPI `success@razorpay`. Never put live
   keys on a demo deployment.

## 7. E-mail (optional)

Without `MAIL_HOST` the backend prints e-mails to its log, and everything else works (the registration and
password-reset links are then only in the Render log).

With Brevo (free tier): create an account, **SMTP & API -> SMTP**, verify a sender address, then
`MAIL_HOST=smtp-relay.brevo.com`, `MAIL_USERNAME=<your login>`, `MAIL_PASSWORD=<SMTP key>`,
`MAIL_FROM="ParkEase <your-verified-sender>"`.

**Port:** Render's free web services block outbound SMTP on ports 25, 465 and 587, so the usual port times out.
`render.yaml` sets `MAIL_PORT=2525`, which Brevo accepts and which is not on Render's block list; this has not been
tested from a free instance. If mail still times out, either leave `MAIL_HOST` empty or move the service to a paid
instance (then use 587).

## 8. Post-deploy smoke checklist

Open the Vercel URL, then:

- [ ] `https://<service>.onrender.com/actuator/health` returns `{"status":"UP"}` and nothing else (no details); other
      `/actuator/*` paths are not found.
- [ ] Home page loads, the map shows tiles, search for a city returns listings (demo data), no CORS errors in the
      browser console.
- [ ] Log in as `driver@parkease.dev` with `DEMO_PASSWORD`; open a listing and see the price quote.
- [ ] Book a slot and pay with the Razorpay test card; the booking becomes **Confirmed** and a receipt downloads.
- [ ] Razorpay dashboard -> Webhooks shows 2xx deliveries to the backend.
- [ ] Log in as `owner@parkease.dev`: dashboard and earnings load; upload a photo to a draft listing and confirm that
      its URL starts with `https://res.cloudinary.com/`.
- [ ] Log in as `admin@parkease.dev`: stats and the moderation queues load.
- [ ] Reload a deep link (for example `/search`) on Vercel: it opens the app, not a 404.
- [ ] Response headers on an API call include `Strict-Transport-Security`, `X-Content-Type-Options` and
      `X-Frame-Options`.
- [ ] Optional: trigger a password reset and check that the e-mail arrives (or, without SMTP, that the link is in the
      Render log).

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| First request after a quiet period takes 30-60 s or times out | Render's free web service sleeps after 15 minutes without traffic and cold-starts a JVM. Open the health URL a minute before a demo, or ping `/actuator/health` every 10 minutes from a free monitor such as UptimeRobot. |
| Render deploy fails with `Unsafe production configuration` | The log lists each problem (secret, DB, URLs). Fix the named variables in the dashboard and redeploy. |
| `RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be set in production` | Production has no mock payments. Add the test keys (step 6). |
| `Could not resolve placeholder 'DEMO_PASSWORD'` | Set `DEMO_PASSWORD` or drop `demo` from `SPRING_PROFILES_ACTIVE`. |
| Browser console: CORS error | `FRONTEND_URL` / `CORS_ALLOWED_ORIGINS` does not exactly match the origin in the address bar (scheme, subdomain, no trailing slash). A preview deployment URL on Vercel is a different origin. |
| Browser console: CSP violation | The backend host is not `*.onrender.com`; edit `connect-src` / `img-src` in `frontend/vercel.json`. |
| `429 Too Many Requests` (`RATE_LIMITED`) | Per-IP limits: auth 10/min, public search/listing/quote/availability/reviews 120/min, uploads and disputes 20/min, payment verification 30/min. `Retry-After` says how long to wait. If every visitor is throttled together, the backend sees one address for all of them: check `app.security.trusted-proxy-hops` (default 1, the number of proxies in front of the app). |
| Photos or documents disappear after a restart | `CLOUDINARY_URL` is not set; files are on Render's ephemeral disk. |
| `FATAL: no pg_hba.conf entry` / SSL errors | `sslmode=require` missing from `DB_URL`. |
| `permission denied to create extension "btree_gist"` | The database role cannot create extensions; use Neon's default owner role. |
| Connection timeouts right after idle | Neon waking up; the pool retries, the next request succeeds. |
| 413 on a request | JSON bodies are limited to 1 MB, single uploads to 5 MB (images: JPEG, PNG, WebP; documents: PDF, JPEG, PNG). |
| Out-of-memory restarts on Render free | Lower the heap: `JAVA_OPTS=-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError`. |

## Running the production profile locally

```bash
cd backend
set -a && source .env && set +a      # DB_URL, JWT_SECRET, FRONTEND_URL=https://localhost... , RAZORPAY_* ...
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

`FRONTEND_URL` must be `https://...` even locally (the check cannot tell), for example `https://parkease.local`.
