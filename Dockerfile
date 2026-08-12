# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing source does not re-download
# the world on every rebuild.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Testcontainers needs a Docker daemon, unavailable here; tests run in CI.
RUN mvn -B clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S payflow && adduser -S payflow -G payflow

COPY --from=build /build/target/wallet-service-*.jar app.jar
RUN chown payflow:payflow app.jar
USER payflow

EXPOSE 8082

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8082/actuator/health/liveness || exit 1

# Shell form so $JAVA_OPTS is expanded; exec so the JVM stays PID 1 and receives
# SIGTERM directly for a graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
