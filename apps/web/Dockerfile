# Dockerfile фронтенда epd-web (Next.js 15, standalone-режим).
# Сборка: docker build -t epd/web:0.1.0 apps/web
# syntax=docker/dockerfile:1

# ---------- Stage 1: зависимости ----------
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# ---------- Stage 2: сборка ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Адреса backend-сервисов для rewrites (вычисляются на этапе сборки Next).
ARG MD_API_URL=http://localhost:8081
ARG WB_API_URL=http://localhost:8082
# Публичный (браузерный) адрес Keycloak — инлайнится в клиентский бандл (NEXT_PUBLIC_*).
ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8180
ENV MD_API_URL=${MD_API_URL} \
    WB_API_URL=${WB_API_URL} \
    NEXT_PUBLIC_KEYCLOAK_URL=${NEXT_PUBLIC_KEYCLOAK_URL} \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---------- Stage 3: runtime ----------
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# Непривилегированный пользователь.
RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

# standalone-вывод содержит минимальный сервер и нужные node_modules.
COPY --from=build /app/.next/standalone ./
# Статику (.next/static) standalone не включает — копируем отдельно.
COPY --from=build /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
