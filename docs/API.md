# Portal Server API

Все ответы — JSON, ошибки: `{"error":"текст"}`. Авторизация: `Authorization: Bearer <token>` (токен выдаётся при регистрации).

| Метод | Путь | Описание |
|---|---|---|
| GET | /api/health | проверка доступности |
| POST | /api/register | `{nickname, model}` → `{user, token}` |
| GET | /api/me | свой профиль |
| PUT | /api/me/model | `{model: "classic"\|"slim"}` |
| PUT | /api/me/skin | тело — PNG 64×64 (до 512 КБ) |
| GET | /api/users?q=&limit= | поиск по началу ника |
| GET | /api/users/{id} | профиль |
| GET | /api/users/{id}/skin | PNG скина |
| PUT | /api/admin/users/{id}/status | `{status}`, заголовок `X-Admin-Key` |

Профиль: `{id, nickname, model, status, createdAt, skinHash?}`; `status` ∈ user / verified / admin и меняется только админ-ключом.
Переменные окружения: `PORTAL_PORT`, `PORTAL_BIND`, `PORTAL_DATA`, `PORTAL_ADMIN_KEY`.
