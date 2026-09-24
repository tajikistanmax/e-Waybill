#!/bin/sh
# Предпроверка стенда перед выкладкой платформы (infra/DEPLOY.md, шаг 3½).
# Запуск на сервере из каталога infra:   sh preflight.sh
# Ничего не меняет — только читает .env, сертификат, часы, диск и порты.
# Код выхода = число ОШИБОК (0 — можно выкладывать). ПРЕДУПРЕЖДЕНИЯ выкладку не блокируют.
set -u

cd "$(dirname "$0")" || exit 99
ERR=0
WARN=0
ok()   { printf '  [ OK ] %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1"; WARN=$((WARN + 1)); }
err()  { printf '  [FAIL] %s\n' "$1"; ERR=$((ERR + 1)); }

# Значение переменной из .env: первая строка KEY=..., до первого «=» — имя, остальное — значение
# (base64 содержит «=»); кавычки по краям снимаются.
envval() {
    [ -f .env ] || { printf ''; return; }
    tr -d '\r' < .env | sed -n "s/^[[:space:]]*$1[[:space:]]*=//p" | head -n 1 | sed 's/^"\(.*\)"$/\1/; s/^'"'"'\(.*\)'"'"'$/\1/'
}

echo "== Docker"
if command -v docker >/dev/null 2>&1; then
    ok "docker: $(docker --version 2>/dev/null)"
    if docker compose version >/dev/null 2>&1; then ok "$(docker compose version 2>/dev/null)"; else err "нет docker compose v2"; fi
else
    err "docker не найден"
fi

echo "== Секреты (infra/.env)"
if [ ! -f .env ]; then
    err "нет файла infra/.env — секреты стенда (см. DEPLOY.md, раздел 2)"
else
    for v in SERVICE_ACCOUNT_PASSWORD MEDDATA_ENCRYPTION_KEY QR_SIGNING_KEY PAYMENT_WEBHOOK_SECRET BACKUP_ENCRYPTION_KEY EPD_TLS_HOST; do
        if [ -n "$(envval "$v")" ]; then ok "$v задан"; else err "$v не задан"; fi
    done
    val=$(envval DB_PASSWORD)
    if [ -z "$val" ] || [ "$val" = "epd_dev_password" ]; then
        warn "DB_PASSWORD: пароль из репозитория (epd_dev_password). Для прода — свой; у уже созданной БД меняется через ALTER USER, не только в .env"
    else
        ok "DB_PASSWORD свой"
    fi
    val=$(envval MINIO_PASSWORD)
    if [ -z "$val" ] || [ "$val" = "epd_dev_password" ]; then
        warn "MINIO_PASSWORD: пароль из репозитория (epd_dev_password). Для прода — свой (у уже созданного хранилища меняется через mc admin)"
    else
        ok "MINIO_PASSWORD свой"
    fi
    pw=$(envval PAYMENT_WEBHOOK_SECRET)
    case "$pw" in
        ""|dev_webhook_secret|epd_webhook_dev_secret) err "PAYMENT_WEBHOOK_SECRET пустой или из репозитория — waybill не запустится при включённой оплате" ;;
        *) if [ ${#pw} -lt 16 ]; then err "PAYMENT_WEBHOOK_SECRET короче 16 символов"; fi ;;
    esac
    sp=$(envval SERVICE_ACCOUNT_PASSWORD)
    if [ -n "$sp" ] && [ ${#sp} -lt 16 ]; then warn "SERVICE_ACCOUNT_PASSWORD короче 16 символов"; fi
    mk=$(envval MEDDATA_ENCRYPTION_KEY)
    if [ -n "$mk" ]; then
        n=$(printf '%s' "$mk" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
        if [ "$n" = "32" ]; then ok "MEDDATA_ENCRYPTION_KEY — 256 бит"; else err "MEDDATA_ENCRYPTION_KEY: ожидается base64 от 32 байт, получено $n (НЕ перегенерировать, если медданные уже есть!)"; fi
    fi
    if grep -Eq '^[[:space:]]*(KC_|KEYCLOAK_|NEXT_PUBLIC_KEYCLOAK)' .env; then
        warn "в .env остались переменные Keycloak — не используются с 24.09.2026, можно удалить"
    fi
fi

echo "== Сертификат HTTPS (infra/nginx/certs)"
host=$(envval EPD_TLS_HOST)
hsts=$(envval EPD_HSTS)
crt=nginx/certs/tls.crt
if [ -s "$crt" ] && [ -s nginx/certs/tls.key ]; then
    if command -v openssl >/dev/null 2>&1; then
        subj=$(openssl x509 -in "$crt" -noout -subject 2>/dev/null)
        issuer=$(openssl x509 -in "$crt" -noout -issuer 2>/dev/null)
        if openssl x509 -in "$crt" -noout -checkend 2592000 >/dev/null 2>&1; then ok "срок действия — больше 30 дней"; else err "сертификат истекает в ближайшие 30 дней или уже истёк"; fi
        if [ -n "$host" ]; then
            if openssl x509 -in "$crt" -noout -text 2>/dev/null | grep -q -e "DNS:$host" -e "IP Address:$host"; then ok "сертификат выдан на $host"; else err "в сертификате нет адреса $host (EPD_TLS_HOST)"; fi
        fi
        selfsigned=0
        [ "${subj#subject=}" = "${issuer#issuer=}" ] && selfsigned=1
        if [ "$selfsigned" = 1 ]; then
            warn "сертификат самоподписанный — для пилота; для прода нужен сертификат УЦ"
            [ -n "$hsts" ] && err "EPD_HSTS включён при самоподписанном сертификате — браузеры заблокируют стенд"
        else
            ok "сертификат выдан УЦ: ${issuer#issuer=}"
        fi
    else
        warn "нет openssl — сертификат не проверен"
    fi
else
    warn "сертификата нет — прокси создаст самоподписанный на ${host:-localhost} (пилот)"
    [ -n "$hsts" ] && err "EPD_HSTS включён, а сертификата УЦ нет — браузеры заблокируют стенд"
fi

echo "== Время (второй фактор входа)"
if command -v timedatectl >/dev/null 2>&1; then
    if [ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" = "yes" ]; then ok "часы синхронизированы (NTP)"; else err "часы не синхронизированы — коды 2FA не будут приниматься (timedatectl set-ntp true)"; fi
else
    warn "нет timedatectl — проверьте синхронизацию времени вручную"
fi

echo "== Диск"
root=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null)
[ -n "$root" ] || root=/var/lib/docker
[ -d "$root" ] || root=/
free=$(df -Pk "$root" 2>/dev/null | awk 'NR==2 {print int($4/1024/1024)}')
if [ -n "$free" ]; then
    if [ "$free" -ge 10 ]; then ok "свободно ${free} ГБ ($root)"; else err "свободно ${free} ГБ на $root — нужно не меньше 10 ГБ (образы, БД, копии)"; fi
fi

echo "== Порты 80 / 443 / 8443"
if command -v ss >/dev/null 2>&1; then
    for p in 80 443 8443; do
        who=$(ss -ltnp 2>/dev/null | awk -v p=":$p" '$4 ~ p"$" {print $6}' | head -n 1)
        if [ -z "$who" ]; then ok "порт $p свободен"
        elif echo "$who" | grep -q docker; then ok "порт $p занят Docker (прокси платформы)"
        else err "порт $p занят другим процессом: $who"; fi
    done
else
    warn "нет ss — порты не проверены"
fi

echo
echo "Итог: ошибок $ERR, предупреждений $WARN"
[ "$ERR" -eq 0 ] && echo "Можно выкладывать (DEPLOY.md, раздел 4)." || echo "Исправьте ошибки перед выкладкой."
exit "$ERR"
