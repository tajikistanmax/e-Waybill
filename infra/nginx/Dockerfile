# Обратный прокси платформы: единственная дверь снаружи (HTTPS 443, портал проверки QR 8443,
# HTTP 80 — только перенаправление на HTTPS). Службы web / public / API наружу не публикуются.
FROM nginx:1.27-alpine

# openssl — для самоподписанного сертификата, если настоящий не положили (пилот по IP).
RUN apk add --no-cache openssl \
    && rm -f /etc/nginx/conf.d/default.conf

COPY snippets/ /etc/nginx/snippets/
COPY templates/ /etc/nginx/templates/
COPY 15-epd-tls-cert.sh /docker-entrypoint.d/15-epd-tls-cert.sh
RUN chmod +x /docker-entrypoint.d/15-epd-tls-cert.sh

# Подставлять в шаблоны только переменные платформы (EPD_*): остальные $... — переменные nginx.
ENV NGINX_ENVSUBST_FILTER=^EPD_
