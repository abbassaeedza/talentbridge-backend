# Demo Authentication, Resend, and Runtime Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide safe public-demo coordinator login, send every in-app notification through Resend, and align local and Render environment settings with Supabase deployment.

**Architecture:** The Spring Boot backend owns demo credentials and exposes a feature-gated demo-login endpoint that returns the existing `AuthResponse` without sending a password to Vite.
`NotificationService` remains the single notification entry point and delegates email delivery to a small Resend-backed service.
Vite calls the demo endpoint through the existing auth context, while Render owns all secret and runtime values.

**Tech Stack:** Java 17, Spring Boot 3.2.3, JUnit 5, Mockito, Resend Java SDK 4.13.0, React 18, Vite 5, TypeScript, Playwright, Render, Vercel, and Supabase.

## Global Constraints

- Keep the frontend and backend as independent Git repositories.
- Never expose `APP_SEED_COORDINATOR_PASSWORD`, `JWT_SECRET`, `RESEND_API_KEY`, or `SUPABASE_SECRET_KEY` to Vite.
- Enable public demo authentication only while `APP_DEMO_MODE=true`.
- Keep in-app notification persistence successful when Resend is unavailable.
- Send an email for every call to either existing `NotificationService.send(...)` overload.
- Use `PARTY_MIN_SIZE=2` and `PARTY_MAX_SIZE=3` as deploy defaults.
- Keep `.env` ignored and commit only `.env.example` documentation.
- Put every full sentence in Markdown on its own physical line.

---

## File Map

- `src/main/java/com/talentbridge/service/AuthService.java` owns normal and demo authentication behavior.
- `src/main/java/com/talentbridge/controller/AuthController.java` exposes the demo-login route.
- `src/main/java/com/talentbridge/config/DataSeeder.java` creates the coordinator and synchronizes its password only in demo mode.
- `src/main/java/com/talentbridge/service/EmailService.java` owns Resend delivery and non-fatal failure handling.
- `src/main/java/com/talentbridge/service/NotificationService.java` persists in-app notifications and delegates email delivery.
- `src/main/resources/application.yml` maps environment variables into Spring configuration.
- `render.yaml`, `.env`, `.env.example`, `README.md`, and `docs/DEPLOYMENT.md` document backend runtime configuration.
- `src/api/services.ts` defines the frontend demo-login API call.
- `src/contexts/AuthContext.tsx` applies both password-login and demo-login responses through one session path.
- `src/pages/auth/LoginPage.tsx` triggers demo login from the existing demo card.
- `e2e/login-demo.spec.ts` verifies the browser-visible demo-login flow.
- `playwright.config.ts`, `package.json`, and `package-lock.json` provide the smallest repeatable frontend E2E setup.
- Frontend `.env`, `.env.example`, `README.md`, and `docs/DEPLOYMENT.md` document public-only frontend variables and demo behavior.

---

### Task 1: Backend demo authentication and coordinator synchronization

**Files:**

- Create: `talentbridge-backend/src/test/java/com/talentbridge/service/AuthServiceDemoLoginTest.java`
- Create: `talentbridge-backend/src/test/java/com/talentbridge/config/DataSeederTest.java`
- Modify: `talentbridge-backend/src/main/java/com/talentbridge/service/AuthService.java`
- Modify: `talentbridge-backend/src/main/java/com/talentbridge/controller/AuthController.java`
- Modify: `talentbridge-backend/src/main/java/com/talentbridge/config/DataSeeder.java`
- Modify: `talentbridge-backend/src/main/resources/application.yml`

**Interfaces:**

- Produces: `AuthService.demoLogin(): AuthResponse`.
- Produces: `POST /api/auth/demo-login` with no request body.
- Consumes: `app.demo-mode`, `app.seed.coordinator-email`, and `app.seed.coordinator-password`.

- [ ] **Step 1: Write failing demo-login service tests**

