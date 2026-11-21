# Safetics — веб

React 19 + TypeScript + Vite, Ant Design, TanStack Query, React Router, i18next (EN, UK).

## Що потрібно

- Node.js 24 LTS (мінімум 22.22): `brew install node@24` або через `nvm use` (версія в `.nvmrc`)

## Запуск

```bash
npm install
npm run dev
```

Відкрийте http://localhost:5173. Запити на `/api` і `/actuator` Vite переадресовує на бекенд (`localhost:8080`), тому спершу запустіть сервер:

```bash
cd ../server
docker compose up -d
./mvnw spring-boot:run
```

На сторінці «Огляд» картка «Стан системи» показує, чи доступний бекенд.

## Команди

| Команда | Що робить |
| --- | --- |
| `npm run dev` | Сервер розробки з гарячим перезавантаженням |
| `npm run build` | Перевірка типів і збірка в `dist/` |
| `npm run lint` | Перевірка коду (oxlint) |
| `npm run preview` | Перегляд зібраної версії |

## Структура

```
src/
├── api/        клієнт API і хуки запитів (пізніше — згенерований з OpenAPI)
├── i18n/       переклади; uk.ts типізований за en.ts, пропущений ключ = помилка збірки
├── layout/     каркас: бокове меню, шапка з вибором мови
├── pages/      сторінки
├── App.tsx     маршрути і тема Ant Design
└── main.tsx    точка входу
```

## На AWS

`npm run build` → вміст `dist/` у S3 → CloudFront. Той самий CloudFront переадресовує `/api/*` на бекенд, тож фронт і API працюють на одному домені.
