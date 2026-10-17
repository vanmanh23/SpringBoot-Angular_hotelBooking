# Stay Hotel — Hotel Booking System

Microservices hotel booking platform: Java 17 / Spring Boot 3.5, Spring Cloud Gateway, PostgreSQL 17, Kafka, Angular 21, Docker Compose.

> **Status:** Milestones M0 (foundation), M1 (authentication & authorization), M2 (hotel catalogue & search) and M3 (booking with concurrency-safe inventory) are complete. Payment (M4) is next. Business features are being built milestone by milestone; see [`docs/PROGRESS.md`](docs/PROGRESS.md). Only endpoints in [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md) marked *Implemented* exist.

## Architecture
```
Browser ──> frontend (nginx, Angular) ──/api──> api-gateway :8080
                                                  ├── auth-service         :8081  auth_db
                                                  ├── hotel-service        :8082  hotel_db
                                                  ├── booking-service      :8083  booking_db
                                                  ├── payment-service      :8084  payment_db
                                                  ├── review-service       :8085  review_db
                                                  └── notification-service :8086  notification_db
PostgreSQL 17 (one DB + role per service) · Kafka 3.9 (KRaft)
```
Design reference: `hotel-stayed-report.docx` (not in the repo). Decisions: [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Repository layout
| Path | Content |
|------|---------|
| `backend/` | Maven multi-module: `common-lib`, `api-gateway`, six services |
| `frontend/` | Angular app (`core/`, `shared/`, `features/`) |
| `infra/postgres/init/` | First-start script that creates per-service DBs and roles |
| `docs/` | audit, API contract, traceability, decisions, plan, progress |

## Prerequisites
JDK 17, Maven 3.9+, Node 22, Docker Desktop (also needed for Testcontainers tests).

## Run everything with Docker
```bash
cp .env.example .env        # then replace every change-me value
echo "AUTH_JWT_PRIVATE_KEY=$(scripts/generate-jwt-key.sh)" >> .env   # or paste it over the placeholder
# optional first admin: set ADMIN_EMAIL / ADMIN_PASSWORD (12+ chars, letters and digits) in .env
docker compose up -d --build --wait
# Frontend: http://localhost:4200   Gateway: http://localhost:8080
docker compose down          # keeps data volumes
```

Demo data (optional; ADMIN_EMAIL/ADMIN_PASSWORD must have been set before the first start):
```bash
python scripts/seed-demo-data.py --admin-email <ADMIN_EMAIL> --admin-password <ADMIN_PASSWORD>
```

## Develop locally
```bash
# Infrastructure only
docker compose up -d postgres kafka

# A service (credentials from .env). AUTH_JWT_ALLOW_EPHEMERAL_KEY=true is for local dev only.
cd backend
DB_USERNAME=auth_user DB_PASSWORD=<AUTH_DB_PASSWORD> AUTH_JWT_ALLOW_EPHEMERAL_KEY=true   mvn -pl auth-service -am spring-boot:run

# Frontend (proxies /api to localhost:8080)
cd frontend
npx -y npm@11 install        # npm 10.9.0 has an install bug, see DECISIONS D-008
npx ng serve
```

## Tests
```bash
cd backend && mvn -B clean verify          # unit + Testcontainers integration tests
cd frontend && npx ng build && npx ng test --watch=false
```

## Configuration
All secrets come from environment variables (`.env` for Compose, never committed). See [`.env.example`](.env.example). Each service reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`; the gateway reads `*_SERVICE_URL` and `CORS_ALLOWED_ORIGINS`.