Create `AuthServiceDemoLoginTest` with mocked repositories, token provider, password encoder, and notification service.
Set `demoMode`, `coordinatorEmail`, and `coordinatorPassword` through `ReflectionTestUtils`.
Verify enabled demo login loads the configured coordinator, validates the configured password through the existing login path, and returns its tokens.
Verify disabled demo login throws `ResourceNotFoundException` before querying the user repository.

```java
@Test
void logsInConfiguredCoordinatorWhenDemoModeIsEnabled() {
    ReflectionTestUtils.setField(service, "demoMode", true);
    ReflectionTestUtils.setField(service, "coordinatorEmail", "coordinator@talentbridge.com");
    ReflectionTestUtils.setField(service, "coordinatorPassword", "demo-password");
    when(userRepository.findByEmail("coordinator@talentbridge.com")).thenReturn(Optional.of(coordinator));
    when(passwordEncoder.matches("demo-password", coordinator.getPassword())).thenReturn(true);
    when(tokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access");
    when(tokenProvider.generateRefreshToken(any())).thenReturn("refresh");

    AuthResponse result = service.demoLogin();

    assertEquals("access", result.getAccessToken());
    verify(passwordEncoder).matches("demo-password", coordinator.getPassword());
}

@Test
void hidesDemoLoginWhenDemoModeIsDisabled() {
    ReflectionTestUtils.setField(service, "demoMode", false);

    assertThrows(ResourceNotFoundException.class, service::demoLogin);
    verifyNoInteractions(userRepository);
}
```

- [ ] **Step 2: Run the demo-login tests and confirm RED**

Run: `./mvnw -Dtest=AuthServiceDemoLoginTest test`
Expected: compilation fails because `AuthService.demoLogin()` does not exist.

- [ ] **Step 3: Write failing coordinator synchronization tests**

Create `DataSeederTest` with mocked `UserRepository` and `PasswordEncoder`.
Verify an existing coordinator receives a new encoded password and is saved when demo mode is enabled and the configured password differs.
Verify an existing coordinator is not changed when demo mode is disabled.

```java
@Test
void synchronizesExistingCoordinatorPasswordInDemoMode() {
    ReflectionTestUtils.setField(seeder, "demoMode", true);
    ReflectionTestUtils.setField(seeder, "coordinatorEmail", "coordinator@talentbridge.com");
    ReflectionTestUtils.setField(seeder, "coordinatorPassword", "new-demo-password");
    when(userRepository.findByEmail("coordinator@talentbridge.com")).thenReturn(Optional.of(coordinator));
    when(passwordEncoder.matches("new-demo-password", "old-hash")).thenReturn(false);
    when(passwordEncoder.encode("new-demo-password")).thenReturn("new-hash");

    seeder.seed();

    assertEquals("new-hash", coordinator.getPassword());
    verify(userRepository).save(coordinator);
}

@Test
void preservesExistingCoordinatorPasswordOutsideDemoMode() {
    ReflectionTestUtils.setField(seeder, "demoMode", false);
    when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(coordinator));

    seeder.seed();

    assertEquals("old-hash", coordinator.getPassword());
    verify(userRepository, never()).save(any());
}
```

- [ ] **Step 4: Run the coordinator synchronization tests and confirm RED**

Run: `./mvnw -Dtest=DataSeederTest test`
Expected: the synchronization test fails because the existing implementation skips every existing coordinator.

- [ ] **Step 5: Implement the smallest backend demo flow**

Add these environment mappings under `app` in `application.yml`:

```yaml
app:
  demo-mode: ${APP_DEMO_MODE:false}
  seed:
    coordinator-email: ${APP_SEED_COORDINATOR_EMAIL:coordinator@talentbridge.com}
    coordinator-password: ${APP_SEED_COORDINATOR_PASSWORD:Admin1234!}
```

Add the same three `@Value` fields to `AuthService` and implement `demoLogin()` by rejecting disabled mode and passing a populated `LoginRequest` into the existing `login(...)` method.
Add `@PostMapping("/demo-login")` to `AuthController` and return `authService.demoLogin()`.
Change `DataSeeder.seed()` to query by email once, synchronize the password only when demo mode is enabled and it differs, otherwise retain the existing account, and keep the current create-if-missing behavior.

