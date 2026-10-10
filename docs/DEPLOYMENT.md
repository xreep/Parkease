# Deploying ParkEase

ParkEase runs on the free tiers of six services (e-mail is optional):

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
Neon (DB_URL) -> Cloudinary (CLOUDINARY_URL) -> Razorpay test keys -> [SMTP] -> Render (backend URL)
              -> Vercel (frontend URL) -> back to Render (FRONTEND_URL) -> Razorpay webhook -> smoke test
```

The production profile checks its settings at startup and refuses to boot without a valid database, JWT secret,
frontend URL and Razorpay keys, which is why the Razorpay keys are created before Render, and the webhook (which needs the
Render URL) after it.

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
3. Open **Connection Details** and turn **Connection pooling off** so that the host does **not** contain `-pooler`
   (for example `ep-cool-name-123456.ap-southeast-1.aws.neon.tech`). Use this **direct** host for `DB_URL`:

   ```
   DB_URL=jdbc:postgresql://<direct-host>/<database>?sslmode=require
   DB_USERNAME=<role>
   DB_PASSWORD=<password>
   ```

   **Why not the pooled `-pooler` host:** Neon's pooler is PgBouncer in transaction mode. Flyway takes a session-level
   advisory lock while it migrates, and transaction pooling can hand each statement to a different server connection, so
   the lock is lost or the migration fails. The application already pools connections itself (HikariCP, 5 connections),
   so a second pooler adds nothing.

   `sslmode=require` is mandatory: Neon only accepts TLS, and the backend logs a warning without it. Do not paste Neon's
   `postgresql://user:pass@host/db` string as is; JDBC wants the user and password separately.
4. The free tier suspends compute after a few idle minutes and wakes on the next connection (about a second). The
   backend's pool is capped at 5 connections, keeps none open while idle (`minimum-idle: 0`), closes idle ones after 2
   minutes and every connection after 4 (`idle-timeout 120000`, `max-lifetime 240000`), so it never holds a connection
   that Neon has silently dropped. The first request after a pause pays about a second for the reconnect.

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

**Owner verification documents can be PDFs.** Cloudinary blocks delivery of PDF and ZIP files on new accounts by
default: in the Cloudinary console go to **Settings -> Security** and tick **Allow delivery of PDF and ZIP files**.
Without it the admin's "open document" link for a PDF answers with an error (images still work).

## 3. Razorpay test keys

Do this before Render: the production profile will not boot without the keys.

1. razorpay.com -> sign up -> switch the dashboard to **Test mode**.
2. **Settings -> API Keys -> Generate Test Key**. Keep the key id (`rzp_test_...`) for `RAZORPAY_KEY_ID` and the secret for
   `RAZORPAY_KEY_SECRET`.
3. Test payments use card `4111 1111 1111 1111` with any future expiry and CVV, or UPI `success@razorpay`. Never put live
   keys on a demo deployment.

The webhook is added in step 8, once the backend URL exists.

## 4. E-mail (optional)

Without `MAIL_HOST` the backend prints e-mails to its log, and everything else works (the registration and
password-reset links are then only in the Render log).

With Brevo (free tier): create an account, **SMTP & API -> SMTP**, verify a sender address, then
`MAIL_HOST=smtp-relay.brevo.com`, `MAIL_USERNAME=<your login>`, `MAIL_PASSWORD=<SMTP key>`,
`MAIL_FROM="ParkEase <your-verified-sender>"`.

**Port:** Render's free web services block outbound SMTP on ports 25, 465 and 587, so the usual port times out.
`render.yaml` sets `MAIL_PORT=2525`, which Brevo accepts and which is not on Render's block list; this has not been
tested from a free instance. If mail still times out, either leave `MAIL_HOST` empty or move the service to a paid
instance (then use 587).

## 5. Render (backend)

1. Push the repository to GitHub.
2. In Render: **New -> Blueprint**, pick the repository. Render reads `render.yaml` and proposes the `parkease-api`
   Docker web service (free plan, health check `/api/v1/health`, profiles `prod,demo`).
