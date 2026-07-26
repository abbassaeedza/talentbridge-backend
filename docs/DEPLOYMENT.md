# Render and Supabase Deployment

This guide deploys the TalentBridge Spring Boot API on Render's free web-service plan with Supabase PostgreSQL and Storage.

## Architecture

```text
Vercel frontend
    |
Render Spring Boot API
    |-- Supabase PostgreSQL
    `-- Supabase Storage
```

## 1. Create the Supabase project

1. Create a project in the Supabase dashboard.
2. Save the database password.
3. Wait for the project to become healthy.

Open **Project Settings > Database > Connection string** and select the **Session pooler**.
Session mode on port `5432` suits a persistent Java backend and supports IPv4.

Map the connection string to Render variables:

```text
postgresql://DB_USERNAME:DB_PASSWORD@DB_HOST:5432/DB_NAME
```

Typical Supabase values are:

```text
DB_HOST=aws-0-your-region.pooler.supabase.com
DB_PORT=5432
DB_NAME=postgres
DB_USERNAME=postgres.your-project-reference
DB_PASSWORD=your-database-password
DB_SSL_MODE=require
```

Do not use transaction mode on port `6543` for Flyway migrations.

## 2. Create Storage

1. Open **Storage** in Supabase.
2. Create a bucket named `talentbridge-files`.
3. Mark the bucket public.
4. Keep the file-size limit at least `50 MB` if submission documents can reach the backend limit.

Every object in a public bucket is readable by anyone who knows its URL.
Uploads remain protected because only the Render backend receives the service-role key.

Open **Project Settings > API** and copy:

```text
SUPABASE_URL=https://your-project-reference.supabase.co
SUPABASE_SERVICE_ROLE_KEY=your-service-role-jwt
SUPABASE_STORAGE_BUCKET=talentbridge-files
```

Never expose `SUPABASE_SERVICE_ROLE_KEY` in Vercel variables, frontend files, logs, screenshots, or GitHub.

## 3. Configure Resend

1. Create a Resend account and API key.
2. For an initial test, use `TalentBridge <onboarding@resend.dev>` as `RESEND_FROM_EMAIL`.
3. The `resend.dev` sender can deliver only to the email address that owns the Resend account.
4. To test with other recipients, add and verify a domain in Resend, then use an address on that domain.

Every existing in-app notification also attempts an email with the same title and message.
Resend delivery failure is logged and does not fail the action that created the notification.

## 4. Create GitHub OAuth credentials

Create a GitHub OAuth App after Vercel assigns the frontend URL.

```text
Homepage URL: https://your-vercel-project.vercel.app
Authorization callback URL: https://your-vercel-project.vercel.app/github/callback
```

Copy its client ID and client secret for Render.
The frontend receives only the client ID.

## 5. Deploy the Render Blueprint

1. Open Render and select **New > Blueprint**.
2. Connect `abbassaeedza/talentbridge-backend`.
3. Render reads the committed `render.yaml` and creates `talentbridge-backend`.
4. Enter every variable marked for dashboard input.

For an existing Blueprint service, Render does not prompt again for newly added `sync: false` variables during later syncs.
Add `RESEND_API_KEY` and `RESEND_FROM_EMAIL` manually under the service's **Environment** page, then redeploy.

Required dashboard values:

```text
DB_HOST=<Supabase session-pooler host>
DB_PORT=5432
DB_NAME=postgres
DB_USERNAME=<Supabase session-pooler user>
DB_PASSWORD=<Supabase database password>
FRONTEND_URL=https://your-vercel-project.vercel.app
OPENAI_API_KEY=<OpenAI API key>
GITHUB_CLIENT_ID=<GitHub OAuth client ID>
GITHUB_CLIENT_SECRET=<GitHub OAuth client secret>
GITHUB_REDIRECT_URI=https://your-vercel-project.vercel.app/github/callback
SUPABASE_URL=https://your-project-reference.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<Supabase service-role JWT>
RESEND_API_KEY=<Resend API key>
RESEND_FROM_EMAIL=TalentBridge <onboarding@resend.dev>
APP_SEED_COORDINATOR_EMAIL=<private coordinator email>
APP_SEED_COORDINATOR_PASSWORD=<strong unique password>
```

`FRONTEND_URL` must exactly match the production Vercel origin without a trailing slash because the backend permits one CORS origin.
Render generates `JWT_SECRET` from `render.yaml`.
The Blueprint also sets `JWT_EXPIRATION=86400000`, `JWT_REFRESH_EXPIRATION=604800000`, `PARTY_MIN_SIZE=2`, `PARTY_MAX_SIZE=3`, and `APP_DEMO_MODE=true`.
These values do not need manual entry unless you intentionally override them in Render.
Flyway creates and upgrades the Supabase schema during backend startup.

### Demo coordinator behavior

While `APP_DEMO_MODE=true`, each backend startup synchronizes the stored coordinator password with `APP_SEED_COORDINATOR_PASSWORD`.
The frontend demo card calls the backend demo-login endpoint and never receives the password.
Changing a `VITE_*` value cannot safely solve this because Vite embeds those values in public browser code.

## 6. Finish Vercel configuration

Copy the Render public URL into the frontend's Vercel variable:

```text
VITE_API_URL=https://your-render-service.onrender.com
```

Redeploy the frontend after changing the variable.

## Verify

1. Open the Render service logs and confirm Flyway migration success.
2. Log in through the Vercel frontend.
3. Confirm the browser shows no mixed-content or CORS errors.
4. Upload a small submission document.
5. Confirm the saved document URL begins with `SUPABASE_URL/storage/v1/object/public/talentbridge-files/`.
6. Open the document URL in a private browser window.
7. Complete GitHub OAuth and confirm the linked username appears.
8. Trigger an in-app notification and confirm the matching email appears in the Resend dashboard.

## Before public launch

1. Set `APP_DEMO_MODE=false` in Render.
2. Redeploy the backend and confirm `POST /api/auth/demo-login` returns HTTP 404.
3. Change the coordinator password through the authenticated password-change flow.
4. Remove the demo card from the frontend and redeploy Vercel.
5. Replace `onboarding@resend.dev` with an address on a verified sending domain.

## Free-tier behavior

Render sleeps after inactivity, so the first API request can take about one minute.
Supabase may pause inactive free projects and enforces free database, storage, and egress quotas.
OpenAI API usage is separately billed and is not part of free hosting.
This setup suits evaluation and demonstration traffic, not uptime-sensitive production use.

## Troubleshooting

### Database connection fails

Use the Session pooler host, port `5432`, pooler username including the project reference, and `DB_SSL_MODE=require`.

### Browser reports CORS failure

Set `FRONTEND_URL` to the exact Vercel Production origin, then redeploy Render.
Preview deployment origins are not allowed by the current single-origin configuration.

### Upload returns HTTP 400 or 404

Confirm the bucket is named exactly `talentbridge-files`, is public, and the service-role JWT belongs to the same Supabase project as `SUPABASE_URL`.

### Resend returns HTTP 403

The `onboarding@resend.dev` sender can send only to the Resend account owner's email.
Verify a custom domain and update `RESEND_FROM_EMAIL` to deliver to other users.

### Backend responds slowly once

Wait for the Render free service to wake.
Subsequent requests remain fast until it sleeps again.
