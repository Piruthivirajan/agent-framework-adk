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
# tests run on the developer machine (./mvnw verify); the image build only packages
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Dmaven.test.skip=true package

# Extract the executable jar into a plain classpath layout (the application jar plus lib/*.jar). This is
# Spring Boot's recommended container layout, and it is required here: under Boot's nested-jar loader
# (`java -jar` on the fat jar) the MCP SDK silently returns zero tools (found by the compose smoke test),
# while the same jars on a normal classpath work.
RUN java -Djarmode=tools -jar target/agent-framework-*.jar extract --destination /extracted

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

COPY --from=build /extracted/lib /app/lib
COPY --from=build /extracted/agent-framework-*.jar /app/app.jar

# Runs as a numeric non-root user (compose sets it to the owner of the mounted corpus folder).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
USER 1000:1000

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=8 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
