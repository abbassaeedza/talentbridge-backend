# TalentBridge Frontend Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove reload-dependent coordinator behavior, keep notifications inside the viewport, and surface safe chatbot errors.

**Architecture:** Existing TanStack Query keys retain one response contract and mutation success handlers invalidate the active query families.
The existing layout uses fixed viewport positioning and mutually exclusive menu state, while Playwright reproduces the user-visible failures through mocked API boundaries.

**Tech Stack:** React 18, TypeScript 5.5, Vite 5, TanStack Query 5, React Router 6, Tailwind CSS 3, Axios, and Playwright.

## Global Constraints

No new dependency may be added.
The `['pending-users']` cache must always contain `PageResponse<User>`.
Successful global deadline updates must refresh coordinator project lists and the Dashboard project summary.
Notification, sidebar, and user menus must not obscure one another.
The frontend must show only the safe backend chatbot error message.

---

### Task 1: Dashboard and Users Cache Contract

**Files:**

- Create: `e2e/coordinator-stability.spec.ts`
- Modify: `src/pages/coordinator/ManageUsersPage.tsx`

**Interfaces:**

- Consumes: `userApi.getPending(): Promise<AxiosResponse<PageResponse<User>>>` and query key `['pending-users']`.
- Produces: `pendingQuery.data` as `PageResponse<User> | undefined` on every coordinator page.

- [ ] **Step 1: Write the failing client-side navigation test**

Create a Playwright helper that logs in the coordinator through the mocked demo endpoint and mocks notifications, projects, parties, analytics, and all user directories.
Keep the pending endpoint response paginated:

```ts
import type { Page, Route } from '@playwright/test';

const coordinator = {
  id: '00000000-0000-0000-0000-000000000001',
  email: 'coordinator@talentbridge.com',
  firstName: 'Demo',
  lastName: 'Coordinator',
  role: 'COORDINATOR',
  status: 'APPROVED',
  onboardingComplete: true,
};

const pendingStudent = {
  id: '00000000-0000-0000-0000-000000000002',
  email: 'pending.student@talentbridge.com',
  firstName: 'Pending',
  lastName: 'Student',
  role: 'STUDENT',
  status: 'PENDING',
};

const openProject = {
  id: '00000000-0000-0000-0000-000000000003',
  title: 'AI Support Copilot',
  description: 'A representative open demo project.',
  scope: 'Build a support assistant.',
  deliverables: 'Working application.',
  evaluationCriteria: 'Quality and usability.',
  tools: ['React', 'Spring Boot'],
  status: 'OPEN',
  deadline: '2030-01-01T10:00:00.000Z',
  companyName: 'Nexa Systems',
  createdByName: 'Nexa Systems',
  partyApplicationCount: 0,
  createdAt: '2026-07-31T10:00:00.000Z',
};

const demoNotifications = [{
  id: '00000000-0000-0000-0000-000000000004',
  type: 'GENERAL',
  title: 'Demo ready',
  message: 'Representative demo data is available.',
  read: true,
  createdAt: '2026-07-31T10:00:00.000Z',
}];

const pageResponse = <T>(content: T[]) => ({
  content,
  totalPages: 1,
  totalElements: content.length,
  page: 0,
  size: 100,
  first: true,
  last: true,
});

type CoordinatorApiOptions = {
  onProjects?: (route: Route) => void | Promise<void>;
  onGlobalDeadline?: (route: Route) => void | Promise<void>;
};

async function loginAsCoordinator(page: Page) {
  await page.goto('/login');
  await page.getByRole('button', { name: /Coordinator/ }).click();
  await expect(page).toHaveURL(/\/coordinator\/dashboard$/);
}

async function mockCoordinatorApi(page: Page, options: CoordinatorApiOptions = {}) {
  await page.route('**/api/auth/demo-login', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      accessToken: 'demo-access-token',
      refreshToken: 'demo-refresh-token',
      tokenType: 'Bearer',
      user: coordinator,
    }),
  }));
  await page.route('**/api/users/me', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(coordinator),
  }));
  await page.route('**/api/users/notifications**', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ notifications: demoNotifications, unreadCount: 0 }),
  }));
  await page.route('**/api/users/pending**', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(pageResponse([pendingStudent])),
  }));
  for (const path of ['students', 'companies', 'supervisors', 'coordinators']) {
    await page.route(`**/api/users/${path}`, route => route.fulfill({
      contentType: 'application/json', body: '[]',
    }));
  }
  await page.route('**/api/projects/all**', route => options.onProjects
    ? options.onProjects(route)
    : route.fulfill({ contentType: 'application/json', body: JSON.stringify(pageResponse([openProject])) }));
  await page.route('**/api/projects/global-deadline', route => options.onGlobalDeadline
    ? options.onGlobalDeadline(route)
    : route.fulfill({ contentType: 'application/json', body: '{}' }));
  await page.route('**/api/parties/all', route => route.fulfill({
    contentType: 'application/json', body: '[]',
  }));
  await page.route('**/api/coordinator/analytics', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      projectsByStatus: {}, usersByRole: {}, usersByStatus: {},
      submissionsByStatus: {}, totalParties: 0, assignedParties: 0,
      unassignedParties: 0, finalizedEvaluations: 0, draftEvaluations: 0,
    }),
  }));
}

test('navigates from Dashboard to Users without blanking', async ({ page }) => {
  const errors: Error[] = [];
  page.on('pageerror', error => errors.push(error));
  await mockCoordinatorApi(page);
  await loginAsCoordinator(page);

  await expect(page.getByRole('heading', { name: 'Coordinator Dashboard' })).toBeVisible();
  await page.getByRole('link', { name: 'Users' }).click();

  await expect(page.getByRole('heading', { name: 'Manage Users' })).toBeVisible();
  await expect(page.getByText('pending.student@talentbridge.com')).toBeVisible();
  expect(errors).toEqual([]);
});
```

