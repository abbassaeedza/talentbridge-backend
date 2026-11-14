# TalentBridge Backend

TalentBridge is an AI-assisted platform connecting university student teams with industry projects.
This repository contains its Spring Boot REST API.

## Features

- JWT authentication with refresh tokens and role-based authorization
- Student, company, coordinator, and supervisor workflows
- Project, party, application, submission, evaluation, scorecard, and notification management
- PostgreSQL schema migrations with Flyway
- GitHub repository analysis
- OpenAI-powered chat and project evaluation
- Multipart submission uploads
- Resend email delivery for every in-app notification

## Technology

- Java 17
- Spring Boot 3.2
- Spring Security
- Spring Data JPA
- PostgreSQL 15
- Flyway
- Maven Wrapper
- Docker

## Requirements

- Java 17 or newer
- Docker with Docker Compose

## Local development

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Copy the example configuration, add required credentials, then start Spring Boot:

```bash
cp .env.example .env
./mvnw spring-boot:run
```

Environment files are not loaded automatically by Spring Boot.
Export their values through your shell or IDE before starting the application.

The API runs at `http://localhost:8080` by default.
Flyway applies database migrations during startup.

## Required environment variables

| Variable | Description |
| --- | --- |
| `DB_HOST` | PostgreSQL host |
| `DB_PORT` | PostgreSQL port |
| `DB_NAME` | PostgreSQL database |
| `DB_USERNAME` | PostgreSQL user |
| `DB_PASSWORD` | PostgreSQL password |
| `JWT_SECRET` | Random JWT signing secret of at least 32 bytes |
| `FRONTEND_URL` | Exact frontend origin allowed by CORS |
| `OPENAI_API_KEY` | OpenAI API key used by chat and evaluation |
| `N8N_AI_WEBHOOK_URL` | Optional authenticated n8n relay for chat and evaluation |
| `N8N_WEBHOOK_SECRET` | Header Auth value for the n8n AI relay |
| `GITHUB_CLIENT_ID` | GitHub OAuth App client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App client secret |
| `GITHUB_REDIRECT_URI` | Registered GitHub OAuth callback URL |
| `LOCAL_STORAGE_PATH` | Directory used for uploaded submission files |
| `DB_SSL_MODE` | PostgreSQL TLS mode, set to `require` for Supabase |
| `SUPABASE_URL` | Supabase project URL enabling production object storage |
| `SUPABASE_SECRET_KEY` | Server-only Supabase secret key |
| `SUPABASE_STORAGE_BUCKET` | Public Storage bucket, defaults to `talentbridge-files` |
| `RESEND_API_KEY` | Server-only Resend API key |
| `RESEND_FROM_EMAIL` | Sender identity, such as `TalentBridge <notifications@example.com>` |
| `APP_DEMO_MODE` | Enables demo fixture seeding and password synchronization, never password-free login |
| `APP_SEED_COORDINATOR_EMAIL` | Preserved operator/bootstrap identity, defaults to `coordinator@talentbridge.com` |
| `APP_SEED_COORDINATOR_PASSWORD` | Required only when creating the operator; existing operator credentials are preserved |
| `APP_SEED_DEMO_PASSWORD` | Required server-only password for the 23 exact fictional demo accounts |
| `APP_SEED_RESET_DEMO_DATA` | Defaults to `false`; explicit one-time opt-in to replace an older dataset after backup and approval |
| `PARTY_MIN_SIZE` | Minimum party size, defaults to `2` |
| `PARTY_MAX_SIZE` | Maximum party size, defaults to `3` |
| `PARTY_SUPERVISOR_MAX_PARTIES` | Maximum supervised parties per academic term, defaults to `2` |
| `SERVER_PORT` | HTTP port, defaults to `8080` |

Development defaults exist for convenience.
Always override passwords, tokens, and seed credentials in a public deployment.
Demo accounts use normal email/password login, including `rowan.hale@example.test` (coordinator) and `mira.shah@example.test` (student).
All replacement fixture emails use alphabetic names at `example.test`.
The operator is not a demo fixture and is never deleted or password-synchronized by a demo reset.
An existing version mismatch is skipped with a warning unless reset is explicitly enabled.
At the current version, startup synchronizes only the exact demo accounts' passwords without resetting their work.
See the deployment guide before enabling reset; ownership-based replacement deletes extra work created by allowlisted demo users too.

## Commands

```bash
./mvnw spring-boot:run  # Start API
./mvnw test             # Run tests
./mvnw clean package    # Build executable JAR
docker build -t talentbridge-backend .
```

## Deployment

See [Cloud Run and Supabase deployment](docs/DEPLOYMENT.md) for setup, secrets, deployment, and verification instructions.

## API groups

| Prefix | Purpose |
| --- | --- |
| `/api/auth` | Registration, password login, refresh, and password management |
| `/api/users` | Profiles, approvals, GitHub linking, and notifications |
| `/api/projects` | Project creation, discovery, approval, and assignment |
| `/api/parties` | Team membership, applications, and supervision |
| `/api/submissions` | Draft and final project submissions |
| `/api/evaluations` | AI evaluation and report finalization |
| `/api/chat` | AI assistant |
| `/api/analytics` | Coordinator analytics |

## Database migrations

Migration scripts live in `src/main/resources/db/migration`.
Add a new versioned migration instead of changing a migration already applied to a shared database.

## Frontend

Frontend source lives in the separate [`talentbridge-frontend`](https://github.com/abbassaeedza/talentbridge-frontend) repository.

## License

Licensed under the [MIT License](LICENSE).