3. Render asks for every variable marked `sync: false`. Fill them in:

   | Variable | Value |
   |---|---|
   | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from step 1 |
   | `JWT_SECRET` | from step 0 |
   | `DEMO_PASSWORD` | from step 0. Required while `SPRING_PROFILES_ACTIVE` contains `demo` (the application refuses to start without it, whatever the order of the profiles) |
   | `FRONTEND_URL` | the exact Vercel origin, e.g. `https://parkease.vercel.app` (no trailing slash). You can enter a placeholder now and correct it in step 7, but the backend refuses to start until it is an `https://` URL. |
   | `PUBLIC_BASE_URL` | this service's URL, e.g. `https://parkease-api.onrender.com` |
   | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | Razorpay test keys, step 3 (**required**: production has no mock payments) |
   | `RAZORPAY_WEBHOOK_SECRET` | from step 0 (you register the same value in Razorpay in step 8) |
   | `CLOUDINARY_URL` | from step 2 |
   | `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | optional, step 4; leave `MAIL_HOST` empty to only log e-mails |

   `SPRING_PROFILES_ACTIVE` is already `prod,demo` in `render.yaml`. `prod` switches on the hardened settings;
   `demo` seeds the demo accounts, about 100 listings and about 500 bookings on the first start (idempotent, it never
   touches other rows). For a clean database use `prod` only and delete `DEMO_PASSWORD`. Note on the demo data: its
   historical payments exist only in the database, so refunds on them go through the built-in mock provider, while
   payments you make on the deployed site go through Razorpay and are refunded there. Mixed provider history is expected.

   **What demo mode does** (all of it only while the `demo` profile is active):
   - On **every start** the showcase logins (`admin@`, `owner@`, `driver@`, `owner.north@` ... `@parkease.dev`) are reset:
     password taken from `DEMO_PASSWORD`, status active, e-mail verified, owners verified (`owner.pending@` stays pending
     on purpose). The platform settings (fee, GST, hold and approval windows, price guidelines) go back to their
     defaults. So a visitor who changed something on the shared demo cannot break it for long: restart the service.
   - **Rotating `DEMO_PASSWORD`** takes effect on the next restart (the dashboard restarts the service when you save
     the variable); the old password stops working and open sessions of those accounts end. Only the `@parkease.dev`
     logins are rewritten; the generated demo people at `@example.com` keep the password they were seeded with.
   - Those showcase logins **cannot change their password** (`403 DEMO_ACCOUNT_LOCKED`) or be suspended by an admin
     (`409 CANNOT_SUSPEND`). Visitors' own accounts behave normally.
   - **Mail** to `@example.com` and `@parkease.dev` addresses is dropped (logged at INFO: `Demo mode: not sending ...`);
     real visitors' addresses still receive mail.
   - `GET /api/v1/health` returns `{"status":"UP","demoMode":true}`; the frontend uses `demoMode` to show a demo banner.
   - **Demo data dates are fixed at the first seed** (bookings are placed relative to "now" once). After weeks the
     "upcoming" bookings are in the past. To refresh, point `DB_URL` at a new Neon branch or database and restart: the
     seeder fills it again relative to the new "now". Deleting the old branch afterwards frees its storage.

   `CORS_ALLOWED_ORIGINS` is optional: in production CORS allows exactly `FRONTEND_URL` unless you set a comma
   separated list of exact `https://` origins.
4. Apply. Render builds `backend/Dockerfile` (a few minutes: it downloads Maven and the dependencies), starts the
   container and waits for `/api/v1/health`. Flyway creates the schema on the first start; with the `demo` profile
   the first start also seeds the demo data (under 30 seconds).
5. Open `https://<service>.onrender.com/actuator/health`: it must answer `{"status":"UP"}` (this one also checks the
   database, so it doubles as a readiness check).

**Health checks.** Render's check uses `/api/v1/health`, a static answer that never touches the database. A check that
queried Neon every few seconds would keep the free database awake around the clock and burn its compute hours.
`/actuator/health` (database included) is for you to run by hand; it exposes no details.

