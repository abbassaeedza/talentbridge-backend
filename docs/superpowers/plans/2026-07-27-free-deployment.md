# Free Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TalentBridge deployable on Vercel, Render, and Supabase free tiers.

**Architecture:** Vercel serves the React SPA, Render runs the existing Dockerized Spring Boot API, and Supabase provides PostgreSQL plus public object storage.
Local development retains filesystem uploads when Supabase credentials are absent.

**Tech Stack:** Vite, React, Node test runner, Spring Boot 3.2, Java 17 `HttpClient`, PostgreSQL, Docker, Vercel, Render, Supabase

## Global Constraints

- Keep frontend and backend in separate GitHub repositories.
- Add no Java or JavaScript test dependency.
- Keep secrets out of committed files.
- Use TDD for storage and OAuth behavior.
- Preserve local development defaults.

---

### Task 1: Supabase upload storage

**Files:**

- Test: `src/test/java/com/talentbridge/service/FileStorageServiceTest.java`
- Modify: `src/main/java/com/talentbridge/service/FileStorageService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`

**Interfaces:**

- Consumes: `MultipartFile`, `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_STORAGE_BUCKET`
- Produces: `FileStorageService.upload(MultipartFile, String)` returning a public Supabase URL in production or `/files/...` locally

- [ ] **Step 1: Write failing Supabase upload test**

Create an HTTP test server that asserts `apikey`, `Authorization`, content type, object path, and body.
Assert the service returns `/storage/v1/object/public/talentbridge-files/...`.

- [ ] **Step 2: Verify test fails**

Run `./mvnw -Dtest=FileStorageServiceTest test`.
Expected failure: current service returns a local `/files/...` URL and never calls the HTTP server.

- [ ] **Step 3: Implement minimal upload switch**

Use local storage when Supabase URL or secret is blank.
Otherwise send the file bytes to `POST {SUPABASE_URL}/storage/v1/object/{bucket}/{key}` with required headers and return its public URL.

- [ ] **Step 4: Verify backend**

Run `./mvnw test`.
Expected result: all tests pass.

### Task 2: GitHub OAuth production callback

**Files:**

- Create: `src/utils/githubOAuth.ts` in frontend repository
- Test: `src/utils/githubOAuth.test.ts` in frontend repository
- Modify: `src/pages/student/StudentDashboard.tsx` in frontend repository
- Modify: `src/App.tsx` in frontend repository
- Modify: `package.json` in frontend repository

**Interfaces:**

- Produces: `createGitHubOAuthRequest(clientId, redirectUri, state)` returning `{ url, state }`
- Consumes: callback `code` and `state`, then calls existing `userApi.githubCallback(code)`

- [ ] **Step 1: Write failing OAuth helper tests**

Assert generated URL contains client ID, redirect URI, scopes, and state.
Assert returned state equals the supplied state.

- [ ] **Step 2: Verify tests fail**

Run `npm test`.
Expected failure: `githubOAuth.ts` does not exist.

- [ ] **Step 3: Implement helper and callback route**

Store generated state in session storage before redirecting to GitHub.
Reject missing or mismatched callback state.
Exchange valid code through the authenticated backend endpoint, refresh the user, and return to the student dashboard.

- [ ] **Step 4: Verify frontend**

Run `npm test && npm run build`.
Expected result: tests and production build pass.

### Task 3: Platform configuration and durable guides

**Files:**

- Create: `vercel.json` in frontend repository
- Create: `docs/DEPLOYMENT.md` in frontend repository
- Create: `render.yaml` in backend repository
- Create: `docs/DEPLOYMENT.md` in backend repository
- Modify: both `README.md` files

**Interfaces:**

- Vercel build output: `dist`
- Render HTTP port: `10000`
- Supabase bucket: `talentbridge-files`

- [ ] **Step 1: Add Vercel SPA rewrite**

Rewrite every browser route to `/index.html`.

- [ ] **Step 2: Add Render Blueprint**

Define one free Docker web service and prompt for every secret through `sync: false`.

- [ ] **Step 3: Document dashboard setup**

Record exact Supabase SQL, bucket, Vercel variables, Render variables, GitHub OAuth URLs, deployment order, and smoke checks.

- [ ] **Step 4: Verify and publish**

Run full frontend and backend checks, inspect diffs, commit each repository, and push both `main` branches.
