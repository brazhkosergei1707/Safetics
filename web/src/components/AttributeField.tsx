import { DatePicker, Form, Input, InputNumber, Select, Switch } from 'antd'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import type { FieldDefinition } from '../api/assets'
import { localized } from '../i18n/localized'

/** Поле форми за описом поля типу (обладнання чи послуги). Значення — у attributes.<key>. */
export function AttributeField({ field, language }: { field: FieldDefinition; language: string }) {
  const { t } = useTranslation()
  const name = ['attributes', field.key]
  const label = localized(field.label, language)
  const rules = field.required ? [{ required: true, message: t('validation.required') }] : []

  switch (field.type) {
    case 'number':
    case 'integer':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <InputNumber
            style={{ width: '100%' }}
            min={field.min}
            max={field.max}
            precision={field.type === 'integer' ? 0 : undefined}
            suffix={field.unit}
            decimalSeparator=","
          />
        </Form.Item>
      )
    case 'boolean':
      return (
        <Form.Item name={name} label={label} valuePropName="checked">
          <Switch />
        </Form.Item>
      )
    case 'date':
      return (
        <Form.Item
          name={name}
          label={label}
          rules={rules}
          getValueProps={(value) => ({ value: value ? dayjs(value as string) : null })}
          normalize={(value: dayjs.Dayjs | null) => (value ? value.format('YYYY-MM-DD') : null)}
        >
          <DatePicker style={{ width: '100%' }} format="DD.MM.YYYY" />
        </Form.Item>
      )
    case 'enum':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Select allowClear options={(field.options ?? []).map((o) => ({ value: o, label: o }))} />
        </Form.Item>
      )
    case 'text':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Input.TextArea rows={3} maxLength={5000} />
        </Form.Item>
      )
    default:
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Input maxLength={500} suffix={field.unit} />
        </Form.Item>
      )
  }
}
