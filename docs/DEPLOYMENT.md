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
LOCAL_STORAGE_PATH
SUPABASE_URL
SUPABASE_SERVICE_ROLE_KEY
SUPABASE_STORAGE_BUCKET
RESEND_API_KEY
RESEND_FROM_EMAIL
FRONTEND_URL
APP_DEMO_MODE
APP_SEED_COORDINATOR_EMAIL
APP_SEED_COORDINATOR_PASSWORD
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

Flyway creates and updates the database schema during backend startup.

## Supabase Storage

Create a public bucket named `talentbridge-files`.
Set its file-size limit to at least `50 MB`.
Keep `SUPABASE_SERVICE_ROLE_KEY` on the backend only.

The backend authenticates uploads with the service-role key.
Public object URLs remain readable without authentication.

## Resend

Set `RESEND_API_KEY` and `RESEND_FROM_EMAIL` in the backend environment.
Use `TalentBridge <onboarding@resend.dev>` for initial tests.
The test sender can deliver only to the Resend account owner.

Verify a sending domain before public launch.
Use an address from that domain after verification.

## Demo coordinator

Set `APP_DEMO_MODE=true` during product testing.
Set the public demo email and password through the seed variables.

Each backend startup synchronizes the demo coordinator password while demo mode is active.
The frontend demo-login request never receives or stores the password.

Set `APP_DEMO_MODE=false` before public launch.
Change the coordinator password after demo mode is disabled.

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
Confirm that the Supabase URL and service-role key belong to the same project.

### The first request is slow

Cloud Run starts a new instance after the service scales to zero.
Later requests remain fast while the instance stays active.