**Client addresses and rate limits.** Render's proxy sends the visitor's address in `X-Forwarded-For`. The production
profile uses `server.forward-headers-strategy: native`: Tomcat believes that header only when the connection comes from
an *internal proxy* (default: private, loopback, link-local and CGNAT ranges, i.e. `10.0.0.0/8`, `172.16.0.0/12`,
`192.168.0.0/16`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`). Every rate limit is
then keyed on the real visitor, and a caller cannot invent an address by sending the header itself. The startup log
states the mode: `Forwarded headers: strategy=native, internal-proxies=...`.

If Render's proxies turn out to be outside those ranges, all visitors would share the proxy's address (and its
rate-limit budget; see the post-deploy check). Set `INTERNAL_PROXIES` in the Render dashboard to a regular expression
matched against the *whole* peer address, for example

```
203\.0\.113\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}
```

You must paste exactly that form, single backslashes, into the dashboard field: environment values are taken literally, so
doubling the backslashes would make the pattern match nothing. The value replaces the default, so keep the private
ranges you still want. Do not use `.*`: that would let anyone
spoof their address.

**What the backend refuses to start without** (the log names every problem at once): a `JWT_SECRET` that is not base64,
is shorter than 32 bytes or is one of the development secrets from the repository; a missing `DB_URL`; a `FRONTEND_URL`
or `CORS_ALLOWED_ORIGINS` that is missing, not `https://`, or contains a wildcard; missing Razorpay keys.

**Docker.** The image is built by Render from the Dockerfile; it was not built on the machine this repository was
developed on (no Docker installed there), so the first Render build is the first real build. To build it yourself:
`docker build -t parkease-api backend`. It is multi-stage (Maven wrapper with a cached dependency layer, then
`eclipse-temurin:21-jre`), runs as an unprivileged user, listens on `$PORT` and uses
`JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC ..."`. On a 512 MB instance, if Render reports out-of-memory
restarts, set `JAVA_OPTS` to `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError`.

## 6. Vercel (frontend)

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

## 7. Point the backend at the frontend

In Render, set `FRONTEND_URL` to the real Vercel origin (exactly as the browser shows it: scheme and host, no path, no
trailing slash) and let the service redeploy. It is both the only allowed CORS origin and the base of the links in
e-mails.

## 8. Razorpay webhook

1. **Settings -> Webhooks -> Add new webhook** (Test mode):
   - URL: `https://<service>.onrender.com/api/v1/payments/webhook`
   - Secret: the same value as `RAZORPAY_WEBHOOK_SECRET` on Render
   - Events: `payment.captured`, `payment.failed`, `refund.processed`, `refund.failed`
2. Make a test payment (smoke checklist below) and confirm the webhook shows 2xx deliveries.

## 9. Post-deploy smoke checklist

Open the Vercel URL, then:

- [ ] `https://<service>.onrender.com/api/v1/health` and `/actuator/health` both return `{"status":"UP"}` and nothing else (no details); other
      `/actuator/*` paths are not found.
- [ ] Home page loads, the map shows tiles, search for a city returns listings (demo data), no CORS errors in the
      browser console.
- [ ] Log in as `driver@parkease.dev` with `DEMO_PASSWORD`; open a listing and see the price quote.
- [ ] Book a slot and pay with the Razorpay test card; the booking becomes **Confirmed** and a receipt downloads.
- [ ] Razorpay dashboard -> Webhooks shows 2xx deliveries to the backend.
- [ ] Log in as `owner@parkease.dev`: dashboard and earnings load; upload a photo to a draft listing and confirm that
      its URL starts with `https://res.cloudinary.com/`.
- [ ] Log in as `admin@parkease.dev`: stats and the moderation queues load.
- [ ] As admin, open an **owner verification document that is a PDF** (Admin -> Verification -> the pending owner ->
      open document): it must display. An error here means Cloudinary's "Allow delivery of PDF and ZIP files" is off (step 2).
