# VolunteerHub — Backend API (Spring Boot 3 & Java 21)

This repository contains the backend service for **VolunteerHub**, a volunteer shift scheduling platform. Built with Spring Boot 3.3.5 and Java 21, it provides JWT authentication, concurrency control for shift capacity, automatic waitlist management, and schedule overlap prevention.

---

## Tech Stack
- **Language:** Java 21
- **Framework:** Spring Boot 3.3.5 (Spring Web, Spring Data JPA, Spring Security, Spring Actuator)
- **Security:** Spring Security 6 OAuth2 Resource Server (stateless JWT), BCrypt hashing
- **Database:** PostgreSQL (Neon for production, H2 in-memory for local development)
- **Concurrency Control:** Pessimistic row locking (`@Lock(PESSIMISTIC_WRITE)`) to prevent overbooking

---

## Local Setup

### Prerequisites
- Java 21 JDK (e.g. Eclipse Adoptium Temurin 21)
- Maven 3.9+

### Running the Application
```bash
# Set Java 21 home if required
# Windows PowerShell:
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"

# Run locally with in-memory H2 database:
mvn spring-boot:run
```
The server will start at `http://localhost:8080`.
Health check: `http://localhost:8080/actuator/health`

### Running Automated Tests
```bash
mvn test
```

---

## Environment Variables

| Variable | Description | Default (Local Dev) |
|---|---|---|
| `PORT` | HTTP port | `8080` |
| `DB_URL` | JDBC database URL | `jdbc:h2:mem:shifts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1` |
| `DB_USER` | Database username | `sa` |
| `DB_PASSWORD` | Database password | *(empty)* |
| `DDL_AUTO` | Hibernate schema mode | `update` |
| `JWT_SECRET` | Secret key for JWT signing | 32+ character random string |
| `FRONTEND_ORIGIN` | Allowed CORS origins | `http://localhost:5173,http://localhost:8080` |

---

## Deployment to Render

1. Create a PostgreSQL database on **Neon** (`console.neon.tech`) and obtain the JDBC connection string.
2. In **Render** (`dashboard.render.com`), create a **New Web Service** connected to this repository.
3. Select runtime: **Docker** (Render uses the provided `Dockerfile`).
4. Configure environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `FRONTEND_ORIGIN`).
5. Set Health Check path to `/actuator/health`.
