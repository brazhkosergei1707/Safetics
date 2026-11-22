import type { FieldDefinition } from '../api/assets'
import { localized } from '../i18n/localized'

export const FIELD_TYPES = ['string', 'text', 'number', 'integer', 'boolean', 'date', 'enum'] as const

/** Поле в редакторі: назва лише поточною мовою, переклади іншими мовами зберігаються в otherLabels. */
export interface FieldFormValues {
  key: string
  label: string
  type: (typeof FIELD_TYPES)[number]
  unit?: string
  required?: boolean
  min?: number
  max?: number
  options?: string[]
  otherLabels?: Record<string, string>
}

export function fieldsToForm(fields: FieldDefinition[], language: string): FieldFormValues[] {
  return fields.map((f) => ({
    key: f.key,
    label: localized(f.label, language),
    type: f.type,
    unit: f.unit ?? undefined,
    required: f.required ?? false,
    min: f.min ?? undefined,
    max: f.max ?? undefined,
    options: f.options ?? undefined,
    otherLabels: f.label,
  }))
}

export function fieldsFromForm(values: FieldFormValues[] | undefined, language: string): FieldDefinition[] {
  return (values ?? []).map((f) => ({
    key: f.key,
    label: { ...(f.otherLabels ?? {}), [language]: f.label },
    type: f.type,
    unit: f.unit || undefined,
    required: f.required ?? false,
    min: f.min ?? undefined,
    max: f.max ?? undefined,
    options: f.type === 'enum' ? f.options : undefined,
  }))
}
