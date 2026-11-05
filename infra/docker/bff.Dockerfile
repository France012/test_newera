# syntax=docker/dockerfile:1.7
# =============================================================================
# bff — backend for frontend (port 8185)
# Stateless: no datasource, downstream URLs come from the environment.
# =============================================================================

# ----------------------------- build stage -----------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY core-app/pom.xml core-app/pom.xml
COPY document-service/pom.xml document-service/pom.xml
COPY bff/pom.xml bff/pom.xml

COPY config/ config/

COPY bff/src bff/src

RUN mvn -B -ntp -pl bff -am -DskipTests package

# ---------------------------- runtime stage ----------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -g 10001 -S app \
  && adduser -u 10001 -S -G app -h /app app

WORKDIR /app

COPY --from=build /workspace/bff/target/*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport" \
  SERVER_PORT=8185

USER 10001:10001

EXPOSE 8185

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8185/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
