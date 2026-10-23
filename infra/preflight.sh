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
    for v in DB_PASSWORD MINIO_PASSWORD SERVICE_ACCOUNT_PASSWORD MEDDATA_ENCRYPTION_KEY QR_SIGNING_KEY BACKUP_ENCRYPTION_KEY EPD_TLS_HOST; do
        if [ -n "$(envval "$v")" ]; then ok "$v задан"; else err "$v не задан"; fi
    done
    dbpw=$(envval DB_PASSWORD)
    if [ -z "$dbpw" ] || [ "$dbpw" = "epd_dev_password" ]; then
        err "DB_PASSWORD пустой или задан открытый dev-пароль; задайте отдельный случайный пароль"
    elif [ ${#dbpw} -lt 24 ]; then
        err "DB_PASSWORD короче 24 символов"
    else
        ok "DB_PASSWORD — задан, длина допустима"
    fi
    miniopw=$(envval MINIO_PASSWORD)
    if [ -z "$miniopw" ] || [ "$miniopw" = "epd_dev_password" ]; then
        err "MINIO_PASSWORD пустой или задан открытый dev-пароль; задайте отдельный случайный пароль"
    elif [ ${#miniopw} -lt 24 ]; then
        err "MINIO_PASSWORD короче 24 символов"
    else
        ok "MINIO_PASSWORD — задан, длина допустима"
    fi
    if [ -n "$dbpw" ] && [ "$dbpw" = "$miniopw" ]; then
        err "DB_PASSWORD и MINIO_PASSWORD должны быть разными"
    fi
    pw=$(envval PAYMENT_WEBHOOK_SECRET)
    sp=$(envval SERVICE_ACCOUNT_PASSWORD)
    if [ -n "$sp" ] && [ ${#sp} -lt 16 ]; then err "SERVICE_ACCOUNT_PASSWORD короче 16 символов"; fi
    signing_mode=$(envval SIGNING_MODE)
    case "$signing_mode" in
        cades)
            crypto_url=$(envval SIGNING_CRYPTO_URL)
            if [ -n "$crypto_url" ]; then ok "SIGNING_MODE=cades, адрес Crypto Service задан"
            else err "SIGNING_CRYPTO_URL не задан при SIGNING_MODE=cades"; fi
            ;;
        stub|"") err "SIGNING_MODE не равен cades — заглушка SHA-256 не является юридически значимой ЭП" ;;
        *) err "неподдерживаемый SIGNING_MODE (ожидается cades)" ;;
    esac
    # 23.10.2026: та же логика, что у SIGNING_MODE — заглушка единой платформы Минтранса
    # (налоговая/ГАИ) в проде недопустима (см. ProdStubGuard в master-data-service).
    unified_mode=$(envval UNIFIED_PLATFORM_MODE)
    case "$unified_mode" in
        http)
            unified_url=$(envval UNIFIED_PLATFORM_URL)
            if [ -n "$unified_url" ]; then ok "UNIFIED_PLATFORM_MODE=http, адрес платформы задан"
            else err "UNIFIED_PLATFORM_URL не задан при UNIFIED_PLATFORM_MODE=http"; fi
            ;;
        stub|"") err "UNIFIED_PLATFORM_MODE не равен http — заглушка налоговой/ГАИ недопустима в проде" ;;
        *) err "неподдерживаемый UNIFIED_PLATFORM_MODE (ожидается http)" ;;
    esac
    # Открытые каналы без токена — допустимы только явным решением (dev/тестовый стенд).
    # На боевом сервере AGGREGATOR_OPEN/DOCS_OPEN должны быть false (или не заданы — тот же
    # эффект: SecurityConfig по умолчанию закрыт с 23.10.2026).
    agg_open=$(envval AGGREGATOR_OPEN)
    if [ "$agg_open" = "true" ]; then
        err "AGGREGATOR_OPEN=true — API агрегатора (/api/v1/aggregator/**) открыт без токена; для прода задайте false"
    else
        ok "AGGREGATOR_OPEN — закрыт (false/не задан)"
    fi
    docs_open=$(envval DOCS_OPEN)
    if [ "$docs_open" = "true" ]; then
        err "DOCS_OPEN=true — Swagger/OpenAPI открыт анониму (поверхность атаки); для прода задайте false"
    else
        ok "DOCS_OPEN — закрыт (false/не задан)"
    fi
    # Секрет вебхука оплаты обязателен ТОЛЬКО когда оплата включена (PAYMENT_ENABLED;
    # в infra/docker-compose.prod.yml default — true, т.е. пустое значение в .env = включено).
    payment_enabled=$(envval PAYMENT_ENABLED)
    [ -z "$payment_enabled" ] && payment_enabled=true
    if [ "$payment_enabled" = "true" ]; then
        case "$pw" in
            ""|dev_webhook_secret|epd_webhook_dev_secret)
                err "PAYMENT_ENABLED=true (или не задан — дефолт true), а PAYMENT_WEBHOOK_SECRET пустой/из репозитория"
                ;;
            *)
                if [ ${#pw} -lt 16 ]; then err "PAYMENT_WEBHOOK_SECRET короче 16 символов"; else ok "PAYMENT_WEBHOOK_SECRET — задан, длина допустима"; fi
                ;;
        esac
    else
        ok "PAYMENT_ENABLED=false — секрет вебхука не требуется"
    fi
    # QR_SIGNING_KEY — проверка задан/не задан уже выполнена выше (цикл по списку секретов),
    # здесь — предметная проверка связности с режимом подписи: пустой ключ допустим только
    # для SIGNING_MODE=stub, а в проде он обязателен к cades (см. выше), т.е. фактически
    # всегда должен быть задан на боевом сервере.
    # Учётные записи: боевая установка — один администратор из .env, без демо-учёток из репозитория.
    if [ -n "$(envval AUTH_BOOTSTRAP_REALM_FILE)" ]; then
        err "AUTH_BOOTSTRAP_REALM_FILE задан — при пустой базе будут созданы демо/QA-учётки с паролями из репозитория. На боевом сервере — оставить пустым"
    fi
    if [ -n "$(envval EPD_AUTH_ADMIN_USERNAME)" ] && [ -n "$(envval EPD_AUTH_ADMIN_PASSWORD)" ]; then
        ok "начальный администратор (EPD_AUTH_ADMIN_*) задан"
    elif [ -z "$(envval AUTH_BOOTSTRAP_REALM_FILE)" ]; then
        warn "EPD_AUTH_ADMIN_USERNAME/PASSWORD не заданы: на установке с нуля войти в платформу будет некому"
    fi
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
