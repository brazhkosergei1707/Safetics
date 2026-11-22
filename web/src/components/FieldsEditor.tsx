import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Form, type FormInstance, Input, InputNumber, Select, Space, Switch, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { FIELD_TYPES, type FieldFormValues } from './fieldsForm'

/** Редактор полів типу (обладнання чи послуги): назва, ключ, тип, одиниця, межі, варіанти, обов'язковість. */
export function FieldsEditor({ form }: { form: FormInstance }) {
  const { t } = useTranslation()
  const values = Form.useWatch('fields', form) as FieldFormValues[] | undefined
  const required = [{ required: true, message: t('validation.required') }]

  return (
    <Form.List name="fields">
      {(fields, { add, remove }) => (
        <>
          {fields.map((field) => {
            const type = values?.[field.name]?.type
            return (
              <Space key={field.key} align="start" wrap style={{ display: 'flex', marginBottom: 4 }}>
                <Form.Item name={[field.name, 'label']} rules={required}>
                  <Input placeholder={t('assetTypes.fieldLabel')} style={{ width: 160 }} />
                </Form.Item>
                <Form.Item name={[field.name, 'key']} rules={required}>
                  <Input placeholder={t('assetTypes.fieldKey')} style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name={[field.name, 'type']} rules={required}>
                  <Select
                    style={{ width: 120 }}
                    options={FIELD_TYPES.map((ft) => ({ value: ft, label: t(`assetTypes.types.${ft}`) }))}
                  />
                </Form.Item>
                {(type === 'number' || type === 'integer') && (
                  <>
                    <Form.Item name={[field.name, 'unit']}>
                      <Input placeholder={t('assetTypes.unit')} style={{ width: 70 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'min']}>
                      <InputNumber placeholder="min" style={{ width: 80 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'max']}>
                      <InputNumber placeholder="max" style={{ width: 80 }} />
                    </Form.Item>
                  </>
                )}
                {type === 'enum' && (
                  <Form.Item name={[field.name, 'options']} rules={required}>
                    <Select mode="tags" placeholder={t('assetTypes.options')} style={{ minWidth: 200 }} />
                  </Form.Item>
                )}
                <Form.Item name={[field.name, 'required']} valuePropName="checked">
                  <Switch checkedChildren={t('assetTypes.required')} unCheckedChildren={t('assetTypes.optional')} />
                </Form.Item>
                <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
              </Space>
            )
          })}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'string', required: false })}>
            {t('assetTypes.addField')}
          </Button>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            {t('assetTypes.fieldsHint')}
          </Typography.Paragraph>
        </>
      )}
    </Form.List>
  )
}
