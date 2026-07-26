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

## 3. Create GitHub OAuth credentials

Create a GitHub OAuth App after Vercel assigns the frontend URL.

```text
Homepage URL: https://your-vercel-project.vercel.app
Authorization callback URL: https://your-vercel-project.vercel.app/github/callback
```

Copy its client ID and client secret for Render.
The frontend receives only the client ID.

## 4. Deploy the Render Blueprint

1. Open Render and select **New > Blueprint**.
2. Connect `abbassaeedza/talentbridge-backend`.
3. Render reads the committed `render.yaml` and creates `talentbridge-backend`.
4. Enter every variable marked for dashboard input.

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
APP_SEED_COORDINATOR_EMAIL=<private coordinator email>
APP_SEED_COORDINATOR_PASSWORD=<strong unique password>
```

`FRONTEND_URL` must exactly match the production Vercel origin without a trailing slash because the backend permits one CORS origin.
Render generates `JWT_SECRET` from `render.yaml`.
Flyway creates and upgrades the Supabase schema during backend startup.

## 5. Finish Vercel configuration

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

### Backend responds slowly once

Wait for the Render free service to wake.
Subsequent requests remain fast until it sleeps again.
