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
Both service-level and revision-level scaling use zero minimum instances and one maximum instance.
The service uses request-based CPU allocation and scales to zero when idle.
Startup CPU boost is enabled, concurrency is 40, and the request timeout is 120 seconds.
No warm-instance floor or keepalive traffic is configured.

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

Cloud Run receives the following runtime variables.
Sensitive values use Secret Manager references, not literal environment values.

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

### Secret Manager

The numeric secret versions in [deploy/secret-pins.json](../deploy/secret-pins.json) are the production credential references.
The manifest contains resource names and version IDs only, never secret contents.
Database credentials, signing material, provider keys, bootstrap credentials, and both n8n webhook values stay server-side.
Public URLs, client IDs, timeouts, bucket names, and other non-secret configuration remain normal environment values.
Do not create placeholder secrets for disabled integrations.

Grant `roles/secretmanager.secretAccessor` to the runtime account on each required secret, not on the whole project.
Pass secret contents to `gcloud secrets versions add --data-file=-` through private standard input; never put them in command arguments or logs.
Deploy numeric versions, not `latest`, so a new version cannot silently change a cold-started instance.
Do not overwrite production configuration with a local `.env` file.

For rotation, create a new version, update the manifest and runtime reference, invoke the candidate's HTTP readiness endpoint, and verify normal login before routing traffic.
Verify the exact candidate image digest and preserve all unrelated configuration.
JWT signing-key rotation requires a new sign-in on every device.
Demo-password rotation changes only exact managed identities on startup and revokes their prior token versions.
Operator bootstrap-password rotation does not change an existing operator's database password.
Keep `APP_SEED_RESET_DEMO_DATA=false` throughout ordinary deployments and credential rotation.
Old revisions may retain previous environment values in their metadata; restrict Cloud Run configuration access and never route traffic back to obsolete authentication code.

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
Use synthetic fixtures in an explicitly named disposable database for deployment rehearsal.
Run `bash ./mvnw test` for offline unit tests.
Run `bash ./mvnw -Pdatabase-tests verify` with `TALENTBRIDGE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:PORT/talentbridge_test`, `TALENTBRIDGE_TEST_DB_USER`, and `TALENTBRIDGE_TEST_DB_PASSWORD` for the separate PostgreSQL suite.
The database guard rejects remote hosts, other database names, query parameters, and the frozen backup restore.
Flyway applies the current migrations before synthetic legacy fixtures exercise replacement, late-failure rollback, and password-change races.
The integration suite deliberately resets its disposable data, so never point it at a production tunnel or backup database.

### Token and password security

Access tokens contain `type=access`; refresh tokens contain `type=refresh`.
Both contain `ver`, which must match the user's stored credential version.
Deploying this token format invalidates old sessions and requires users to sign in again.
Missing or invalid authentication returns `401`; authenticated permission failures return `403`.
Password changes require authentication and the current password, update the password and version atomically, and invalidate every old access and refresh token.
Exact managed demo accounts cannot change their individual passwords while demo mode is enabled.
Rotate their shared credential through its configured secret instead.
Startup checks BCrypt matches before updating a demo password; an unchanged password does not revoke sessions on each cold start.
The separate database profile exercises deletion and replacement with real foreign keys and a temporary SQL trigger that rejects the final version update through the Spring seeder proxy.
It verifies rollback of every synthetic application row and removes its temporary trigger and function in `finally`.
Fixture preparation deliberately resets the named disposable database; the offline unit suite does not run these integration tests.
Legacy JWTs are rejected by the required type/version claims, and password changes revoke prior versioned tokens.
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

Deploy the image while preserving the configured environment and numeric secret references:

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
  --min-instances=0 \
  --max-instances=1 \
  --concurrency=40 \
  --timeout=120 \
  --port=8080 \
  --cpu-throttling \
  --cpu-boost \
  --startup-probe=httpGet.path=/api/health/startup,httpGet.port=8080,periodSeconds=5,timeoutSeconds=5,failureThreshold=48 \
  --no-traffic
