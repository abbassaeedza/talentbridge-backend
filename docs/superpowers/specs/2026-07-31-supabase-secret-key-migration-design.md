# Supabase Secret Key Migration Design

## Goal

Replace the deprecated JWT-based Supabase `service_role` key with the new server-only `sb_secret_...` key without retaining a legacy fallback.

## Considered approaches

### Clean replacement

Rename the environment variable to `SUPABASE_SECRET_KEY`, rename the Spring property and Java field, and send the key only through the `apikey` request header.
This is the selected approach because it matches current Supabase guidance and prevents the non-JWT secret key from being sent through `Authorization: Bearer`.

### Compatibility fallback

Accept both `SUPABASE_SECRET_KEY` and `SUPABASE_SERVICE_ROLE_KEY` during a transition.
This was rejected because the local and Cloud Run environments can be updated together, so a fallback would preserve deprecated configuration without preventing downtime.

### Keep the legacy variable name

Store the new secret key under `SUPABASE_SERVICE_ROLE_KEY` while changing only the HTTP header behavior.
This was rejected because the name would falsely describe the credential as a legacy JWT and make later security work error-prone.

## Code and configuration

`FileStorageService` will read `supabase.secret-key` and will require it before selecting Supabase Storage.
Storage uploads will include `apikey: sb_secret_...` and will omit the `Authorization` header.
`application.yml`, `.env.example`, the README, and deployment documentation will use `SUPABASE_SECRET_KEY` exclusively.
The obsolete Render blueprint will be removed because deployment now uses Cloud Run.

## Deployment

The updated local `.env` is the source for the Cloud Run runtime environment.
Only the documented backend runtime variables will be uploaded.
The legacy `SUPABASE_SERVICE_ROLE_KEY` variable will not remain on the new Cloud Run revision.
GitHub Actions will deploy the committed code after tests pass.

## Verification

A regression test will assert that Storage receives the new secret through `apikey` and receives no `Authorization` header.
The complete backend test suite must pass.
The Cloud Run revision must become ready, and the database-backed demo-login endpoint must return HTTP 200.
