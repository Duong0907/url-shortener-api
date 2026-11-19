# ---- Build stage: compile and package the jar ----
# The jar is platform-independent, so build natively (no QEMU) even when targeting another arch.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.13_9-jdk-ubi10-minimal AS build
WORKDIR /workspace

# CVE-2026-114938 (Java4Shell): disable the AVX-512 Base64 decoder intrinsic in
# the build JVM. Remove once the base image is bumped to a fixed JDK (21.0.13.1+).
ENV MAVEN_OPTS="-XX:-UseBASE64Intrinsics"

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
FROM eclipse-temurin:21.0.13_9-jre-ubi10-minimal
WORKDIR /app

RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /workspace/app.jar app.jar

EXPOSE 8080

# CVE-2026-114938 (Java4Shell): disable the AVX-512 Base64 decoder intrinsic on
# x86-64 hosts with AVX-512 VBMI. Remove once the base image ships a fixed JDK.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "app.jar"]
