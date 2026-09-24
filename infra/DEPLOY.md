# Выкладка платформы на стенд (пилот / промышленная эксплуатация)

Инструкция для администратора сервера. Пример — стенд `10.10.29.70` (пользователь `behruz`,
каталог `~/e-rohkhat`). Все команды — на сервере, в Linux-оболочке.

Что получится: снаружи открыт только обратный прокси — **HTTPS 443** (платформа),
**8443** (портал проверки QR), **80** (перенаправление на HTTPS). Службы, база, Kafka, MinIO
наружу не публикуются.

---

## 0. Один раз: подготовка сервера

1. **Docker Engine + Compose v2** (`docker compose version` ≥ 2.20).
2. **Точное время (NTP).** Второй фактор входа (коды из телефона) не принимается, если часы
   сервера расходятся больше чем на 30 секунд: `timedatectl` → `System clock synchronized: yes`.
3. **Сетевой экран:** открыть входящие 80, 443, 8443. Порты 3000, 3002, 8180, 8081, 8082 больше
   не нужны — закрыть.
4. **Сертификат HTTPS** (см. раздел 3).

## 1. Перед каждой выкладкой — резервная копия

```bash
cd ~/e-rohkhat/infra
# внеочередная копия обеих БД в том backups (шифруется BACKUP_ENCRYPTION_KEY)
BACKUP_RUN_NOW=1 docker compose -f docker-compose.prod.yml up -d --force-recreate backup
docker logs --tail 20 epd-prod-backup          # дождаться «backup done»
```

Запомнить текущую версию, чтобы было куда откатиться: `git rev-parse --short HEAD`.

## 2. Секреты — `infra/.env`

Файл в git не попадает. Обязательные переменные (сгенерировать `openssl rand -base64 24`,
ключи шифрования — `openssl rand -base64 32`):

| Переменная | Назначение | Замечание |
|---|---|---|
| `DB_PASSWORD` | пароль PostgreSQL | если база уже создана, смена здесь пароль не меняет — нужен `ALTER USER` |
| `MINIO_PASSWORD` | хранилище файлов | |
| `PAYMENT_WEBHOOK_SECRET` | подпись вебхука оплаты | |
| `QR_SIGNING_KEY` | ключ подписи QR (EC P-256 JWK) | **не менять** — выданные QR перестанут проверяться |
| `MEDDATA_ENCRYPTION_KEY` | шифрование медпоказателей | **никогда не менять** — сохранённые медданные станут нечитаемыми |
| `BACKUP_ENCRYPTION_KEY` | шифрование резервных копий | хранить копию отдельно от сервера |
| `SERVICE_ACCOUNT_PASSWORD` | служебная учётка служб | **новая с 23.09**; без неё compose не стартует |
| `AGGREGATOR_PASSWORD` | учётка внешнего агрегатора | новая с 24.09; пусто — учётка не создаётся |
| `EPD_TLS_HOST` | адрес стенда, напр. `10.10.29.70` | на него выпускается самоподписанный сертификат и строится ссылка QR |

Необязательные: `EPD_HSTS` (только с настоящим сертификатом: `max-age=31536000`),
`EPD_CSP_IMG_EXTRA` (внешний сервер тайлов карты, если задан `NEXT_PUBLIC_MAP_TILES_URL`),
`NEXT_PUBLIC_VERIFY_BASE_URL` (по умолчанию `https://<EPD_TLS_HOST>:8443`).

Переменные Keycloak (`KC_*`, `NEXT_PUBLIC_KEYCLOAK_URL`, `KEYCLOAK_*`) больше не используются —
их можно удалить из `.env`.

## 3. Сертификат HTTPS

- **Промышленная эксплуатация:** сертификат удостоверяющего центра на имя/адрес стенда.
  Положить в `infra/nginx/certs/`: `tls.crt` (сертификат + цепочка УЦ) и `tls.key` (ключ,
  права `600`). Каталог в git не попадает.
- **Пилот во внутренней сети:** если файлов нет, прокси сам создаст самоподписанный
  сертификат на `EPD_TLS_HOST` (срок 397 дней). Браузер покажет предупреждение. Чтобы его не
  было, раздать `infra/nginx/certs/tls.crt` на рабочие места как доверенный корневой
  (Windows: `certmgr.msc` → «Доверенные корневые центры сертификации», или групповой
  политикой). **HSTS с самоподписанным сертификатом не включать.**
- Замена сертификата: положить новые файлы и `docker compose -f docker-compose.prod.yml restart proxy`.

## 4. Выкладка

