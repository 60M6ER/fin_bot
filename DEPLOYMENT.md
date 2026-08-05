# Docker deployment

The application image contains both the Quasar frontend and the Spring Boot backend.
PostgreSQL runs in a separate container and stores its data in the `pgdata` volume.

## First start

Create `.env` from `.env.example` and replace both required secrets:

```sh
cp .env .env
openssl rand -hex 32
openssl rand -base64 32
chmod 600 .env
docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps
docker compose logs -f app
```

Use the hexadecimal value as `POSTGRES_PASSWORD` and the base64 value as
`APP_SECRET_KEY`. Keep `APP_SECRET_KEY` stable and backed up: changing it makes encrypted
broker and Telegram credentials in the database unreadable.

By default the UI is available only on the server itself at `http://127.0.0.1:8080`.
The application currently has no user authentication. Do not publish it directly to the
Internet. Use a VPN or a reverse proxy with TLS and authentication. A host-level reverse
proxy can forward to the default loopback binding without changing `APP_BIND_ADDRESS`.

For immediate private access from your computer, use an SSH tunnel and open
`http://127.0.0.1:8080` locally:

```sh
ssh -N -L 8080:127.0.0.1:8080 user@your-server
```

## Operations

```sh
# Health and logs
curl --fail http://127.0.0.1:8080/actuator/health
docker compose logs --tail=200 app

# Graceful restart; the application gets time to stop bot runtimes and cancel orders
docker compose restart app

# Rebuild after updating the source
docker compose build app
docker compose up -d app

# Stop everything without deleting database data
docker compose down
```

## Database backup

Create a backup before every deployment that contains migrations:

```sh
docker compose exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > fin-bot.dump
```

Restore into an empty database only:

```sh
docker compose exec -T postgres sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < fin-bot.dump
```

Do not use `docker compose down -v` unless permanent deletion of the database is intended.

## Local package build

`bootJar` builds the frontend automatically and embeds it into the JAR:

```sh
cd backend
./gradlew clean bootJar
jar tf build/libs/backend-0.0.1-SNAPSHOT.jar | grep BOOT-INF/classes/static/index.html
```

For Docker only, `-PskipFrontend=true` skips the Gradle frontend task because the
multi-stage Docker build has already copied the compiled SPA into backend resources.