- [ ] Reload a deep link (for example `/search`) on Vercel: it opens the app, not a 404.
- [ ] Response headers on an API call include `Strict-Transport-Security`, `X-Content-Type-Options` and
      `X-Frame-Options`.
- [ ] Optional: trigger a password reset and check that the e-mail arrives (or, without SMTP, that the link is in the
      Render log).
- [ ] **Rate limits are per visitor, and a forged header does not change the bucket.**

      ```bash
      API=https://<service>.onrender.com/api/v1
      curl -s ifconfig.me; echo                      # your public IP, to compare with the log below
      # 130 requests with a fake X-Forwarded-For: the budget is 120/min, so the last ones must be 429
      for i in $(seq 1 130); do curl -s -o /dev/null -w "%{http_code}\n" -H "X-Forwarded-For: 198.51.100.10" \
        "$API/search?lat=12.97&lng=77.59"; done | sort | uniq -c
      # a *different* fake address straight after is still limited, because it is still you
      curl -s -o /dev/null -w "%{http_code}\n" -H "X-Forwarded-For: 198.51.100.11" "$API/search?lat=12.97&lng=77.59"
      ```

      Expected: about 120 `200` and some `429`, then `429` again for the second fake address. Render's proxy appends
      your real address to whatever you send, and the backend only believes the entry its own proxy added, so forging
      the header gains nothing.

      Then confirm that visitors are separated *from each other* (not merged into one proxy address): read the Render log.
      Each rejection prints `Rate limited client=<address> rule=public-reads method=GET path=/api/v1/search`; `client=`
      must be your public IP from above, not a `10.x` / `100.64.x` proxy address, and the startup line
      `Forwarded headers: strategy=native, internal-proxies=...` shows the active setting. As a final check, while your
      address is limited, open the site from another network (phone on mobile data): it must still work. If `client=`
      shows a proxy address, or the other network is limited too, the proxy is not recognised as internal: set
      `INTERNAL_PROXIES` (step 5).

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| First request after a quiet period takes 30-60 s or times out | Render's free web service sleeps after 15 minutes without traffic and cold-starts a JVM. Warm it up only shortly before a demo: open `/api/v1/health` a minute or two beforehand and wait for `UP`. Do not ping it around the clock from a monitor, which would use up the free instance hours for no benefit. |
| Render deploy fails with `Unsafe production configuration` | The log lists each problem (secret, DB, URLs). Fix the named variables in the dashboard and redeploy. |
| `RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET must be set in production` | Production has no mock payments. Add the test keys (step 3). |
| `DEMO_PASSWORD must be set when the demo profile is active (every seeded account logs in with it)` | Set `DEMO_PASSWORD` or drop `demo` from `SPRING_PROFILES_ACTIVE`. |
| Browser console: CORS error | `FRONTEND_URL` / `CORS_ALLOWED_ORIGINS` does not exactly match the origin in the address bar (scheme, subdomain, no trailing slash). A preview deployment URL on Vercel is a different origin. |
| Browser console: CSP violation | The backend host is not `*.onrender.com`; edit `connect-src` / `img-src` in `frontend/vercel.json`. |
| `429 Too Many Requests` (`RATE_LIMITED`) | Per-IP limits: auth 10/min, public search/listing/quote/availability/reviews 120/min, uploads and disputes 20/min, payment verification 30/min. `Retry-After` says how long to wait. If every visitor is throttled together, the backend sees one address for all of them: the proxy is not in `INTERNAL_PROXIES` (step 5); the log line `Rate limited client=...` shows which address it used. |
| Photos or documents disappear after a restart | `CLOUDINARY_URL` is not set; files are on Render's ephemeral disk. |
| `FATAL: no pg_hba.conf entry` / SSL errors | `sslmode=require` missing from `DB_URL`. |
| Flyway fails with a lock or "prepared statement" error | `DB_URL` uses the `-pooler` host. Use Neon's direct host (step 1). |
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
