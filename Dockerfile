FROM node:20-alpine AS frontend-build
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts

COPY frontend/ ./
RUN npm run build


FROM eclipse-temurin:21-jdk-jammy AS backend-build
WORKDIR /workspace/backend

COPY backend/gradle ./gradle
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
RUN chmod +x gradlew

COPY backend/src ./src
COPY --from=frontend-build /workspace/frontend/dist/spa ./src/main/resources/static

RUN ./gradlew bootJar --no-daemon -PskipFrontend=true && \
    cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" /workspace/app.jar


FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system app && \
    useradd --system --gid app --home-dir /app --create-home app

WORKDIR /app
COPY --from=backend-build --chown=app:app /workspace/app.jar ./app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