```

For a new service, configure non-secret variables and the manifest's secret references before testing startup.
An image deployment alone is not permission to replace runtime credentials.
Test the tagged candidate, then route explicitly to that verified revision.

## Automatic deployment

The workflow at `.github/workflows/deploy-cloud-run.yml` checks pull requests targeting `main`, pushes to `main`, and manual runs.
The stable required-check names are `Backend unit tests` and `Backend PostgreSQL tests`.
The first runs the offline Maven unit suite and dependency-free release-helper tests on Node 24.
The second runs `bash ./mvnw -B -Pdatabase-tests verify` against a fresh PostgreSQL 16 service with synthetic credentials and the loopback-only `talentbridge_test` database.
This profile also reruns units; neither check receives production credentials, backups, or OIDC permissions.

Register these checks before requiring both on an up-to-date pull request to `main`.
Branch protection must apply to administrators, forbid force pushes and deletion, and require no additional human approvals.
The main operator owns branch protection, WIF configuration, failing-PR proof, and rollouts; editing this workflow does not configure those external controls.

`Deploy production` depends on both checks and runs only for a `main` push or a manual run on `main`.
Its fixed production concurrency group never cancels an active deployment.
Its first step compares the current remote `main` SHA with `GITHUB_SHA` before checkout or Google authentication.
A superseded queued source skips all deployment steps without failing the checks.
The remaining steps check out exactly `GITHUB_SHA` and grant `id-token: write` only to this deployment job.

GitHub uses Workload Identity Federation with `google-github-actions/auth@v3` and `setup-gcloud@v3`, without a private key file.
The provider's verified trust condition requires both `assertion.repository == 'abbassaeedza/talentbridge-backend'` and `assertion.ref == 'refs/heads/main'`.
Repository-only trust is insufficient: a feature-branch workflow could otherwise request production credentials.
The deployer can write only to the TalentBridge Artifact Registry repository, deploy only this Cloud Run service, and act only as the dedicated runtime service account.
Public access is configured once on the Cloud Run service and is not changed by each deployment.

`scripts/release.mjs` builds and pushes the exact checked-out source under its full Git SHA for `linux/amd64`.
It compares the build's digest with the registry's SHA-tag resolution, resolves an OCI index or Docker manifest list to its unique Linux AMD64 image child, and deploys the immutable digest.
Each candidate has a unique Git/run/attempt revision suffix, a temporary tag, and no production traffic.
Before candidate startup, the helper checks all eleven exact numeric secret references, absence of sensitive plaintext environment values, the runtime account, port 8080, HTTP startup probe, resource settings, and `APP_SEED_RESET_DEMO_DATA=false`.
The Cloud Run v1 secret-alias annotation must contain exactly the eleven manifest names mapped to the same secret names in project `1089736501943` or `project-4343c1b3-d768-4b8f-bff`, with no missing, extra, or duplicate aliases.
It leaves unrelated non-secret configuration and secret references unchanged; it does not read secret payloads.
The deployment explicitly sets `--min=0 --max=1 --min-instances=0 --max-instances=1 --cpu-boost` while preserving the resource and HTTP startup settings.
The preflight retains its existing secret and resource checks and permits a legacy revision maximum of twenty, so the deployment can repair it.
Desired scaling and boost checks run only after deployment, before any candidate receives production traffic, and again after promotion.
Both candidate and promotion checks require service `metadata.annotations` keys `run.googleapis.com/minScale` and `run.googleapis.com/maxScale` to mean zero and one.
They independently require `autoscaling.knative.dev/minScale` and `autoscaling.knative.dev/maxScale` to mean zero and one on both the service's revision template and the actual revision.
An absent minimum annotation means zero; each maximum must explicitly be `'1'`.
Both revision annotation sets must explicitly contain `run.googleapis.com/startup-cpu-boost: 'true'`.
The protected main-branch pipeline applies this configuration; no separate manual rollout is required.

The helper verifies candidate image/source metadata and unchanged environment configuration, then invokes the tagged `/api/health/startup` until an actual HTTP `200` is observed within a bounded five-minute window.
Deployment-command success or an uninvoked revision is not sufficient.
Only that verified revision is explicitly promoted to `100%`.
After promotion, the helper checks reconciled traffic, source/image identity, secret pins, runtime settings, and preserved environment configuration again.
It removes only its own temporary tag, including after a failed candidate check; other tags are not removed.
A failed pre-promotion check leaves existing production traffic in place.
A post-promotion failure fails the release for operator investigation; it does not automatically roll back to an obsolete image or credential configuration.

Command output and runtime metadata are privately captured, never uploaded as artifacts or printed in full.
Release logs contain fixed status/error codes, verified revision names, and image digests only.
The Docker context excludes Git metadata, local environment files, build outputs, uploads, worktrees, scratch files, and generated Google authentication files.
Run `node --test scripts/*.test.mjs` locally to check image, configuration, routing, and workflow guards without contacting production.

Frontend protection is separate.
Required PR checks gate merging to its `main` branch.
Vercel native GitHub deployment remains enabled with build command `npm test && npm run build`.
That build command gates publishing; Vercel does not wait for post-merge GitHub Actions checks.

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
Observe natural idle through Cloud Run metrics, not periodic HTTP requests that keep the service active.
Zero minimum instances avoid a configured warm-instance floor, but cold starts and other billable usage remain possible.

### Startup finishes before fixture maintenance

Use the HTTP startup probe at `/api/health/startup`, not a TCP-only probe.
The endpoint returns `503` until Spring startup runners and their transactions finish, then returns `200` without exposing application data.
The deployment workflow allows up to 240 seconds for this initialization while keeping zero minimum instances and request-based CPU allocation.
Check the fixture-maintenance completion log and the database seed-version marker before treating a reset as successful.
