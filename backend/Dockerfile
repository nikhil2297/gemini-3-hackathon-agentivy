# =========================
# Build stage
# =========================
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle* .
COPY settings.gradle* .

RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test


# =========================
# Runtime stage
# =========================
FROM mcr.microsoft.com/playwright/java:v1.57.0-jammy

WORKDIR /app

# ---- Install Node.js + npm (ONLY for Angular tooling) ----
RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    npm --version && node --version && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_OPTS="\
-XX:+UseContainerSupport \
-XX:MaxRAMPercentage=75.0 \
-Djava.security.egd=file:/dev/./urandom \
-Dserver.tomcat.connection-timeout=1800000 \
-Dspring.mvc.async.request-timeout=1800000"

ENV NODE_NO_WARNINGS=1

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
