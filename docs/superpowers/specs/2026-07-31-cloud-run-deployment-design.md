# Cloud Run Deployment Design

## Goal

Deploy the TalentBridge backend to Google Cloud Run for public demonstration use.
Keep expected Cloud Run usage inside the monthly free tier.

## Architecture

Cloud Build builds the existing Dockerfile.
Artifact Registry stores the container image.
Cloud Run runs the backend in the Mumbai region.
Supabase continues to supply PostgreSQL and file storage.
Vercel continues to serve the frontend.

## Cloud Run configuration

- Use project `project-4343c1b3-d768-4b8f-bff`.
- Use region `asia-south1`.
- Use one CPU and 1 GiB of memory.
- Set the minimum instance count to zero.
- Set the maximum instance count to one.
- Allow public HTTPS requests.
- Use request-based billing.
- Use port `8080`.
- Set request concurrency to 40.
- Set the request timeout to 120 seconds.

## Runtime configuration

Use the existing local backend environment values for the first deployment.
Do not print, commit, or copy secret values into documentation.
Set `SERVER_PORT` to `8080` in Cloud Run.
Set `FRONTEND_URL` to the production Vercel origin.

## Continuous deployment

GitHub Actions deploys the backend after each push to `main`.
Workload Identity Federation authenticates GitHub without a service-account key file.
The deployment workflow retains the Cloud Run resource limits.

## Billing controls

Create a project-scoped billing budget with early email alerts.
Set the Cloud Run maximum instance count to one.
Keep the minimum instance count at zero so idle service instances stop.
Google Cloud budgets do not stop spending.
Disabling project billing is the only direct stop control, and it stops the backend.

## Security

Keep database, JWT, GitHub, OpenAI, Supabase, Resend, and demo credentials server-side.
Do not store secrets in GitHub workflow files.
Do not create or download a service-account JSON key.

## Verification

Run the complete backend test suite before deployment.
Verify the Cloud Run service settings after deployment.
Call a public API endpoint through the generated Cloud Run HTTPS URL.
Confirm that Cloud Run reports at most one instance and zero minimum instances.
