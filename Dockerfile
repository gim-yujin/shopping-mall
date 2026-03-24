# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Gradle Wrapper + 의존성 캐시 레이어 분리
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 후 bootJar 빌드 (테스트는 CI에서 이미 통과)
COPY src/ src/
COPY config/ config/
RUN ./gradlew bootJar --no-daemon -x test -x check

# ── Stage 2: Runtime ───────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system appuser && useradd --system --gid appuser appuser

COPY --from=builder /app/build/libs/*.jar app.jar

RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
