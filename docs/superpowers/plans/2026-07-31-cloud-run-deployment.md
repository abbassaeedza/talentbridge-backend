# Cloud Run Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the TalentBridge backend to a resource-capped Cloud Run service with automatic GitHub deployment.

**Architecture:** Cloud Build builds the existing Dockerfile and stores the image in Artifact Registry.
Cloud Run runs one scale-to-zero service, and GitHub Actions authenticates through Workload Identity Federation.

**Tech Stack:** Java 17, Spring Boot 3.2, Docker, Cloud Build, Artifact Registry, Cloud Run, GitHub Actions, and Google Cloud IAM.

## Global Constraints

- Use Google Cloud project `project-4343c1b3-d768-4b8f-bff`.
- Use Google Cloud project number `1089736501943`.
- Use billing account `01B4E1-FA83B8-61C325`.
- Use region `asia-south1`.
- Use one CPU and 1 GiB of memory.
- Use zero minimum instances and one maximum instance.
- Never print, commit, or download secret values.
- Keep Supabase as the database and file storage provider.
- Keep Vercel as the frontend provider.

---

### Task 1: Add billing alerts

**Files:**

- No repository files change.

**Interfaces:**

- Consumes: Google Cloud billing account `01B4E1-FA83B8-61C325`.
- Produces: A project-scoped monthly budget named `TalentBridge Cloud Run safety`.

- [ ] **Step 1: Enable the budget API**

```bash
gcloud services enable billingbudgets.googleapis.com \
  --project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 2: Check for an existing budget**

```bash
gcloud billing budgets list \
  --billing-account=01B4E1-FA83B8-61C325 \
  --billing-project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 3: Create the budget when it does not exist**

```bash
gcloud billing budgets create \
  --billing-account=01B4E1-FA83B8-61C325 \
  --billing-project=project-4343c1b3-d768-4b8f-bff \
  --display-name="TalentBridge Cloud Run safety" \
  --budget-amount=1USD \
  --calendar-period=month \
  --filter-projects=projects/1089736501943 \
  --threshold-rule=percent=0.01 \
  --threshold-rule=percent=0.50 \
  --threshold-rule=percent=0.90 \
  --threshold-rule=percent=1.00
```

- [ ] **Step 4: Verify the budget**

```bash
gcloud billing budgets list \
  --billing-account=01B4E1-FA83B8-61C325 \
  --billing-project=project-4343c1b3-d768-4b8f-bff \
  --format='table(displayName,amount.specifiedAmount.units,thresholdRules.thresholdPercent)'
```

Expected: The output contains one `TalentBridge Cloud Run safety` budget with a `1 USD` amount.

---

### Task 2: Prepare the backend for Cloud Run

**Files:**

- Modify: `src/main/resources/application.yml`
- Modify: `docs/DEPLOYMENT.md`
- Create: `.github/workflows/deploy-cloud-run.yml`

**Interfaces:**

- Consumes: Cloud Run's injected `PORT` value and the existing backend environment variables.
- Produces: A Cloud Run-compatible server port and an automatic deployment workflow.

- [ ] **Step 1: Make Cloud Run's port take precedence**

Change the server port configuration to:

```yaml
server:
  port: ${PORT:${SERVER_PORT:8080}}
```

- [ ] **Step 2: Add the deployment workflow**

Create `.github/workflows/deploy-cloud-run.yml` with this content:

```yaml
name: Deploy to Cloud Run

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  id-token: write

env:
  PROJECT_ID: project-4343c1b3-d768-4b8f-bff
  REGION: asia-south1
  REPOSITORY: talentbridge
  SERVICE: talentbridge-backend

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: projects/1089736501943/locations/global/workloadIdentityPools/github/providers/talentbridge-backend
          service_account: github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com

      - uses: google-github-actions/setup-gcloud@v3

      - name: Build and push image
        run: |
          gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet
          docker build --tag "$REGION-docker.pkg.dev/$PROJECT_ID/$REPOSITORY/$SERVICE:$GITHUB_SHA" .
          docker push "$REGION-docker.pkg.dev/$PROJECT_ID/$REPOSITORY/$SERVICE:$GITHUB_SHA"

      - uses: google-github-actions/deploy-cloudrun@v3
        with:
          service: talentbridge-backend
          region: asia-south1
          image: asia-south1-docker.pkg.dev/project-4343c1b3-d768-4b8f-bff/talentbridge/talentbridge-backend:${{ github.sha }}
          flags: >-
            --service-account=talentbridge-runtime@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com
            --memory=1Gi
            --cpu=1
            --min=0
            --max=1
            --concurrency=40
            --timeout=120
            --port=8080
            --cpu-throttling
```

- [ ] **Step 3: Replace the legacy deployment guide**

Document Cloud Run, Artifact Registry, Workload Identity Federation, runtime variables, billing alerts, verification, and rollback.
State that a budget sends alerts and does not enforce a spending cap.

- [ ] **Step 4: Run the backend tests**

```bash
./mvnw test
```

Expected: Maven exits with code zero and reports no test failures.

- [ ] **Step 5: Commit the backend changes**

```bash
git add src/main/resources/application.yml docs/DEPLOYMENT.md .github/workflows/deploy-cloud-run.yml
git commit -m "ci: deploy backend to Cloud Run"
```

---

### Task 3: Create Google Cloud deployment resources