- [ ] **Step 6: Run focused and full backend tests**

Run: `./mvnw -Dtest=AuthServiceDemoLoginTest,DataSeederTest test`
Expected: both test classes pass.

Run: `./mvnw test`
Expected: all backend tests pass.

- [ ] **Step 7: Commit the backend demo flow**

```bash
git add src/main/java/com/talentbridge/service/AuthService.java src/main/java/com/talentbridge/controller/AuthController.java src/main/java/com/talentbridge/config/DataSeeder.java src/main/resources/application.yml src/test/java/com/talentbridge/service/AuthServiceDemoLoginTest.java src/test/java/com/talentbridge/config/DataSeederTest.java
git commit -m "feat(auth): add gated demo login"
```

---

### Task 2: Resend delivery for every in-app notification

**Files:**

- Create: `talentbridge-backend/src/main/java/com/talentbridge/service/EmailService.java`
- Create: `talentbridge-backend/src/test/java/com/talentbridge/service/EmailServiceTest.java`
- Create: `talentbridge-backend/src/test/java/com/talentbridge/service/NotificationServiceTest.java`
- Modify: `talentbridge-backend/src/main/java/com/talentbridge/service/NotificationService.java`
- Modify: `talentbridge-backend/src/main/resources/application.yml`
- Modify: `talentbridge-backend/pom.xml`

**Interfaces:**

- Produces: `EmailService.send(String recipient, String subject, String message): void`.
- Consumes: `resend.api-key` from `RESEND_API_KEY` and `resend.from-email` from `RESEND_FROM_EMAIL`.
- Consumes: official Maven artifact `com.resend:resend-java:4.13.0`.

- [ ] **Step 1: Write failing notification delegation tests**

Create `NotificationServiceTest` with mocked `NotificationRepository`, `UserRepository`, and `EmailService`.
Verify each public `send(...)` overload saves one notification and calls `EmailService.send(...)` with the recipient email, title, and message.

```java
@Test
void emailsEveryBasicNotification() {
    service.send(recipient, NotificationType.GENERAL, "Title", "Message");

    verify(notificationRepository).save(any(Notification.class));
    verify(emailService).send("student@example.com", "Title", "Message");
}

@Test
void emailsEveryReferencedNotification() {
    service.send(recipient, NotificationType.PROJECT_ASSIGNED, "Assigned", "Message", "id", "PROJECT");

    verify(notificationRepository).save(any(Notification.class));
    verify(emailService).send("student@example.com", "Assigned", "Message");
}
```

- [ ] **Step 2: Run notification tests and confirm RED**

Run: `./mvnw -Dtest=NotificationServiceTest test`
Expected: compilation fails because `EmailService` does not exist and `NotificationService` has no email dependency.

- [ ] **Step 3: Write failing Resend service tests**

Create `EmailServiceTest` using mocked `Resend` and `Emails` SDK objects.
Use the package-private constructor `EmailService(Resend resend, String fromEmail)` for direct unit testing.
Verify configured delivery passes the exact sender, recipient, subject, and text to `resend.emails().send(...)`.
Verify a thrown SDK exception does not escape `EmailService.send(...)`.
Verify a missing API client or blank sender skips delivery.

- [ ] **Step 4: Run email service tests and confirm RED**

Run: `./mvnw -Dtest=EmailServiceTest test`
Expected: compilation fails because `EmailService` and the Resend dependency do not exist.

- [ ] **Step 5: Add the pinned Resend dependency and minimal email service**

Add this dependency to `pom.xml`:

```xml
<dependency>
    <groupId>com.resend</groupId>
    <artifactId>resend-java</artifactId>
    <version>4.13.0</version>
</dependency>
```

Map Resend values in `application.yml`:

```yaml
resend:
  api-key: ${RESEND_API_KEY:}
  from-email: ${RESEND_FROM_EMAIL:}
```

Implement one Spring `EmailService` that creates the Resend client only when an API key is present.
Build `CreateEmailOptions` with `from`, `to`, `subject`, and `text`.
Catch delivery exceptions and log the recipient and exception message without logging keys or message content.
Skip delivery with one warning when configuration is absent.

