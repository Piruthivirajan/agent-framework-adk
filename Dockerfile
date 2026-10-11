# syntax=docker/dockerfile:1
#
# Production image of the application: multi-stage, with the cross-encoder model baked in and its
# sha256 verified at build time. Build context is the repository root (see .dockerignore).
#
#   docker build -t agentfw-app .
#
# The corpus PDFs are NOT in the image (they are downloaded and hash-checked by scripts/fetch-corpus.sh
# on the host and mounted at /app/corpus), and no secret is baked in: the Gemini key and the demo
# password come from the environment at run time.

# ---- 1. build the jar --------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# dependencies first, so a source change does not download them again
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:resolve dependency:resolve-plugins

COPY src src
# tests run in CI and on the developer machine (./mvnw verify); the image build only packages
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && cp target/agent-framework-*.jar /app.jar

# ---- 2. runtime ----------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# The model: downloaded from the pinned Hugging Face commit and verified by sha256 here, at build time.
# The build fails on a mismatch. At run time ModelFiles finds the files with the right hash and does
# not download anything.
COPY scripts/fetch-model.sh /tmp/fetch-model.sh
RUN MODEL_DIR=/app/models/ms-marco-MiniLM-L-6-v2 bash /tmp/fetch-model.sh && rm /tmp/fetch-model.sh

COPY --from=build /app.jar /app/app.jar

# Runs as a numeric non-root user (compose sets it to the owner of the mounted corpus folder).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
USER 1000:1000

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=8 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
