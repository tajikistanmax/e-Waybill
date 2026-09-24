# Dockerfile веб-консоли epd-web (Next.js 15, standalone).
#
# ВАЖНО: контекст сборки — КОРЕНЬ МОНОРЕПО (не apps/web), т.к. приложение зависит от
# workspace-пакета @epd/shared (packages/shared) через tsconfig-алиас @/lib/* и импорт
# @epd/shared/ui. Сборка из контекста apps/web физически не видит packages/shared.
#   docker build -f apps/web/Dockerfile -t epd/web:0.1.0 .
# (в compose: build.context = .. , dockerfile = apps/web/Dockerfile)
#
# Профиль сборки задаётся build-arg NEXT_PUBLIC_APP_PROFILE (all по умолчанию | waybill | oversight).
# syntax=docker/dockerfile:1

# ---------- Stage 1: сборка ----------
FROM node:22-alpine AS build
WORKDIR /repo
# Сначала манифесты всех workspace-пакетов + корневой lock — слой npm ci кэшируется, пока
# зависимости не меняются. npm ci выполняем ЗДЕСЬ (не в отдельной deps-стадии): часть пакетов
# npm кладёт не в корневой node_modules, а во вложенный apps/web/node_modules — при копировании
# только корневого node_modules из отдельной стадии они терялись (Module not found: jsqr/…).
COPY package.json package-lock.json ./
COPY apps/web/package.json ./apps/web/
COPY apps/public/package.json ./apps/public/
COPY packages/shared/package.json ./packages/shared/
RUN npm ci
# Теперь исходники (меняются чаще — отдельный слой, npm ci не пересобирается).
COPY packages/shared ./packages/shared
COPY apps/web ./apps/web

# Профиль приложения (пишет в distDir .next / .next-waybill / .next-oversight).
ARG NEXT_PUBLIC_APP_PROFILE=
# Адреса backend-сервисов для rewrites (вычисляются на этапе сборки Next).
ARG MD_API_URL=http://localhost:8081
ARG WB_API_URL=http://localhost:8082
# Публичные (браузерные) адреса — инлайнятся в клиентский бандл (NEXT_PUBLIC_*).
ARG NEXT_PUBLIC_VERIFY_BASE_URL=
ARG NEXT_PUBLIC_WAYBILL_URL=
ARG NEXT_PUBLIC_OVERSIGHT_URL=
ENV NEXT_PUBLIC_APP_PROFILE=${NEXT_PUBLIC_APP_PROFILE} \
    MD_API_URL=${MD_API_URL} \
    WB_API_URL=${WB_API_URL} \
    NEXT_PUBLIC_VERIFY_BASE_URL=${NEXT_PUBLIC_VERIFY_BASE_URL} \
    NEXT_PUBLIC_WAYBILL_URL=${NEXT_PUBLIC_WAYBILL_URL} \
    NEXT_PUBLIC_OVERSIGHT_URL=${NEXT_PUBLIC_OVERSIGHT_URL} \
    NEXT_TELEMETRY_DISABLED=1

WORKDIR /repo/apps/web
RUN npm run build

# Собираем финальное дерево в фиксированный /out (COPY не умеет shell-переменные, а distDir
# зависит от профиля: .next / .next-waybill / .next-oversight). standalone НЕ включает static и
# public — «запекаем» их в дерево standalone по путям, которые ждёт сервер:
#   <standalone>/apps/web/<distDir>/static   (сервер отдаёт /_next/static именно из <distDir>/static,
#                                             поэтому для профиля это .next-<profile>/static, не .next)
#   <standalone>/apps/web/public
# @epd/shared в standalone отдельным пакетом не нужен — он вкомпилирован в чанки.
RUN set -eux; \
    DIST=".next"; if [ -n "${NEXT_PUBLIC_APP_PROFILE}" ]; then DIST=".next-${NEXT_PUBLIC_APP_PROFILE}"; fi; \
    APP="${DIST}/standalone/apps/web"; \
    mkdir -p "${APP}/${DIST}/static"; \
    cp -r "${DIST}/static/." "${APP}/${DIST}/static/"; \
    cp -r public "${APP}/public"; \
    mkdir -p /out; \
    cp -r "${DIST}/standalone" /out/standalone

# ---------- Stage 2: runtime ----------
FROM node:22-alpine AS runtime
WORKDIR /repo
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

# Готовое дерево standalone (сервер + node_modules + static + public по нужным путям).
COPY --from=build /out/standalone ./

USER nextjs
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
