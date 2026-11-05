# syntax=docker/dockerfile:1.7
# =============================================================================
# core-app — claims / policies service (port 8180)
# Multi-stage: Maven build, then a minimal non-root Temurin JRE runtime.
# Verified statically in the dev sandbox (no Docker there); first real build in CI.
# =============================================================================

# ----------------------------- build stage -----------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /workspace

# POMs first: dependency resolution is cached independently of the sources.
COPY pom.xml ./
COPY core-app/pom.xml core-app/pom.xml
COPY document-service/pom.xml document-service/pom.xml
COPY bff/pom.xml bff/pom.xml

# Checkstyle resolves ${maven.multiModuleProjectDirectory}/config at build time.
COPY config/ config/

COPY core-app/src core-app/src

RUN mvn -B -ntp -pl core-app -am -DskipTests package

# ---------------------------- runtime stage ----------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Dedicated unprivileged account (no login shell, fixed uid).
RUN addgroup -g 10001 -S app \
  && adduser -u 10001 -S -G app -h /app app

WORKDIR /app

COPY --from=build /workspace/core-app/target/*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport" \
  SERVER_PORT=8180

USER 10001:10001

EXPOSE 8180

# busybox wget: the Temurin JRE image has no curl.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8180/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
