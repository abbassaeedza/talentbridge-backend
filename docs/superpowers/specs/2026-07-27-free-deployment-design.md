# Free Deployment Design

## Goal

Deploy TalentBridge with no hosting fee for light demonstration use.

## Architecture

Vercel builds and serves the Vite frontend from the frontend GitHub repository.
Render builds and runs the Spring Boot Docker image from the backend GitHub repository.
Supabase supplies PostgreSQL and a public Storage bucket for submission documents.

## Runtime flow

The browser calls the Render API through `VITE_API_URL`.
Render connects to Supabase PostgreSQL through its session pooler using TLS.
The backend uploads documents to Supabase Storage with a server-only secret key and stores public object URLs in PostgreSQL.
Local development keeps filesystem storage when Supabase variables are absent.

## Constraints

- Keep both GitHub repositories independent.
- Add no Java storage SDK.
- Use Java `HttpClient` for Supabase Storage REST requests.
- Never expose `SUPABASE_SERVICE_ROLE_KEY` to Vercel or browser code.
- Use a public Storage bucket because existing submission records contain directly opened document URLs.
- Accept Render free-tier cold starts and Supabase free-tier limits for demonstration use.
- Keep OpenAI usage outside the free-hosting guarantee.

## Security

Only the backend receives the Supabase secret key.
GitHub OAuth requests include an unpredictable `state` value stored in session storage and validated on callback.
Production CORS permits the exact Vercel origin.
JWT, GitHub, OpenAI, database, and Supabase credentials remain dashboard secrets.

## Verification

Backend tests cover authenticated Supabase upload behavior and returned public URLs.
Frontend tests cover GitHub OAuth URL and state generation using Node's built-in test runner.
Production builds verify both repositories before deployment commits are pushed.
