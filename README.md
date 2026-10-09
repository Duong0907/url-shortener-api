# URL Shortener API

Spring Boot service that turns long URLs into short links and redirects them.

**Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Hibernate, Lombok, springdoc-openapi.

## Run locally

1. Start a PostgreSQL 16 database.
2. Copy the env file and fill in your credentials:

   ```bash
   cp .env.example .env
   ```

3. Start the app:

   ```bash
   ./mvnw spring-boot:run
   ```

Flyway creates the tables on first start.

No database yet? Start one with Docker, using the same values as in `.env`:

```bash
docker run -d --name urlshortener-pg -p 5432:5432 \
  -e POSTGRES_USER=urlshortener -e POSTGRES_PASSWORD=change-me -e POSTGRES_DB=urlshortener \
  postgres:16-alpine
```

Or run the app with a disposable Testcontainers database (Docker required, no `.env` needed):

```bash
./mvnw spring-boot:test-run
```

## Configuration

The app reads `.env` in the repository root at startup. `.env` is git-ignored. Never commit it.
Real environment variables override `.env`.

| Variable            | Default                                        | Meaning                     |
|---------------------|------------------------------------------------|-----------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/urlshortener` | JDBC URL                    |
| `DATABASE_USERNAME` | empty                                          | Database user               |
| `DATABASE_PASSWORD` | empty                                          | Database password           |
| `APP_BASE_URL`      | `http://localhost:8080`                        | Prefix of returned short URLs |

## API

| Method | Path          | Result                                         |
|--------|---------------|------------------------------------------------|
| POST   | `/api/links`  | `201` with the new short link                  |
| GET    | `/{slug}`     | `302` to the target URL, `404` if unknown      |
| GET    | `/health`     | Health status                                  |

```bash
curl -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://example.com"}'
```

Errors use `application/problem+json`.

API docs: Swagger UI at <http://localhost:8080/swagger-ui.html>, OpenAPI JSON at `/v3/api-docs`.

## Test

```bash
./mvnw test      # tests only
./mvnw verify    # tests + coverage check
```

Integration tests start PostgreSQL with Testcontainers. Docker must be running.

`verify` fails when line coverage drops below 80%. Change the limit with `coverage.minimum` in `pom.xml`.
The HTML report is in `target/site/jacoco/index.html`.

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push to `master`/`main` and on every pull request.
It uploads the JaCoCo report as the `jacoco-report` artifact.

## Project layout

```text
src/main/java/com/urlshortener/
├── link/
│   ├── controller/   REST endpoints
│   ├── dto/          Request and response records
│   ├── entity/       JPA entities
│   ├── repository/   Spring Data repositories
│   └── service/      Business logic, URL validation, slug generation
├── redirect/         GET /{slug}
└── OpenApiConfig.java
src/main/resources/db/migration/   Flyway SQL migrations
```

Flyway owns the schema. Hibernate only validates it (`ddl-auto=validate`).
A schema change is a new `V<n>__*.sql` file plus the entity change. Never edit a migration that has run.