- [ ] **Step 6: Delegate both notification overloads to email**

Inject `EmailService` into `NotificationService`.
After each notification save, call `emailService.send(recipient.getEmail(), title, message)`.
Do not add calls in individual business services because every existing event already routes through these two methods.

- [ ] **Step 7: Run focused and full backend tests**

Run: `./mvnw -Dtest=EmailServiceTest,NotificationServiceTest test`
Expected: both test classes pass.

Run: `./mvnw test`
Expected: all backend tests pass.

- [ ] **Step 8: Commit Resend delivery**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/talentbridge/service/EmailService.java src/main/java/com/talentbridge/service/NotificationService.java src/test/java/com/talentbridge/service/EmailServiceTest.java src/test/java/com/talentbridge/service/NotificationServiceTest.java
git commit -m "feat(email): deliver notifications via Resend"
```

---

### Task 3: Backend runtime and deployment configuration

**Files:**

- Create: `talentbridge-backend/src/test/java/com/talentbridge/config/AppPropertiesTest.java`
- Modify, ignored: `talentbridge-backend/.env`
- Modify: `talentbridge-backend/.env.example`
- Modify: `talentbridge-backend/src/main/resources/application.yml`
- Modify: `talentbridge-backend/render.yaml`
- Modify: `talentbridge-backend/README.md`
- Modify: `talentbridge-backend/docs/DEPLOYMENT.md`

**Interfaces:**

- Produces: `PARTY_MIN_SIZE` and `PARTY_MAX_SIZE` mappings.
- Produces: explicit Render defaults for JWT expiry, party size, and demo mode.
- Produces: Render prompts for Resend secrets.

- [ ] **Step 1: Add a failing Spring property-binding test**

Create `src/test/java/com/talentbridge/config/AppPropertiesTest.java` with `ApplicationContextRunner`, `ConfigDataApplicationContextInitializer`, and `ConfigurationPropertiesAutoConfiguration`.
Load `AppProperties`, provide `PARTY_MIN_SIZE=4` and `PARTY_MAX_SIZE=6`, and assert the bound party limits are `4` and `6`.

```java
@Test
void bindsPartyLimitsFromEnvironment() {
    new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AppProperties.class)
            .withSystemProperties("PARTY_MIN_SIZE=4", "PARTY_MAX_SIZE=6")
            .run(context -> {
                AppProperties properties = context.getBean(AppProperties.class);
                assertEquals(4, properties.getParty().getMinSize());
                assertEquals(6, properties.getParty().getMaxSize());
            });
}
```

- [ ] **Step 2: Run the property test and confirm RED**

Run: `./mvnw -Dtest=AppPropertiesTest test`
Expected: it reports `2` and `3` because `application.yml` currently hardcodes both values.

- [ ] **Step 3: Map party limits from environment variables**

Change the current party block to:

```yaml
party:
  min-size: ${PARTY_MIN_SIZE:2}
  max-size: ${PARTY_MAX_SIZE:3}
```

- [ ] **Step 4: Update Render Blueprint variables**

Keep `JWT_SECRET` as `generateValue: true`.
Add these non-secret values:

```yaml
- key: JWT_EXPIRATION
  value: 86400000
- key: JWT_REFRESH_EXPIRATION
  value: 604800000
- key: PARTY_MIN_SIZE
  value: 2
- key: PARTY_MAX_SIZE
  value: 3
- key: APP_DEMO_MODE
  value: true
```

Add these dashboard-entered values:

```yaml
- key: RESEND_API_KEY
  sync: false
- key: RESEND_FROM_EMAIL
  sync: false
