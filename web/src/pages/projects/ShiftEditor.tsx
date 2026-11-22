import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Flex, Form, type FormInstance, Input, InputNumber, Space, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { SHIFT_PRESETS } from './shiftPresets'

interface Props {
  form: FormInstance
  /** Назва поля-списку у формі; за замовчуванням shifts. */
  name?: string
}

/**
 * Редактор змін графіка. Тривалість у формі зберігається в хвилинах (як в API), показується в
 * годинах. Зміна може переходити через північ: 19:00 + 12 год = до 07:00.
 */
export function ShiftEditor({ form, name = 'shifts' }: Props) {
  const { t } = useTranslation()

  return (
    <>
      <Flex gap={8} align="center" wrap style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">{t('projects.presets')}</Typography.Text>
        {Object.entries(SHIFT_PRESETS).map(([label, shifts]) => (
          <Button key={label} size="small" onClick={() => form.setFieldValue(name, shifts.map((s) => ({ ...s })))}>
            {label}
          </Button>
        ))}
      </Flex>
      <Form.List name={name}>
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <Space key={field.key} align="start" wrap style={{ display: 'flex' }}>
                <Form.Item name={[field.name, 'code']} rules={[{ required: true, message: t('validation.required') }]}>
                  <Input placeholder={t('projects.shiftCode')} style={{ width: 80 }} />
                </Form.Item>
                <Form.Item name={[field.name, 'name']} rules={[{ required: true, message: t('validation.required') }]}>
                  <Input placeholder={t('projects.shiftName')} style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name={[field.name, 'start']} rules={[{ required: true, message: t('validation.required') }]}>
                  <Input type="time" style={{ width: 110 }} />
                </Form.Item>
                <Form.Item
                  name={[field.name, 'durationMinutes']}
                  rules={[{ required: true, message: t('validation.required') }]}
                  getValueProps={(minutes?: number) => ({ value: minutes == null ? minutes : minutes / 60 })}
                  normalize={(hours?: number | null) => (hours == null ? hours : Math.round(hours * 60))}
                >
                  <InputNumber min={0.5} max={24} step={0.5} suffix={t('projects.hoursShort')} style={{ width: 110 }} />
                </Form.Item>
                <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} disabled={fields.length === 1} />
              </Space>
            ))}
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              disabled={fields.length >= 6}
              onClick={() => add({ code: '', name: '', start: '07:00', durationMinutes: 480 })}
            >
              {t('projects.addShift')}
            </Button>
          </>
        )}
      </Form.List>
    </>
  )
}
