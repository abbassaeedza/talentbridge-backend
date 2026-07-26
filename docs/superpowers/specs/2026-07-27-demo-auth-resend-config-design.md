# Demo Authentication, Resend, and Runtime Configuration Design

## Goal

Keep the current coordinator account usable as a disposable public demo without exposing its password in the frontend bundle.
Send email for every existing in-app notification through Resend.
Align local and Render environment configuration with the deployed Supabase-based architecture.

## Architecture

The backend owns demo authentication, coordinator credentials, email delivery, and runtime limits.
The frontend displays the existing demo card and requests a demo session without receiving the coordinator password.
Render stores secrets and deploy-time configuration, while Vercel receives only public frontend configuration.

## Demo authentication

The backend exposes a dedicated demo-login endpoint only when `APP_DEMO_MODE=true`.
The endpoint loads the configured coordinator account and returns the normal authentication response.
The endpoint does not accept, return, or log the coordinator password.
When `APP_DEMO_MODE=false`, the endpoint is unavailable.

During demo mode, backend startup synchronizes the configured coordinator password into the seeded database account.
This prevents the database password from drifting when `APP_SEED_COORDINATOR_PASSWORD` changes on Render.
Outside demo mode, startup creates a missing coordinator but never overwrites an existing password.

The frontend demo card calls the demo-login endpoint and follows the existing successful-login flow.
No `VITE_*` variable contains a password because Vite embeds those values in public browser code.

## Email delivery

The backend uses the official Resend Java SDK with `RESEND_API_KEY` and `RESEND_FROM_EMAIL`.
Both existing `NotificationService.send(...)` paths continue to save in-app notifications and also request an email with the same title and message.
This covers registration alerts, account decisions, party events, project events, evaluations, submissions, and broadcasts without duplicating email calls across business services.

Email delivery is best effort.
A Resend failure is logged and does not roll back or fail the application action that created the notification.
No queue or outbox is added for the demo phase.
An outbox can be added after launch if guaranteed delivery becomes a measured requirement.

## Environment configuration

The ignored backend `.env` and committed `.env.example` replace legacy AWS S3 variables with the existing Supabase Storage variables.
They replace unused SendGrid-style mail variables with Resend variables.
They include database TLS, demo mode, coordinator seed credentials, JWT timing, and party-size settings.

`application.yml` reads `PARTY_MIN_SIZE` and `PARTY_MAX_SIZE` instead of hardcoding the limits.
`render.yaml` generates `JWT_SECRET`, prompts for secret values with `sync: false`, and declares non-secret JWT expiry and party-size defaults.
The Render service receives `APP_DEMO_MODE=true` during the public demo phase.

The ignored frontend `.env` remains limited to `VITE_API_URL` and `VITE_GITHUB_CLIENT_ID`.
No frontend password variable is introduced.

## Launch transition

Set `APP_DEMO_MODE=false` on Render before public launch.
Change the real coordinator password through an authenticated password-change flow or a controlled database operation.
Remove or hide the frontend demo card at the same time.
The disabled backend endpoint prevents demo authentication even if an old frontend deployment still renders the card.

## Error handling

Demo login returns the normal authentication error if the configured coordinator account is unavailable.
Disabled demo mode returns a not-found response so it does not advertise a dormant privileged endpoint.
Resend configuration or delivery errors are logged without including secrets.

## Verification

Backend tests verify demo login when enabled, endpoint rejection when disabled, password synchronization only in demo mode, email dispatch for both notification overloads, and non-fatal Resend failure.
Frontend tests verify that clicking the demo card calls demo login and completes the existing authentication flow.
Backend and frontend test and production-build commands run before implementation commits are pushed.

## Current documentation decisions

Render Blueprint variables marked `sync: false` are entered in the Render dashboard during initial creation and are not overwritten by later Blueprint syncs.
Render `generateValue: true` creates a random 256-bit value suitable for `JWT_SECRET`.
Vite exposes every `VITE_*` value in the browser bundle, so secrets must remain on the backend.
The Resend Java SDK sends mail using a server-side API key and sender identity.
