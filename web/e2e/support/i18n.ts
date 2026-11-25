import uk from '../../src/i18n/uk.ts'

/** Текст інтерфейсу з перекладу (тести йдуть українською): t('users.add'), t('services.new', { type: '…' }). */
export function t(key: string, params: Record<string, string | number> = {}): string {
  let value: unknown = uk
  for (const part of key.split('.')) {
    value = (value as Record<string, unknown> | undefined)?.[part]
  }
  if (typeof value !== 'string') throw new Error(`Немає перекладу: ${key}`)
  return value.replace(/\{\{(\w+)\}\}/g, (_, name: string) => String(params[name] ?? ''))
}
