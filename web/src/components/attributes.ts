import dayjs from 'dayjs'
import type { FieldDefinition } from '../api/assets'

/** Значення поля для таблиці: число з розділювачами, дата ДД.ММ.РРРР, так/ні. */
export function formatAttribute(field: FieldDefinition, value: unknown, language: string): string {
  if (value === undefined || value === null || value === '') return ''
  switch (field.type) {
    case 'number':
    case 'integer':
      return Number(value).toLocaleString(language) + (field.unit ? ` ${field.unit}` : '')
    case 'date':
      return dayjs(value as string).format('DD.MM.YYYY')
    case 'boolean':
      return value ? '✓' : '—'
    default:
      return String(value)
  }
}
