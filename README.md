# TalentBridge Backend

Spring Boot REST API for TalentBridge — an AI-powered platform that helps companies shortlist skilled student talent through industry-driven projects.

---

## Tech Stack

| Concern | Technology |
|---|---|
| Framework | Spring Boot 3.2 (Java 17) |
| Security | Spring Security + JWT |
| Database | PostgreSQL 15 |
| Migrations | Flyway |
| AI Chatbot | OpenAI GPT-4o-mini |
| AI Evaluation | OpenAI GPT-4o |
| Repo Analysis | GitHub REST API v3 |
| File Storage | Local filesystem (S3-ready) |
| Email | SendGrid / SMTP |
| Containerisation | Docker + Docker Compose |

---

## Quick Start

### 1. Prerequisites
- Java 17+
- Maven 3.9+
- Docker & Docker Compose

### 2. Configure environment
```bash
cp .env.example .env
# Edit .env — fill in OPENAI_API_KEY, GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET at minimum
```

### 3. Start PostgreSQL
```bash
docker compose up postgres -d
```

### 4. Run the backend
```bash
./mvnw spring-boot:run
```

By default, `docker-compose.yml` starts PostgreSQL only. The backend service is
kept commented so local development can run Spring Boot directly with hot
reload-friendly IDE tooling. Uncomment the backend service in
`docker-compose.yml` if you want a containerized backend too:

```bash
docker compose up --build
```

The API will be available at **http://localhost:8080**

---

## Default Credentials

| Role | Email | Password |
|---|---|---|
| Coordinator | coordinator@talentbridge.com | Admin1234! |

---

## API Reference

### Auth
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Register any role |
| POST | `/api/auth/login` | Login |
| POST | `/api/auth/refresh` | Refresh access token |
| PUT | `/api/auth/password` | Change password |

### Users
| Method | Endpoint | Role | Description |
|---|---|---|---|
| GET | `/api/users/me` | Any | Get current user |
| POST | `/api/users/onboarding` | STUDENT | Complete profile |
| GET | `/api/users/pending` | COORDINATOR | Pending registrations |
| PUT | `/api/users/{id}/approve` | COORDINATOR | Approve user |
| PUT | `/api/users/{id}/reject` | COORDINATOR | Reject user |
| POST | `/api/users/github/callback` | STUDENT | Link GitHub |
| GET | `/api/users/notifications` | Any | Get notifications |
| GET | `/api/users/my/scorecard` | STUDENT | My scorecard |

### Projects
| Method | Endpoint | Role | Description |
|---|---|---|---|
| POST | `/api/projects` | COMPANY, COORDINATOR | Create project |
| GET | `/api/projects` | Any | Browse open projects |
| GET | `/api/projects/all` | COORDINATOR | All projects |
| GET | `/api/projects/{id}` | Any | Project detail |
| PUT | `/api/projects/{id}/approve` | COORDINATOR | Approve project |
| PUT | `/api/projects/{id}/deadline` | COORDINATOR | Set deadline |
| PUT | `/api/projects/global-deadline` | COORDINATOR | Set global deadline |
| PUT | `/api/projects/{id}/assign/{partyId}` | COORDINATOR | Assign to party |

### Parties
| Method | Endpoint | Role | Description |
|---|---|---|---|
| POST | `/api/parties` | STUDENT | Create party |
| POST | `/api/parties/{id}/join` | STUDENT | Join party |
| DELETE | `/api/parties/{id}/leave` | STUDENT | Leave party |
| POST | `/api/parties/{id}/apply` | STUDENT | Apply to project |
| GET | `/api/parties/my` | STUDENT | My party |
| GET | `/api/parties/all` | COORDINATOR | All parties |
| GET | `/api/parties/supervised` | SUPERVISOR | Supervised parties |
| PUT | `/api/parties/{id}/supervisor` | COORDINATOR | Assign supervisor |

### Submissions
| Method | Endpoint | Role | Description |
|---|---|---|---|
| POST | `/api/submissions/{partyId}/draft` | STUDENT | Save draft |
| POST | `/api/submissions/{partyId}/submit` | STUDENT | Final submit |
| GET | `/api/submissions/{partyId}` | Any | Get submission |

### Evaluations
| Method | Endpoint | Role | Description |
|---|---|---|---|
| POST | `/api/evaluations/trigger/{submissionId}` | COORDINATOR | Trigger AI evaluation |
| PUT | `/api/evaluations/{reportId}/finalize` | COORDINATOR | Finalize report |
| GET | `/api/evaluations/submission/{id}` | Any | Get report |

### AI Chat
| Method | Endpoint | Role | Description |
|---|---|---|---|
| POST | `/api/chat` | Any | Chat with AI assistant |

---

## Project Structure

```
src/main/java/com/talentbridge/
├── TalentBridgeApplication.java
├── config/
│   ├── SecurityConfig.java
│   └── AppProperties.java
├── controller/         # REST endpoints
├── dto/
│   ├── request/        # Request bodies
│   └── response/       # Response shapes
├── entity/             # JPA entities
├── enums/              # Domain enums
├── exception/          # Custom exceptions + global handler
├── repository/         # Spring Data JPA repositories
├── security/           # JWT provider + filter
└── service/            # Business logic
    ├── AuthService.java
    ├── UserService.java
    ├── ProjectService.java
    ├── PartyService.java
    ├── SubmissionService.java
    ├── EvaluationService.java
    ├── OpenAIService.java
    ├── GitHubService.java
    ├── ScorecardService.java
    ├── NotificationService.java
    └── FileStorageService.java
```

---

## User Roles

| Role | Description |
|---|---|
| `STUDENT` | Creates/joins party, browses & applies to projects, submits work |
| `COMPANY` | Posts industry projects, assigns project supervisors |
| `PARTY_SUPERVISOR` | University professor; monitors party progress (max 2/semester) |
| `PROJECT_SUPERVISOR` | Company mentor; views party progress on their project |
| `COORDINATOR` | Admin; approves users, assigns projects, triggers evaluations |

---

## AI Evaluation Dimensions

When the coordinator triggers evaluation, GPT-4o analyses the submitted GitHub repo:

| Dimension | Weight | Description |
|---|---|---|
| AI Detection | 20% | Likelihood code is human-written |
| Code Quality | 25% | SOLID principles, naming, structure, tests |
| Functionality | 25% | Does the app actually work? |
| Scope Alignment | 20% | Does it match the deliverables? |
| Team Collaboration | 10% | Commit distribution across members |

Individual student scores are then calculated based on their commit contribution percentage.
