# syntax=docker/dockerfile:1.7
# =============================================================================
# document-service — binary document store (port 8181)
# Content is written to /var/lib/documents (mount a volume/PVC on it).
# =============================================================================

# ----------------------------- build stage -----------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY core-app/pom.xml core-app/pom.xml
COPY document-service/pom.xml document-service/pom.xml
COPY bff/pom.xml bff/pom.xml

COPY config/ config/

COPY document-service/src document-service/src

RUN mvn -B -ntp -pl document-service -am -DskipTests package

# ---------------------------- runtime stage ----------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 10001 -S app \
  && adduser -u 10001 -S -G app -h /app app \
  && mkdir -p /var/lib/documents \
  && chown -R 10001:10001 /var/lib/documents

WORKDIR /app

COPY --from=build /workspace/document-service/target/*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport" \
  SERVER_PORT=8181 \
  DOCUMENTS_STORAGE_PATH=/var/lib/documents

VOLUME ["/var/lib/documents"]

USER 10001:10001

EXPOSE 8181

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8181/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
