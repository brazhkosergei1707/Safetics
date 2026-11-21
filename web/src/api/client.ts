import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

/**
 * Типізований клієнт API. Шляхи, параметри й відповіді беруться з src/api/schema.d.ts,
 * який генерується з опису API бекенду: `npm run api:generate` (сервер має працювати з профілем dev).
 *
 * Токени живуть у httpOnly-cookie, тож JavaScript їх не бачить. Якщо запит повернув 401
 * (токен доступу прострочився), клієнт один раз викликає /api/v1/auth/refresh і повторює запит.
 */

export type Schemas = components['schemas']

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, code?: string) {
    super(code ?? `HTTP ${status}`)
    this.status = status
    this.code = code
  }
}

/** Ендпоінти, після 401 яких оновлювати токен немає сенсу. */
const NO_REFRESH = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout']

let refreshInFlight: Promise<boolean> | null = null

/** Одночасні 401 запускають лише одне оновлення. */
function refreshSession(): Promise<boolean> {
  refreshInFlight ??= fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })
    .then((r) => r.ok)
    .catch(() => false)
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

async function fetchWithRefresh(request: Request): Promise<Response> {
  const retry = request.clone()
  const response = await fetch(request)
  const path = new URL(request.url).pathname
  if (response.status === 401 && !NO_REFRESH.includes(path) && (await refreshSession())) {
    return fetch(retry)
  }
  return response
}

export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: 'include',
  fetch: fetchWithRefresh,
})

/** Повертає дані або кидає ApiError зі статусом і кодом помилки сервера ({"error": "..."}). */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await call
  if (!response.ok) {
    const code = (error as { error?: string } | undefined)?.error
    throw new ApiError(response.status, code)
  }
  return data as T
}