```

- [ ] **Step 5: Align local and example environment files**

Preserve existing local secret values unrelated to this migration.
Remove legacy `USE_LOCAL_STORAGE`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `AWS_REGION`, `AWS_S3_BUCKET`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, and `FROM_EMAIL` entries.
Add `DB_SSL_MODE`, Supabase Storage variables, Resend variables, `APP_DEMO_MODE`, coordinator seed credentials, and both party limits.
Use blank or descriptive placeholders in `.env.example` and never copy local secrets into the committed file.

- [ ] **Step 6: Update backend documentation**

Document Resend domain verification and `RESEND_FROM_EMAIL` formatting.
Document that every in-app notification attempts email delivery.
Document demo-mode password synchronization and the exact launch shutdown procedure.
Document which Render values are generated, fixed by the Blueprint, or entered manually.
Record the Context7-derived security rule that browser variables cannot contain backend secrets.

- [ ] **Step 7: Run property, test, and configuration checks**

Run: `./mvnw -Dtest=AppPropertiesTest test`
Expected: configured limits bind as `4` and `6`.

Run: `./mvnw test`
Expected: all backend tests pass.

Run: `git diff --check`
Expected: no whitespace errors.

- [ ] **Step 8: Commit backend deployment configuration**

```bash
git add .env.example src/main/resources/application.yml render.yaml README.md docs/DEPLOYMENT.md docs/superpowers/plans/2026-07-27-demo-auth-resend-config.md src/test/java/com/talentbridge/config/AppPropertiesTest.java
git commit -m "docs: configure demo deployment"
```

Do not stage the ignored `.env` file.

---

### Task 4: Frontend demo-login flow and browser verification

**Files:**

- Create: `talentbridge-frontend/playwright.config.ts`
- Create: `talentbridge-frontend/e2e/login-demo.spec.ts`
- Modify: `talentbridge-frontend/src/api/services.ts`
- Modify: `talentbridge-frontend/src/contexts/AuthContext.tsx`
- Modify: `talentbridge-frontend/src/pages/auth/LoginPage.tsx`
- Modify: `talentbridge-frontend/package.json`
- Modify: `talentbridge-frontend/package-lock.json`

**Interfaces:**

- Produces: `authApi.demoLogin(): Promise<AxiosResponse<AuthResponse>>`.
- Produces: `AuthContextType.demoLogin(): Promise<void>`.
- Consumes: `POST /api/auth/demo-login`.

- [ ] **Step 1: Add the single E2E dependency**

Run: `npm install --save-dev @playwright/test`
Add `test:e2e` as `playwright test` in `package.json`.
Create `playwright.config.ts` with `webServer.command = "npm run dev -- --host 127.0.0.1"`, `webServer.url = "http://127.0.0.1:3000"`, and Chromium as the only project.

- [ ] **Step 2: Write the failing browser test**

Intercept `POST **/api/auth/demo-login` and return an approved coordinator `AuthResponse`.
Intercept `GET **/api/users/me` and return the same coordinator user.
Open `/login`, click the `Coordinator` demo button, assert the demo endpoint receives a POST with no password body, and assert navigation reaches `/dashboard`.

```ts
test('demo card logs in without exposing a password', async ({ page }) => {
  const demoRequest = page.waitForRequest(
    (request) => request.url().endsWith('/api/auth/demo-login') && request.method() === 'POST',
  );

  await page.goto('/login');
  await page.getByRole('button', { name: /Coordinator/ }).click();

  expect((await demoRequest).postData()).toBeNull();
  await expect(page).toHaveURL(/\/dashboard$/);
});
```

- [ ] **Step 3: Run the browser test and confirm RED**

Run: `npx playwright install chromium`
Run: `npm run test:e2e`
Expected: the request wait times out because the card only fills hardcoded credentials.

- [ ] **Step 4: Implement shared auth-response handling**

Add `authApi.demoLogin()` that posts to `/api/auth/demo-login` without a body.
Extract the existing token storage, status redirects, and `refreshUser()` work in `AuthContext` into one internal `completeLogin(data: AuthResponse)` function.
Keep `login(email, password)` calling the normal endpoint and then `completeLogin`.
Add `demoLogin()` calling the demo endpoint and then `completeLogin`.

- [ ] **Step 5: Connect the demo card**

Remove the hardcoded password and the `fill(...)` helper from `LoginPage`.
Add a `handleDemoLogin()` function that uses the existing loading and toast behavior, calls `demoLogin()`, and navigates to `/dashboard`.
Keep the visible coordinator email as non-secret display text.
Disable the demo button while login is pending.

- [ ] **Step 6: Run frontend tests and build**

Run: `npm test`
Expected: existing Node tests pass.

Run: `npm run test:e2e`
Expected: the demo browser test passes.

Run: `npm run build`
Expected: TypeScript and Vite production build pass.

- [ ] **Step 7: Commit frontend demo login**

```bash
git add package.json package-lock.json playwright.config.ts e2e/login-demo.spec.ts src/api/services.ts src/contexts/AuthContext.tsx src/pages/auth/LoginPage.tsx
git commit -m "feat(auth): connect secure demo login"
```

---

### Task 5: Frontend environment and deployment documentation

**Files:**

- Inspect, ignored: `talentbridge-frontend/.env`
- Modify: `talentbridge-frontend/.env.example`
- Modify: `talentbridge-frontend/README.md`
- Modify: `talentbridge-frontend/docs/DEPLOYMENT.md`

**Interfaces:**

- Confirms: only `VITE_API_URL` and `VITE_GITHUB_CLIENT_ID` are required frontend environment variables.
- Confirms: demo credentials remain backend-only.

- [ ] **Step 1: Verify local frontend environment scope**

Keep `.env` limited to `VITE_API_URL` and `VITE_GITHUB_CLIENT_ID`.
Do not add a coordinator password or Resend value.

- [ ] **Step 2: Update frontend documentation**

Explain that the demo card calls the backend demo-login endpoint.
Explain that disabling `APP_DEMO_MODE` on Render makes the demo card fail closed.
State that every `VITE_*` value is public browser code and must never hold secrets.
Add the launch checklist item to remove the demo card when the backend demo mode is disabled.

- [ ] **Step 3: Verify and commit frontend documentation**

Run: `npm test`
Expected: existing frontend tests pass.

Run: `npm run test:e2e`
Expected: the demo browser test passes.

Run: `npm run build`
Expected: the production build passes.

Run: `git diff --check`
Expected: no whitespace errors.

```bash
git add .env.example README.md docs/DEPLOYMENT.md
git commit -m "docs: document demo deployment"
```

---

### Task 6: Final verification and publication

**Files:**

- Verify all changed files in both repositories.
- Push each `main` branch to its existing `origin`.

- [ ] **Step 1: Audit secret exposure and repository state**

Run `git status --short --branch` in each repository.
Run `git diff HEAD~3 -- . ':!package-lock.json'` in each repository and inspect every change.
Search tracked files for local secret values and confirm neither `.env` file is tracked.
Confirm that frontend tracked files contain no coordinator password, Resend key, Supabase secret key, or JWT secret.

- [ ] **Step 2: Run fresh backend verification**

Run: `./mvnw test`
Expected: all backend tests pass with zero failures.

Run: `./mvnw clean package -DskipTests`
Expected: the production JAR builds successfully.

- [ ] **Step 3: Run fresh frontend verification**

Run: `npm test`
Expected: all Node tests pass.

Run: `npm run test:e2e`
Expected: all Playwright tests pass in Chromium.

Run: `npm run build`
Expected: TypeScript and Vite production build complete successfully.

- [ ] **Step 4: Push both repositories**

```bash
git -C talentbridge-backend push origin main
git -C talentbridge-frontend push origin main
```

- [ ] **Step 5: Report Render variables**

List every new manual Render value: `RESEND_API_KEY` and `RESEND_FROM_EMAIL`.
List demo values that must be checked: `APP_DEMO_MODE`, `APP_SEED_COORDINATOR_EMAIL`, and `APP_SEED_COORDINATOR_PASSWORD`.
List Blueprint-managed values that require no manual secret entry: `JWT_SECRET`, `JWT_EXPIRATION`, `JWT_REFRESH_EXPIRATION`, `PARTY_MIN_SIZE`, and `PARTY_MAX_SIZE`.
Include the exact pre-launch step to set `APP_DEMO_MODE=false` and remove the frontend demo card.
