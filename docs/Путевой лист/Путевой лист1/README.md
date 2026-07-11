# Платформа "Путевой Лист"

Профессиональная система автоматизации и контроля цифровых путевых листов.

## Структура проекта

- `apps/web-admin`: Фронтенд на Next.js (Dashboard, Вход, Формы).
- `services/core-service`: Бэкенд на Java Spring Boot (Бизнес-логика, БД).
- `docker-compose.yml`: Контейнер для PostgreSQL.

## Как запустить проект

### 1. Подготовка Базы Данных

Убедитесь, что у вас установлен Docker, и запустите БД:

```bash
docker-compose up -d
```

### 2. Запуск Бэкенда (Java)

Я установил портативный Maven в папку проекта. Запустите бэкенд командой:

```bash
cd services/core-service
.\apache-maven-3.9.6\bin\mvn spring-boot:run
```

Сервер будет доступен на `http://localhost:8080`.

### 3. Запуск Фронтенда (Next.js)

Запустите фронтенд командой (я настроил обход бага с длинными путями):

```bash
cd apps/web-admin
npm run dev
```

Откройте `http://localhost:3001` (или 3000) в браузере.

## Основные маршруты

- `/login`: Страница входа (Локально + SSO).
- `/dashboard`: Общая панель управления.
- `/dashboard/new`: Создание нового документа.
- `/dashboard/checkpoints/medical`: Пункт медосмотра.
- `/dashboard/checkpoints/technical`: Пункт техконтроля.
- `/dashboard/driver/[id]`: Вид для водителя с QR-кодом.

---
Разработано с использованием Java 21+, Spring Boot 3.x, Next.js 14 и Vanilla CSS.