- [ ] **Step 2: Run the navigation test and verify failure**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "Dashboard to Users"`

Expected: The page blanks with `(pendingQuery.data ?? []) is not iterable`.

- [ ] **Step 3: Use the paginated contract in Manage Users**

Change only the query return and derived array:

```ts
const pendingQuery = useQuery({
  queryKey: ['pending-users'],
  queryFn: () => userApi.getPending(0, 100).then((r) => r.data),
});

const pendingUsers = pendingQuery.data?.content ?? [];

[
  ...pendingUsers,
  ...(studentsQuery.data ?? []),
  ...(companiesQuery.data ?? []),
  ...(supervisorsQuery.data ?? []),
  ...(coordinatorsQuery.data ?? []),
]
```

Rename the later filtered `pendingUsers` variable to `filteredPendingUsers` and update its count and bulk selection callers to avoid shadowing the response content.

- [ ] **Step 4: Run the navigation test and build**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "Dashboard to Users" && npm run build`

Expected: The test passes, the heading and pending user remain visible, and TypeScript builds successfully.

- [ ] **Step 5: Commit the cache fix**

```bash
git add e2e/coordinator-stability.spec.ts src/pages/coordinator/ManageUsersPage.tsx
git commit -m "fix: unify pending user cache shape"
```

### Task 2: Immediate Global Deadline Refresh

**Files:**

- Modify: `e2e/coordinator-stability.spec.ts`
- Modify: `src/pages/coordinator/ManageProjectsPage.tsx`

**Interfaces:**

- Consumes: `projectApi.setGlobalDeadline(deadline)` and existing query keys `['all-projects-admin']` and `['all-projects']`.
- Produces: A mutation success handler that invalidates both query families before clearing the input.

- [ ] **Step 1: Write the failing deadline refresh test**

Add a route whose project response changes only after the global deadline request:

```ts
test('shows a new global deadline without reloading', async ({ page }) => {
  let deadline = '2030-01-01T10:00:00.000Z';
  let projectFetches = 0;
  await mockCoordinatorApi(page, {
    onProjects: route => {
      projectFetches += 1;
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(pageResponse([{ ...openProject, deadline }])),
      });
    },
    onGlobalDeadline: async route => {
      deadline = (await route.request().postDataJSON()).deadline;
      return route.fulfill({ contentType: 'application/json', body: '{}' });
    },
  });
  await loginAsCoordinator(page);
  await page.getByRole('link', { name: 'Projects' }).click();
  await page.locator('input[type="datetime-local"]').first().fill('2031-01-02T10:00');
  await page.getByRole('button', { name: 'Apply' }).click();

  await expect.poll(() => projectFetches).toBeGreaterThan(1);
  await expect(page.getByText(/02 Jan 2031/)).toBeVisible();
});
```

