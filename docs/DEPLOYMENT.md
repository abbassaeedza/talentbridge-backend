# Cloud Run and Supabase Deployment

This guide deploys the TalentBridge Spring Boot API to Google Cloud Run.
Supabase supplies PostgreSQL and Storage.
Vercel serves the frontend.

## Architecture

```text
Vercel frontend
    |
Cloud Run Spring Boot API
    |-- Supabase PostgreSQL
    `-- Supabase Storage
```

## Google Cloud resources

The deployment uses these fixed resources:

```text
Project: project-4343c1b3-d768-4b8f-bff
Region: asia-south1
Cloud Run service: talentbridge-backend
Artifact Registry repository: talentbridge
Runtime service account: talentbridge-runtime@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com
GitHub deployer: github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com
```

Cloud Run uses one CPU and 1 GiB of memory.
The service keeps zero minimum instances and permits one maximum instance.
The service uses request-based CPU allocation and scales to zero when idle.

## Billing controls

The project has a monthly budget named `TalentBridge Cloud Run safety`.
The budget amount is `$1 USD`.
It sends alerts at `$0.01`, `$0.50`, `$0.90`, and `$1.00` of current spending.

A Google Cloud budget sends alerts but does not stop services or charges.
The one-instance limit reduces exposure but does not guarantee a zero bill.
Run this command to stop project billing and the backend:

```bash
gcloud billing projects unlink project-4343c1b3-d768-4b8f-bff
```

Any cost recorded before the unlink operation remains payable.

## Runtime variables

Cloud Run receives these values during the initial deployment:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
DB_SSL_MODE
JWT_SECRET
JWT_EXPIRATION
JWT_REFRESH_EXPIRATION
OPENAI_API_KEY
GITHUB_CLIENT_ID
GITHUB_CLIENT_SECRET
GITHUB_REDIRECT_URI
SUPABASE_URL
SUPABASE_SECRET_KEY
SUPABASE_STORAGE_BUCKET
RESEND_API_KEY
RESEND_FROM_EMAIL
FRONTEND_URL
APP_DEMO_MODE
APP_SEED_COORDINATOR_EMAIL
APP_SEED_COORDINATOR_PASSWORD
APP_SEED_DEMO_PASSWORD
APP_SEED_RESET_DEMO_DATA
PARTY_MIN_SIZE
PARTY_MAX_SIZE
```

Cloud Run injects `PORT=8080`.
The application gives `PORT` precedence over the local `SERVER_PORT` value.

Never commit `.env` or print its values in deployment logs.
Never expose backend secrets through Vite or Vercel variables.

## Supabase database

Use the Supabase Session pooler on port `5432`.
Use the pooler username that includes the Supabase project reference.
Set `DB_SSL_MODE=require`.
The backend limits each instance to five database connections so two overlapping Cloud Run revisions remain below the free Session pooler limit.

Flyway creates and updates the database schema during backend startup.

## Supabase Storage

Create a public bucket named `talentbridge-files`.
Set its file-size limit to at least `50 MB`.
Keep `SUPABASE_SECRET_KEY` on the backend only.

The backend sends the secret key through the `apikey` header.
The backend does not send the secret key through the `Authorization` header.
Public object URLs remain readable without authentication.

## Resend

Set `RESEND_API_KEY` and `RESEND_FROM_EMAIL` in the backend environment.
Use `TalentBridge <onboarding@resend.dev>` for initial tests.
The test sender can deliver only to the Resend account owner.

Verify a sending domain before public launch.
Use an address from that domain after verification.

## Demo fixtures and operator safety

`coordinator@talentbridge.com` remains the preserved operator/bootstrap identity, not a resettable demo account.
`APP_SEED_COORDINATOR_PASSWORD` is used only to create a missing operator; it does not overwrite existing credentials.
There is no fallback operator or demo password.
Changing `APP_SEED_COORDINATOR_EMAIL` does not rename the existing operator: it can create a second account.
If an operator rename is wanted later, review an explicit in-place migration to a fictional address such as `arden.hollis@example.test`, preserving its ID, role, and work.

`APP_DEMO_MODE=true` enables demo fixture maintenance, not anonymous authentication.
The dedicated demo coordinator is `rowan.hale@example.test` and the other 22 demo users also have fictional alphabetic emails at `example.test`.
Set `APP_SEED_DEMO_PASSWORD` through the server environment only and use normal password login.
Keep demo and operator passwords different and share neither through frontend configuration, source code, or logs.
`POST /api/auth/demo-login` has been removed.

Version 6 does not replace an existing dataset merely because it is deployed.
`APP_SEED_RESET_DEMO_DATA` defaults to `false`, including for a fresh database.
A version mismatch logs a warning and skips replacement without preventing existing users from logging in.
At version 6, startup synchronizes passwords for exact current fixture emails only, even when reset is disabled.
It preserves user IDs, profiles, work, and statuses.
Missing demo credentials skip maintenance rather than borrowing the operator password.

Before the one-time replacement:

1. Prepare and verify a restorable database backup, including relationships and all demo-owned custom work.
2. Review the exact legacy/current email allowlists in `DemoDataSeeder` against a read-only preflight.
3. Obtain approval to delete all work owned by those fixture accounts, including custom projects, applications, submissions, evaluations, and scorecards.
4. Stop normal user writes during the reset window; a pessimistic lock on the singleton settings row serializes seeders across instances, but does not lock every normal application write.
5. Only after those checks, supply the demo password and temporarily enable `APP_SEED_RESET_DEMO_DATA=true` with demo mode enabled.
6. Verify the resulting dataset and restore `APP_SEED_RESET_DEMO_DATA=false` before normal operation.

