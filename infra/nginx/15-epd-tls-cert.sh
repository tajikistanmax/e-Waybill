#!/bin/sh
# Сертификат HTTPS. Настоящий сертификат кладётся в infra/nginx/certs/ как tls.crt (с цепочкой)
# и tls.key — тогда он и используется. Если их нет, создаётся САМОПОДПИСАННЫЙ сертификат на адрес
# EPD_TLS_HOST (IP или имя стенда): для пилота во внутренней сети, браузер покажет предупреждение.
# Для промышленной эксплуатации — сертификат удостоверяющего центра (см. infra/DEPLOY.md).
set -eu

CERT_DIR=/etc/nginx/certs
CRT="$CERT_DIR/tls.crt"
KEY="$CERT_DIR/tls.key"

if [ -s "$CRT" ] && [ -s "$KEY" ]; then
    echo "epd-tls: используется сертификат из $CERT_DIR"
    exit 0
fi

mkdir -p "$CERT_DIR"
HOST="${EPD_TLS_HOST:-localhost}"
case "$HOST" in
    *[!0-9.]*) SAN="DNS:$HOST" ;;
    *) SAN="IP:$HOST" ;;
esac
SAN="$SAN,DNS:localhost,IP:127.0.0.1"

# 397 дней — максимальный срок, который принимают браузеры.
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 397 \
    -subj "/CN=$HOST/O=e-Rohkhat (self-signed, pilot)" \
    -addext "subjectAltName=$SAN" \
    -keyout "$KEY" -out "$CRT" 2>/dev/null
chmod 600 "$KEY"
echo "epd-tls: создан САМОПОДПИСАННЫЙ сертификат для $HOST ($SAN). Для прода замените на сертификат УЦ."