- [ ] **Step 2: Run the deadline test and verify failure**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "global deadline"`

Expected: The GET count remains one and the old deadline remains visible.

- [ ] **Step 3: Invalidate both project query families**

Use the installed TanStack Query API in the success handler:

```ts
onSuccess: async () => {
  await Promise.all([
    qc.invalidateQueries({ queryKey: ['all-projects-admin'] }),
    qc.invalidateQueries({ queryKey: ['all-projects'] }),
  ]);
  toast.success('Global deadline applied to all open projects!');
  setGlobalDeadline('');
},
```

- [ ] **Step 4: Run the deadline test and build**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "global deadline" && npm run build`

Expected: The project query refetches, the new date appears, and the build passes.

- [ ] **Step 5: Commit the deadline fix**

```bash
git add e2e/coordinator-stability.spec.ts src/pages/coordinator/ManageProjectsPage.tsx
git commit -m "fix: refresh global project deadlines"
```

### Task 3: Viewport-Safe Notification Panel

**Files:**

- Modify: `e2e/coordinator-stability.spec.ts`
- Modify: `src/components/layout/AppLayout.tsx`

**Interfaces:**

- Consumes: Existing `notifOpen`, `sidebarOpen`, and `userMenuOpen` state.
- Produces: A notification trigger with `aria-label="Notifications"`, `aria-expanded`, `aria-controls="notification-panel"`, and a fixed panel inside the viewport.

- [ ] **Step 1: Write failing responsive interaction tests**

Add a constrained viewport test that measures the panel and verifies mutual exclusion:

```ts
test('keeps notifications visible and closes the mobile sidebar', async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 600 });
  await mockCoordinatorApi(page);
  await loginAsCoordinator(page);

  await page.getByRole('button', { name: 'Open sidebar' }).click();
  await expect(page.getByRole('button', { name: 'Close sidebar' })).toBeVisible();
  await page.getByRole('button', { name: 'Notifications' }).evaluate(
    (button: HTMLButtonElement) => button.click(),
  );

  await expect(page.getByRole('button', { name: 'Close sidebar' })).toBeHidden();
  const box = await page.locator('#notification-panel').boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(800);
});
```

- [ ] **Step 2: Run the notification test and verify failure**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "notifications visible"`

Expected: The notification trigger lacks an accessible name or the panel remains blocked by the sidebar.

- [ ] **Step 3: Make panel state mutually exclusive**

Add the three minimal toggles and use them at the current buttons:

```ts
const toggleNotifications = () => {
  const opening = !notifOpen;
  setNotifOpen(opening);
  if (opening) {
    setSidebarOpen(false);
    setUserMenuOpen(false);
  }
};

const openSidebar = () => {
  setNotifOpen(false);
  setUserMenuOpen(false);
  setSidebarOpen(true);
};

const toggleUserMenu = () => {
  const opening = !userMenuOpen;
  setUserMenuOpen(opening);
  if (opening) setNotifOpen(false);
};
```

- [ ] **Step 4: Make the panel viewport-safe and accessible**

Update the current trigger and panel only:

```tsx
<button
  onClick={toggleNotifications}
  aria-label='Notifications'
  aria-expanded={notifOpen}
  aria-controls='notification-panel'
  className='relative w-9 h-9 rounded-xl flex items-center justify-center text-ink-400 hover:text-ink-100 hover:bg-ink-800 transition-all'
>

<div
  id='notification-panel'
  className='fixed right-4 top-16 w-[min(20rem,calc(100vw-2rem))] z-[60] rounded-2xl border border-ink-700/50 bg-ink-900 shadow-2xl shadow-black/60 overflow-hidden'
>
```

- [ ] **Step 5: Run the notification test and build**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "notifications visible" && npm run build`

