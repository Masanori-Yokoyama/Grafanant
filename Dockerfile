# Stage 1: Build stage using Gradle 8.7 on JDK 21 Alpine
FROM gradle:8.7-jdk21-alpine AS builder
WORKDIR /workspace/app

# Copy build config and source files
COPY build.gradle settings.gradle /workspace/app/
COPY src /workspace/app/src

# Package application jar (excluding tests for fast build; integration testing runs in app stage)
RUN gradle bootJar --no-daemon -x test

# Stage 2: Runtime stage using lightweight Eclipse Temurin 21 JRE Alpine
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy built JAR artifact from builder stage
COPY --from=builder /workspace/app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
