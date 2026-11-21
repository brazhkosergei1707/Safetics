/**
 * Базовий клієнт API. Пізніше типи й виклики замінить клієнт, згенерований з OpenAPI бекенду.
 *
 * Токени живуть у httpOnly-cookie, тож JavaScript їх не бачить. Якщо запит повернув 401
 * (токен доступу прострочився), клієнт один раз викликає /api/v1/auth/refresh і повторює запит.
 */
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
const NO_REFRESH = [
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
  '/api/v1/auth/logout',
]

let refreshInFlight: Promise<boolean> | null = null

/** Одночасні 401 запускають лише одне оновлення. */
function refreshSession(): Promise<boolean> {
  refreshInFlight ??= fetch('/api/v1/auth/refresh', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  })
    .then((r) => r.ok)
    .catch(() => false)
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

async function request<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (response.status === 401 && !retried && !NO_REFRESH.includes(path)) {
    if (await refreshSession()) {
      return request<T>(method, path, body, true)
    }
  }

  if (!response.ok) {
    let code: string | undefined
    try {
      code = ((await response.json()) as { error?: string }).error
    } catch {
      // тіло без JSON
    }
    throw new ApiError(response.status, code)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const apiGet = <T>(path: string) => request<T>('GET', path)
export const apiPost = <T>(path: string, body: unknown = {}) => request<T>('POST', path, body)
