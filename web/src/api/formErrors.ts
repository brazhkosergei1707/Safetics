import type { FormInstance } from 'antd'
import type { TFunction } from 'i18next'
import { ApiError } from './client'

/**
 * Показує помилки полів із сервера біля полів форми. Ключі сервера переводяться в шлях форми:
 * "shifts[1].start" → ['shifts', 1, 'start'], "attributes.dip" → ['attributes', 'dip'].
 * Повертає false, якщо в помилці немає полів (тоді показати загальне повідомлення).
 */
export function applyFieldErrors(
  form: FormInstance,
  error: unknown,
  t: TFunction,
  /** Перейменування полів сервера на поля форми: {password: 'adminPassword'} */
  rename: Record<string, string> = {},
): boolean {
  if (!(error instanceof ApiError)) return false
  const entries = Object.entries(error.fields).map(([key, code]) => [rename[key] ?? key, code] as const)
  if (entries.length === 0) return false
  form.setFields(
    entries.map(([key, code]) => ({
      name: key.split('.').flatMap((part) => {
        const match = /^(.+)\[(\d+)]$/.exec(part)
        return match ? [match[1], Number(match[2])] : [part]
      }),
      errors: [t(`validation.${code}`, { defaultValue: code })],
    })),
  )
  return true
}

/** Текст для помилки без полів: відомий код → переклад, інакше — «сервер недоступний». */
export function errorMessage(error: unknown, t: TFunction): string {
  if (error instanceof ApiError && error.code) {
    return t(`apiErrors.${error.code}`, { defaultValue: t('errors.serverUnavailable') })
  }
  return t('errors.serverUnavailable')
}

/** Помилки про відповідального (crew: one_lead / lead_required, crew[i].lead: no_login) — біля поля «Відповідальний». */
export function leadRename(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  const rename: Record<string, string> = { crew: 'leadId' }
  for (const key of Object.keys(error.fields)) {
    if (key.endsWith('.lead')) rename[key] = 'leadId'
  }
  return rename
}