Expected: The sidebar closes, the panel bounds remain within 800 pixels, and the build passes.

- [ ] **Step 6: Commit the notification fix**

```bash
git add e2e/coordinator-stability.spec.ts src/components/layout/AppLayout.tsx
git commit -m "fix: keep notifications inside viewport"
```

### Task 4: Safe Chatbot Error Message

**Files:**

- Modify: `e2e/coordinator-stability.spec.ts`
- Modify: `src/components/chat/ChatBot.tsx`

**Interfaces:**

- Consumes: Axios errors whose backend payload may contain `{ message: string }`.
- Produces: The safe backend message when present, otherwise the existing generic retry message.

- [ ] **Step 1: Write the failing chatbot error test**

Mock the backend 503 response and operate the chatbot as a user:

```ts
test('shows a safe backend chatbot failure', async ({ page }) => {
  await mockCoordinatorApi(page);
  await page.route('**/api/chat', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ message: 'AI service is temporarily unavailable' }),
  }));
  await loginAsCoordinator(page);

  await page.getByRole('button', { name: 'Open AI assistant' }).click();
  await page.getByPlaceholder('Ask anything…').fill('Explain this project');
  await page.getByRole('button', { name: 'Send message' }).click();

  await expect(page.getByText('AI service is temporarily unavailable')).toBeVisible();
});
```

- [ ] **Step 2: Run the chatbot error test and verify failure**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "chatbot failure"`

Expected: The chatbot renders the current generic retry message.

- [ ] **Step 3: Surface the safe backend message and add accessible button names**

Change the catch branch and label the existing open and send buttons:

```tsx
} catch (error: any) {
  setMessages((prev) => [
    ...prev,
    {
      role: 'assistant',
      content: error?.response?.data?.message
        || 'Sorry, I ran into an error. Please try again.',
    },
  ]);
}

<button aria-label='Open AI assistant' onClick={() => setOpen(true)}>
<button aria-label='Send message' onClick={send} disabled={!input.trim() || loading}>
```

- [ ] **Step 4: Run the chatbot test and build**

Run: `npx playwright test e2e/coordinator-stability.spec.ts -g "chatbot failure" && npm run build`

Expected: The safe 503 message appears and TypeScript builds successfully.

- [ ] **Step 5: Commit the chatbot UI fix**

```bash
git add e2e/coordinator-stability.spec.ts src/components/chat/ChatBot.tsx
git commit -m "fix: show chatbot service errors"
```

### Task 5: Frontend Verification

**Files:**

- Verify only: `package.json`
- Verify only: `playwright.config.ts`

**Interfaces:**

- Consumes: All completed frontend fixes.
- Produces: Passing unit, build, and end-to-end checks with a clean diff.

- [ ] **Step 1: Run unit tests and the production build**

Run: `npm test && npm run build`

Expected: Both commands exit successfully.

- [ ] **Step 2: Run all frontend end-to-end tests**

Run: `npm run test:e2e`

Expected: Every Chromium Playwright test passes.

- [ ] **Step 3: Inspect the final frontend diff**

Run: `git status --short && git diff HEAD~4 --check`

Expected: The worktree is clean and `git diff --check` produces no output.

- [ ] **Step 4: Run the integrated demo-mode smoke test**

Start the backend from `talentbridge-backend` with demo mode enabled and a valid rotated `OPENAI_API_KEY` already present in the local environment:

```bash
APP_DEMO_MODE=true ./mvnw spring-boot:run
```

Start the frontend from `talentbridge-frontend`:

```bash
npm run dev -- --host 127.0.0.1
```

Use the coordinator demo card and verify this exact sequence in Chromium:

1. Confirm that representative users, projects, parties, and notifications are visible.
2. Navigate Dashboard to Users to Dashboard without reloading and confirm both headings render.
3. Apply a global deadline and confirm the displayed project date changes without reloading.
4. Open notifications at an 800 by 600 viewport and confirm the full panel stays inside the viewport.
5. Send `Summarize the available demo projects` and confirm the assistant returns a non-empty reply.
6. Restart the backend and confirm the representative record counts do not increase.

Expected: All six checks pass without browser console errors, duplicate records, or exposed credentials.
