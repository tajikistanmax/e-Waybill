# Универсальный Dockerfile для сервисов Gradle-монорепо epd-backend.
# Выбор сервиса — через build-arg SERVICE (master-data-service | waybill-service).
# Сборка:
#   docker build -f apps/backend/Dockerfile --build-arg SERVICE=master-data-service -t epd/master-data:0.1.0 apps/backend
#   docker build -f apps/backend/Dockerfile --build-arg SERVICE=waybill-service     -t epd/waybill:0.1.0     apps/backend
# syntax=docker/dockerfile:1

# ---------- Stage 1: сборка bootJar ----------
# В репозитории нет unix-скрипта gradlew (только gradlew.bat), поэтому используем
# официальный образ gradle нужной версии (совпадает с wrapper 8.14.3).
FROM gradle:8.14.3-jdk21 AS build
ARG SERVICE
WORKDIR /workspace

# 1) Сначала только build-файлы — слой с зависимостями кэшируется, пока они не меняются.
COPY settings.gradle.kts build.gradle.kts ./
COPY master-data-service/build.gradle.kts master-data-service/build.gradle.kts
COPY waybill-service/build.gradle.kts waybill-service/build.gradle.kts

# Прогрев кэша зависимостей и Spring Boot plugin (без src). Не критично, если часть недоступна.
RUN gradle :${SERVICE}:dependencies --no-daemon --console=plain || true

# 2) Теперь исходники (меняются чаще всего — попадают в отдельный слой).
COPY . .

# 3) Сборка исполняемого bootJar выбранного сервиса без тестов.
#    bootJar кладёт и *-plain.jar (не исполняемый) — выбираем именно исполняемый.
RUN gradle :${SERVICE}:bootJar -x test --no-daemon --console=plain \
 && cp "$(find ${SERVICE}/build/libs -name '*.jar' ! -name '*-plain.jar')" /workspace/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Непривилегированный пользователь.
RUN groupadd --system epd \
 && useradd --system --gid epd --home-dir /app --shell /usr/sbin/nologin epd

COPY --from=build /workspace/app.jar /app/app.jar

USER epd

# Порт задаётся в application.yml (8081 master-data / 8082 waybill); EXPOSE — только документация.
EXPOSE 8081 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
