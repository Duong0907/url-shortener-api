# ---- Build stage: compile and package the jar ----
# The jar is platform-independent, so build natively (no QEMU) even when targeting another arch.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# Build
COPY lombok.config ./
COPY src src
# Tests run in CI (they need Testcontainers/Docker), so skip them here.
RUN ./mvnw -B package -DskipTests \
    && cp target/*.jar app.jar

# ---- Runtime stage: JRE only ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /workspace/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