The flag records operator intent; the application cannot verify that a backup exists.
The reset deletes only exact allowlisted accounts and their exclusively demo-owned work, never an entire email domain or a party based on its name.
References to non-demo users or work block the entire reset before its first deletion and leave the seed version unchanged.
Operator approvals/evaluation triggers on demo work are permitted; the operator identity, credentials, unrelated notifications, and preferences remain intact.
Old exact fixture notifications addressed to the operator may be removed.
Cleanup and reseeding run in one transaction; database failures roll back the transaction.
The settings-row lock is acquired before reading the seed version, so a waiting instance sees the completed version and does not repeat a successful reset.
If the singleton settings row is missing, maintenance is skipped rather than running an unlocked reset.
Do not test this by starting the application against a production database.
Use isolated tests and a restored disposable database for deployment rehearsal.
The optional `DemoDataSeederDatabaseTest` accepts only an explicit loopback PostgreSQL URL in `STAGE_ONE_TEST_DB_URL`, with separate `STAGE_ONE_TEST_DB_USER` and `STAGE_ONE_TEST_DB_PASSWORD` environment values.
Run `./mvnw -Dtest=DemoDataSeederDatabaseTest test` against a pre-Stage-1 public-schema restore, never a production tunnel.
It exercises deletion and replacement with real foreign keys, then uses a temporary SQL trigger to reject the final version update through the Spring seeder proxy, without an outer test transaction.
It verifies every restored public-schema row and the seed marker remain unchanged and removes its trigger and function in `finally`.
Both database tests roll back their work; the normal offline test suite skips them unless the test URL is supplied.
This change does not revoke previously issued JWTs; assess existing demo sessions before enabling the new demo coordinator in a shared deployment.
Disable demo mode outside an intentionally isolated demo environment: the dedicated demo coordinator is still privileged, despite requiring a password.

## Initial deployment

Enable the required APIs:

```bash
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  --project=project-4343c1b3-d768-4b8f-bff
```

Build the image from the backend repository:

```bash
gcloud builds submit \
  --project=project-4343c1b3-d768-4b8f-bff \
  --region=asia-south1 \
  --tag=asia-south1-docker.pkg.dev/project-4343c1b3-d768-4b8f-bff/talentbridge/talentbridge-backend:initial \
  .
```

Deploy the image with the local environment file:

```bash
gcloud run deploy talentbridge-backend \
  --project=project-4343c1b3-d768-4b8f-bff \
  --region=asia-south1 \
  --image=asia-south1-docker.pkg.dev/project-4343c1b3-d768-4b8f-bff/talentbridge/talentbridge-backend:initial \
  --service-account=talentbridge-runtime@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --memory=1Gi \
  --cpu=1 \
  --min=0 \
  --max=1 \
  --concurrency=40 \
  --timeout=120 \
  --port=8080 \
  --cpu-throttling \
  --env-vars-file=.env
```

## Automatic deployment

The workflow at `.github/workflows/deploy-cloud-run.yml` runs after each push to `main`.
GitHub uses Workload Identity Federation to authenticate without a private key file.
The identity provider accepts only `abbassaeedza/talentbridge-backend`.
The deployer can write only to the TalentBridge Artifact Registry repository, deploy only this Cloud Run service, and act only as the dedicated runtime service account.
Public access is configured once on the Cloud Run service and is not changed by each deployment.

The workflow builds the Docker image on GitHub.
It pushes the image to Artifact Registry.
It deploys a new Cloud Run revision with the same resource limits.
Existing Cloud Run environment variables remain unchanged.

## Frontend connection

Copy the generated Cloud Run URL into the Vercel production variable:

```text
VITE_API_URL=https://talentbridge-backend-<generated-id>.asia-south1.run.app
```

Redeploy the Vercel frontend after this change.
Keep `FRONTEND_URL` equal to the exact Vercel production origin without a trailing slash.

## Verification

1. Confirm that the Cloud Run revision becomes ready.
2. Confirm that Flyway completes in the Cloud Run logs.
3. Call `GET /api/projects?page=0&size=1` through the Cloud Run URL.
4. Log in through the Vercel frontend.
5. Upload a small document and open its Supabase URL.
6. Complete GitHub OAuth and confirm the linked username.
7. Trigger an in-app notification and confirm the matching Resend event.

## Troubleshooting

### The service does not start

Check the revision logs for database or Flyway errors.
Confirm that the Supabase pooler values and `DB_SSL_MODE=require` are correct.

### The browser reports a CORS error

Set `FRONTEND_URL` to the exact Vercel production origin.
Deploy a new revision after the value changes.

### An upload fails

Confirm that the bucket is public and named `talentbridge-files`.
Confirm that the Supabase URL and secret key belong to the same project.

### The first request is slow

Cloud Run starts a new instance after the service scales to zero.
Later requests remain fast while the instance stays active.

### Startup finishes before fixture maintenance

Use the HTTP startup probe at `/api/health/startup`, not a TCP-only probe.
The endpoint returns `503` until Spring startup runners and their transactions finish, then returns `200` without exposing application data.
The deployment workflow allows up to 240 seconds for this initialization while keeping zero minimum instances and request-based CPU allocation.
Check the fixture-maintenance completion log and the database seed-version marker before treating a reset as successful.
