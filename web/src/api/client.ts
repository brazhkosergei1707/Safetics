/**
 * Базовий клієнт API. Пізніше його замінить клієнт, згенерований з OpenAPI-специфікації бекенду.
 * credentials: 'include' — щоб браузер надсилав cookie з JWT.
 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function apiGet<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    throw new ApiError(response.status, `${response.status} ${response.statusText}`)
  }
  return (await response.json()) as T
}