**Files:**

- No repository files change.

**Interfaces:**

- Consumes: Google Cloud project ownership and the GitHub repository identity.
- Produces: Enabled APIs, an Artifact Registry repository, and a GitHub deployment identity.

- [ ] **Step 1: Enable deployment APIs**

```bash
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  --project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 2: Create the Docker repository**

Check the repository:

```bash
gcloud artifacts repositories describe talentbridge \
  --location=asia-south1 \
  --project=project-4343c1b3-d768-4b8f-bff
```

If the command reports `NOT_FOUND`, create the repository:

```bash
gcloud artifacts repositories create talentbridge \
  --repository-format=docker \
  --location=asia-south1 \
  --description="TalentBridge container images" \
  --project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 3: Create the GitHub deployment service account**

Check the service account:

```bash
gcloud iam service-accounts describe \
  github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --project=project-4343c1b3-d768-4b8f-bff
```

If the command reports `NOT_FOUND`, create the service account:

```bash
gcloud iam service-accounts create github-cloud-run \
  --display-name="GitHub Cloud Run deployer" \
  --project=project-4343c1b3-d768-4b8f-bff
```

Create a separate runtime service account:

```bash
gcloud iam service-accounts create talentbridge-runtime \
  --display-name="TalentBridge Cloud Run runtime" \
  --project=project-4343c1b3-d768-4b8f-bff
```

Grant the deployer write access only to the TalentBridge image repository:

```bash
gcloud artifacts repositories add-iam-policy-binding talentbridge \
  --location=asia-south1 \
  --member=serviceAccount:github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --role=roles/artifactregistry.writer \
  --project=project-4343c1b3-d768-4b8f-bff
```

Allow the deployer to act only as the runtime service account:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  talentbridge-runtime@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --member=serviceAccount:github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --role=roles/iam.serviceAccountUser \
  --project=project-4343c1b3-d768-4b8f-bff
```

After the Cloud Run service exists, grant deploy access only to that service:

```bash
gcloud run services add-iam-policy-binding talentbridge-backend \
  --region=asia-south1 \
  --member=serviceAccount:github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --role=roles/run.developer \
  --project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 4: Create Workload Identity Federation**

Create the workload identity pool if `gcloud iam workload-identity-pools describe github --location=global` reports `NOT_FOUND`:

```bash
gcloud iam workload-identity-pools create github \
  --location=global \
  --display-name="GitHub Actions" \
  --project=project-4343c1b3-d768-4b8f-bff
```

Create the provider if its describe command reports `NOT_FOUND`:

```bash
gcloud iam workload-identity-pools providers create-oidc talentbridge-backend \
  --location=global \
  --workload-identity-pool=github \
  --display-name="TalentBridge backend" \
  --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository == 'abbassaeedza/talentbridge-backend'" \
  --project=project-4343c1b3-d768-4b8f-bff
```

Grant repository impersonation:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  github-cloud-run@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/1089736501943/locations/global/workloadIdentityPools/github/attribute.repository/abbassaeedza/talentbridge-backend" \
  --project=project-4343c1b3-d768-4b8f-bff
```

- [ ] **Step 5: Verify the deployment identity**

Describe the provider and service-account IAM policy.
Expected: Only the TalentBridge backend repository has Workload Identity User access.

---

### Task 4: Deploy the backend

**Files:**

- Read without modification: `.env`

**Interfaces:**

- Consumes: The existing local `.env` values and the backend Dockerfile.
- Produces: The public `talentbridge-backend` Cloud Run service.

- [ ] **Step 1: Build the initial image**

```bash
gcloud builds submit \
  --project=project-4343c1b3-d768-4b8f-bff \
  --region=asia-south1 \
  --tag=asia-south1-docker.pkg.dev/project-4343c1b3-d768-4b8f-bff/talentbridge/talentbridge-backend:initial \
  .
```

- [ ] **Step 2: Deploy with the local environment file**

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

- [ ] **Step 3: Verify the service configuration**

```bash
gcloud run services describe talentbridge-backend \
  --project=project-4343c1b3-d768-4b8f-bff \
  --region=asia-south1 \
  --format='yaml(status.url,spec.template.metadata.annotations,spec.template.spec.containerConcurrency,spec.template.spec.timeoutSeconds,spec.template.spec.containers[0].ports,spec.template.spec.containers[0].resources.limits,spec.template.spec.containers[0].env.name)'
```

Do not describe complete environment entries because they contain secret values.

- [ ] **Step 4: Verify the public API**

Call `GET /api/projects?page=0&size=1` through the Cloud Run URL.
Expected: The service returns an HTTP response without a platform error.

---

### Task 5: Publish and verify continuous deployment

**Files:**

- No additional repository files change.

**Interfaces:**

- Consumes: The committed workflow and Workload Identity Federation provider.
- Produces: Automatic deployment after each push to `main`.

- [ ] **Step 1: Push the backend commits**

```bash
git push origin main
```

- [ ] **Step 2: Inspect the GitHub Actions run**

```bash
gh run list --workflow=deploy-cloud-run.yml --limit=1
```

Expected: The latest workflow run completes successfully.

- [ ] **Step 3: Recheck Cloud Run**

Describe the active Cloud Run revision and call the public API again.
Expected: The GitHub-deployed revision serves requests with the same resource limits.
