# TalentBridge Demo Stability Design

## Goal

TalentBridge must provide a reliable demo experience without changing production data or requiring page reloads.
The work covers demo-only sample data, chatbot configuration, project deadline refreshes, Dashboard and Users navigation, and the notification panel.

## Confirmed Causes

The Dashboard and Users pages share the `['pending-users']` TanStack Query key but cache different response shapes.
The Dashboard caches a paginated response, while Manage Users caches an array, so client-side navigation can render the wrong shape and blank the page.

The global deadline mutation reports success but does not invalidate project queries.
The browser therefore continues to show cached project deadlines until a reload fetches fresh data.

The notification dropdown is positioned inside the header while the sidebar uses its own higher stacking context.
At constrained widths, the dropdown can be clipped or covered, and the sidebar and notification panel can remain open together.

The chatbot request reaches the backend and OpenAI, but OpenAI rejects the configured credential with HTTP 401.
The existing Chat Completions request shape and configured model are supported, so the integration does not need a rewrite.

The current data seeder creates only the coordinator account and is not sufficient for a representative demo.

## Design

### Demo Data

The existing backend seeding path will create a small deterministic dataset only when `app.demo-mode=true`.
When demo mode is false, it will keep the current production behavior and will not create sample records.

The dataset will represent the current local database rather than copy its accumulated test volume.
It will include users for every role, students in approved, pending, rejected, and suspended states, projects across the current lifecycle states, parties across their lifecycle states, applications, draft and submitted work, and a small set of notifications.

Stable identifying values, such as fixed demo email addresses, will make the seed idempotent.
Restarting the application will not duplicate the representative dataset.
The implementation will reuse current entities, repositories, password encoding, and seeder conventions without adding a fixture framework.

### Dashboard and Users Navigation

Every caller of the `['pending-users']` query will use one cached contract: the existing paginated API response.
Manage Users will derive its displayed array from the response `content` field.
This removes the incompatible cache state at its source and preserves client-side navigation.

### Global Deadline

After a successful global deadline update, the mutation will invalidate the existing project query families used by the coordinator pages.
TanStack Query will refetch active project views and display the new deadline without a browser reload.

### Notification Panel

The notification panel will use viewport-safe positioning and responsive width so it remains visible beside or above the sidebar at narrow widths.
Opening notifications will close the mobile sidebar and user menu.
Opening the sidebar or user menu will close notifications.
The notification trigger will retain an accessible label and expanded state.

### Chatbot

The OpenAI key will remain an environment-only secret and will never be committed.
The backend will keep the existing Chat Completions integration and configured model.
A valid rotated key must be supplied through the local environment before the live chatbot smoke test.

The backend will return a useful service error when the upstream request fails without exposing credentials or raw sensitive response data.
The frontend will show that safe message instead of only a generic failure when one is available.

The key pasted into chat must be rotated because chat history is not an appropriate secret store.

## Error Handling

Seed failures will continue to fail startup rather than leave a partially trusted demo state unnoticed.
Mutation failures will keep the current toast behavior and will not invalidate successful cached data.
Chatbot failures will remain non-destructive and will preserve the conversation already shown in the browser.

## Verification

Backend tests will verify that demo records are created only in demo mode and that repeated seeding does not duplicate them.
Chatbot tests will mock the HTTP boundary and cover successful content extraction and safe upstream failure handling without network access.

Frontend tests will reproduce client-side Dashboard to Users navigation with one query cache, verify immediate deadline refresh after mutation success, and verify the notification panel at the constrained viewport shown in the report.
Existing lint, unit, backend, and end-to-end checks must pass.

A final local smoke test will run TalentBridge in demo mode, navigate without reloads, update a global deadline, inspect the notification panel, and send a chatbot message with a newly rotated environment key.

## Out of Scope

This change will not copy hundreds of historical notifications or timestamped smoke-test records from the local database.
It will not add a new state-management library, replace React Router, or migrate the chatbot to a different OpenAI API.