```bash
cd ~/e-rohkhat
git fetch origin
git checkout main && git pull --ff-only
cd infra
docker compose -f docker-compose.prod.yml up -d --build --remove-orphans
```

`--remove-orphans` удаляет контейнер Keycloak, оставшийся от прежних версий (из стека убран
24.09.2026; вход — в самой платформе). Сборка web занимает ~10 минут.

Миграции баз применяются автоматически при старте служб (Flyway). Проверить:

```bash
docker logs epd-prod-master-data 2>&1 | grep -iE "flyway|Successfully applied|ERROR" | tail
docker logs epd-prod-waybill     2>&1 | grep -iE "flyway|Successfully applied|ERROR" | tail
```

## 5. Проверка после выкладки

```bash
docker compose -f docker-compose.prod.yml ps                     # всё Up
curl -sI  http://10.10.29.70/        | head -3                  # 301 → https
curl -skI https://10.10.29.70/login  | grep -iE "HTTP/|strict|content-security|x-frame"
curl -skI https://10.10.29.70:8443/  | head -1                  # портал проверки QR
# здоровье служб изнутри сети compose
docker compose -f docker-compose.prod.yml exec proxy wget -qO- http://master-data:8081/actuator/health
docker compose -f docker-compose.prod.yml exec proxy wget -qO- http://waybill:8082/actuator/health
```

В браузере: `https://10.10.29.70` → вход. Регрессионные скрипты (`scripts/*.ps1`) рассчитаны
на локальный стенд с открытыми портами служб; на сервере их гоняют через SSH-туннель
(`ssh -L 8081:<ip master-data>:8081 ...`) либо не гоняют.

## 6. Первый вход после выкладки

- `admin`, `inspector`, `analyst` при первом входе подключают второй фактор: страница входа
  покажет QR-код — отсканировать приложением Google Authenticator / Microsoft Authenticator /
  FreeOTP на телефоне и ввести 6-значный код.
- Сотрудникам второй фактор включается в «Доступах» кнопкой «Требовать 2FA».
- **Промышленная эксплуатация:** отключить учётки автопроверок (у них нет второго фактора):

  ```bash
  docker compose -f docker-compose.prod.yml exec postgres \
    psql -U epd -d masterdata -c "update app_user set enabled=false where username like '%-automation';"
  ```

## 7. Восстановление доступа

- **Сотрудник потерял телефон:** администратор компании / платформы → «Доступы» → «Сбросить 2FA».
- **Администратор платформы потерял телефон** (его нет в «Доступах» — он не привязан к
  организации): на сервере

  ```bash
  docker compose -f docker-compose.prod.yml exec postgres psql -U epd -d masterdata -c \
    "update app_user set totp_secret=null, totp_pending_secret=null, totp_last_step=null, totp_enrolled_at=null where username='admin';"
  ```

  При следующем входе (с паролем) он подключит приложение заново.
- **Учётка заблокирована** после 10 неудачных попыток — снимется сама через 15 минут.

## 8. Откат

```bash
cd ~/e-rohkhat
git checkout <версия-до-выкладки>
cd infra && docker compose -f docker-compose.prod.yml up -d --build
```

Миграции только добавляют таблицы и колонки — прежний код с ними работает, откатывать базу не
нужно. Если всё же нужна база на момент до выкладки — восстановить копию из шага 1
(порядок — в `infra/backup/backup-loop.sh`). Вернуть прежние порты (80 → web напрямую) можно
только откатом версии: в текущей снаружи открыт один прокси.

## 9. Данные: шаги, требующие решения владельца

Не выполняются автоматически (см. AUDIT.md, раздел 6):

- нормативы марок и маршруты — `scripts/migration/run_phase1b.ps1` (где доступна база legacy)
  либо через API администратора; демо-марки — `scripts/seed-demo-showcase.ps1 -BrandsOnly`;
- архив путевых листов — при первой загрузке на сервере сразу исправленными скриптами
  (`run_phase5.ps1`, затем `run_phase5b.ps1`); перезаливка уже загруженного архива — массовое
  удаление, **только с разрешения владельца**;
- коэффициенты маршрутов (находка 29) и ИНН 191 организации (находка 12) — ждут решения.

## Что изменилось для пользователей (выкладка 24.09.2026)

- Адрес платформы — `https://10.10.29.70` (было `http://`); старые закладки `http://`
  перенаправляются автоматически.
- Портал проверки QR — `https://10.10.29.70:8443`. Ссылки старых тестовых QR на `:3002`
  перестают открываться (боевых QR новой платформы ещё не выдавалось).
- Сканер QR инспектора на телефоне работает только по HTTPS — теперь работает.
