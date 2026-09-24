# Dockerfile публичного портала проверки ПЛ по QR epd-public (Next.js 15, standalone).
#
# Публичный контур (интернет, аноним): единственная функция — /verify/{jws}. Ни авторизации,
# ни служебного кода — минимальная поверхность атаки. Обращается только к публичному
# эндпоинту waybill-сервиса (/wb-api/.../verify).
#
# ВАЖНО: контекст сборки — КОРЕНЬ МОНОРЕПО (не apps/public): приложение зависит от @epd/shared.
#   docker build -f apps/public/Dockerfile -t epd/public:0.1.0 .
# (в compose: build.context = .. , dockerfile = apps/public/Dockerfile)
# syntax=docker/dockerfile:1

# ---------- Stage 1: сборка ----------
FROM node:22-alpine AS build
WORKDIR /repo
# npm ci выполняем здесь (не в отдельной стадии): часть пакетов npm кладёт во вложенные
# node_modules workspace-пакетов, и копирование только корневого node_modules их теряло.
COPY package.json package-lock.json ./
COPY apps/web/package.json ./apps/web/
COPY apps/public/package.json ./apps/public/
COPY packages/shared/package.json ./packages/shared/
RUN npm ci
COPY packages/shared ./packages/shared
COPY apps/public ./apps/public

# Адреса служб для rewrite (вычисляются на этапе сборки Next): waybill — проверка QR,
# master-data — три публичных справочных точки (названия видов/статусов листа, контакты).
ARG WB_API_URL=http://localhost:8082
ARG MD_API_URL=http://localhost:8081
ENV WB_API_URL=${WB_API_URL} \
    MD_API_URL=${MD_API_URL} \
    NEXT_TELEMETRY_DISABLED=1

WORKDIR /repo/apps/public
RUN npm run build

# «Запекаем» static в дерево standalone по пути, который ждёт сервер (<standalone>/apps/public/
# .next/static) — так же, как в apps/web/Dockerfile. Плюс public/ (локальные шрифты Inter/
# JetBrains Mono, см. changelog/2026-09-23-локальные-шрифты-и-карты.md — без Google Fonts CDN).
RUN set -eux; \
    APP=".next/standalone/apps/public"; \
    mkdir -p "${APP}/.next/static"; \
    cp -r ".next/static/." "${APP}/.next/static/"; \
    cp -r public "${APP}/public"; \
    mkdir -p /out; \
    cp -r ".next/standalone" /out/standalone

# ---------- Stage 2: runtime ----------
FROM node:22-alpine AS runtime
WORKDIR /repo
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3002 \
    HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

COPY --from=build /out/standalone ./

USER nextjs
EXPOSE 3002
CMD ["node", "apps/public/server.js"]
