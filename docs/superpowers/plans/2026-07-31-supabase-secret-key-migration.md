# Supabase Secret Key Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy Supabase `service_role` JWT with the new server-only secret key.

**Architecture:** The backend sends `SUPABASE_SECRET_KEY` only through the Storage `apikey` header.
Cloud Run receives the new key before the code deployment.
The final environment update removes the legacy variable.

**Tech Stack:** Java 17, Spring Boot 3.2, Java HTTP Client, JUnit 5, Cloud Run, and Supabase Storage.

## Global Constraints

- Use `SUPABASE_SECRET_KEY` for the new `sb_secret_...` value.
- Do not send the secret key through the `Authorization` header.
- Do not add a legacy key fallback.
- Do not print any environment value.
- Use Ponytail full mode for code.
- Use STE-flavored writing for Markdown.

---

### Task 1: Change the Storage authentication header

**Files:**

- Modify: `src/test/java/com/talentbridge/service/FileStorageServiceTest.java`
- Modify: `src/main/java/com/talentbridge/service/FileStorageService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`

**Interfaces:**

- Consumes: `SUPABASE_SECRET_KEY` from the backend environment.
- Produces: Storage requests with an `apikey` header and no `Authorization` header.

- [ ] **Step 1: Write the failing regression test**

Change the authorization assertion in `FileStorageServiceTest`:

```java
assertNull(authorization.get());
```

- [ ] **Step 2: Run the test and verify the failure**

Run:

```bash
./mvnw -Dtest=FileStorageServiceTest test
```

Expected: The test fails because the request contains `Authorization: Bearer server-secret`.

- [ ] **Step 3: Make the minimum code change**

Rename the injected field and remove the bearer header:

```java
@Value("${supabase.secret-key:}") private String supabaseSecretKey;
```

```java
.header("apikey", supabaseSecretKey)
```

Update the test field name to `supabaseSecretKey`.
Map the Spring property to `${SUPABASE_SECRET_KEY:}`.
Replace the legacy variable in `.env.example`.

- [ ] **Step 4: Run the focused and complete tests**

Run:

```bash
./mvnw -Dtest=FileStorageServiceTest test
./mvnw test
```

Expected: All tests pass.

### Task 2: Remove legacy deployment references

**Files:**

- Modify: `README.md`
- Modify: `docs/DEPLOYMENT.md`
- Modify: Supabase references in prior design and plan documents.
- Delete: `render.yaml`

**Interfaces:**

- Consumes: The new variable name from Task 1.
- Produces: Documentation that uses the new secret key name only for current configuration.

- [ ] **Step 1: Replace current documentation references**

Use `SUPABASE_SECRET_KEY` in the README and Cloud Run guide.
State that the backend sends the key only through `apikey`.

- [ ] **Step 2: Update prior documentation**

Replace old operational instructions with the new variable name.
Keep historical comparisons in the approved migration design.

- [ ] **Step 3: Remove the Render blueprint**

Delete `render.yaml` because this repository deploys to Cloud Run.

- [ ] **Step 4: Verify the repository**

Run:

```bash
rg -n "SUPABASE_SERVICE_ROLE_KEY|supabase\.service-role-key|Authorization.*supabase" . --glob '!target/**' --glob '!.env'
git diff --check
```

Expected: Only the approved migration history can name the legacy variable.

### Task 3: Deploy without a key outage

**Files:**

- Read without modification: `.env`

**Interfaces:**

- Consumes: The new local environment values and the tested backend image.
- Produces: A ready Cloud Run revision with no legacy key variable.

- [ ] **Step 1: Add the new key to Cloud Run**

Read `SUPABASE_SECRET_KEY` from `.env` without printing it.
Add it to the current Cloud Run service without removing the legacy variable.

- [ ] **Step 2: Commit and push the code**

```bash
git add -A
git commit -m "fix(storage): use Supabase secret key"
git push origin main
```

- [ ] **Step 3: Verify the GitHub deployment**

Wait for the Cloud Run GitHub Actions workflow to finish.
Expected: The workflow completes successfully.

- [ ] **Step 4: Replace the Cloud Run environment**

Upload only the required values from the updated local `.env`.
This update removes `SUPABASE_SERVICE_ROLE_KEY` from the service.
Delete the temporary upload file immediately.

- [ ] **Step 5: Verify the live service**

Confirm that the latest revision is ready.
Confirm that demo login returns HTTP 200.
Confirm that Cloud Run has the new variable name and not the legacy variable name.
