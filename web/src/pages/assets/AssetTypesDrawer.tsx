import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import {
  App,
  Button,
  Divider,
  Drawer,
  Flex,
  Form,
  Input,
  InputNumber,
  List,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type AssetType, useAllAssetTypes, useArchiveAssetType, useSaveAssetType } from '../../api/assets'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { localized } from '../../i18n/localized'

const FIELD_TYPES = ['string', 'text', 'number', 'integer', 'boolean', 'date', 'enum'] as const
const COLORS = ['green', 'blue', 'orange', 'red', 'default']

/** Поле у формі редактора: назва — лише поточною мовою, інші мови зберігаються як були. */
interface FieldValues {
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

interface StatusValues {
  code: string
  label: string
  color?: string
  otherLabels?: Record<string, string>
}

interface FormValues {
  code: string
  name: string
  category: 'primary' | 'auxiliary'
  fields: FieldValues[]
  statuses: StatusValues[]
  allowedChildTypes: string[]
}

/**
 * Типи обладнання компанії: поля, статуси, основне чи допоміжне, що можна вкладати.
 * Змінювати типи може catalog.manage. Код типу після створення не змінюється.
 */
export function AssetTypesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const types = useAllAssetTypes(open)
  const save = useSaveAssetType()
  const archive = useArchiveAssetType()
  const [form] = Form.useForm<FormValues>()
  const [editing, setEditing] = useState<{ type?: AssetType } | null>(null)
  const lang = i18n.language
  const fieldType = Form.useWatch('fields', form)

  const startEdit = (type?: AssetType) => {
    form.resetFields()
    form.setFieldsValue(
      type
        ? {
            code: type.code,
            name: localized(type.name, lang),
            category: type.category,
            allowedChildTypes: type.allowedChildTypes,
            fields: type.fields.map((f) => ({
              key: f.key,
              label: localized(f.label, lang),
              type: f.type,
              unit: f.unit ?? undefined,
              required: f.required ?? false,
              min: f.min ?? undefined,
              max: f.max ?? undefined,
              options: f.options ?? undefined,
              otherLabels: f.label,
            })),
            statuses: type.statuses.map((s) => ({
              code: s.code,
              label: localized(s.label, lang),
              color: s.color ?? 'default',
              otherLabels: s.label,
            })),
          }
        : {
            category: 'auxiliary',
            fields: [],
            allowedChildTypes: [],
            statuses: [{ code: 'active', label: t('assetTypes.defaultActive'), color: 'green' }],
          },
    )
    setEditing({ type })
  }

  const onFinish = (values: FormValues) => {
    const body = {
      code: values.code,
      name: { ...(editing?.type?.name ?? {}), [lang]: values.name },
      category: values.category,
      allowedChildTypes: values.allowedChildTypes ?? [],
      fields: (values.fields ?? []).map((f) => ({
        key: f.key,
        label: { ...(f.otherLabels ?? {}), [lang]: f.label },
        type: f.type,
        unit: f.unit || undefined,
        required: f.required ?? false,
        min: f.min ?? undefined,
        max: f.max ?? undefined,
        options: f.type === 'enum' ? f.options : undefined,
      })),
      statuses: (values.statuses ?? []).map((s) => ({
        code: s.code,
        label: { ...(s.otherLabels ?? {}), [lang]: s.label },
        color: s.color,
      })),
    }
    save.mutate(
      { id: editing?.type?.id, body },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          setEditing(null)
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const required = [{ required: true, message: t('validation.required') }]
  const otherTypes = (types.data ?? []).filter((type) => type.code !== editing?.type?.code && !type.archived)

  return (
    <Drawer
      open={open}
      onClose={() => {
        setEditing(null)
        onClose()
      }}
      size={720}
      destroyOnHidden
      title={editing ? (editing.type ? localized(editing.type.name, lang) : t('assetTypes.new')) : t('assetTypes.title')}
      footer={
        editing && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={() => setEditing(null)}>{t('common.cancel')}</Button>
            <Button type="primary" loading={save.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        )
      }
    >
      {!editing ? (
        <>
          <Flex justify="flex-end" style={{ marginBottom: 12 }}>
            <Button icon={<PlusOutlined />} onClick={() => startEdit()}>
              {t('assetTypes.new')}
            </Button>
          </Flex>
          <List<AssetType>
            loading={types.isPending}
            dataSource={types.data ?? []}
            renderItem={(type) => (
              <List.Item
                actions={[
                  <Button key="edit" type="link" size="small" onClick={() => startEdit(type)}>
                    {t('common.edit')}
                  </Button>,
                  <Button
                    key="archive"
                    type="link"
                    size="small"
                    onClick={() => archive.mutate({ id: type.id, restore: type.archived })}
                  >
                    {type.archived ? t('customers.restore') : t('assets.archive')}
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Flex gap={8} align="center">
                      {localized(type.name, lang)}
                      <Tag color={type.category === 'primary' ? 'orange' : undefined}>
                        {t(`assets.${type.category}`)}
                      </Tag>
                      {type.archived && <Tag>{t('validation.archived')}</Tag>}
                    </Flex>
                  }
                  description={`${type.code} · ${t('assetTypes.fieldsCount', { count: type.fields.length })}`}
                />
              </List.Item>
            )}
          />
        </>
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          <Flex gap={12} wrap>
            <Form.Item name="name" label={t('projects.name')} rules={required} style={{ flex: 1, minWidth: 220 }}>
              <Input />
            </Form.Item>
            <Form.Item name="code" label={t('projects.code')} rules={required} extra={t('personnel.codeHint')}>
              <Input disabled={!!editing.type} style={{ width: 180 }} />
            </Form.Item>
          </Flex>
          <Form.Item name="category" label={t('assetTypes.category')} extra={t('assetTypes.categoryHint')}>
            <Select
              options={[
                { value: 'primary', label: t('assets.primary') },
                { value: 'auxiliary', label: t('assets.auxiliary') },
              ]}
            />
          </Form.Item>
          <Form.Item name="allowedChildTypes" label={t('assetTypes.children')} extra={t('assetTypes.childrenHint')}>
            <Select
              mode="multiple"
              options={otherTypes.map((type) => ({ value: type.code, label: localized(type.name, lang) }))}
            />
          </Form.Item>

          <Divider titlePlacement="start">{t('assetTypes.fields')}</Divider>
          <Form.List name="fields">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => {
                  const type = fieldType?.[field.name]?.type
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

          <Divider titlePlacement="start">{t('assetTypes.statuses')}</Divider>
          <Form.List name="statuses">
            {(statuses, { add, remove }) => (
              <>
                {statuses.map((status) => (
                  <Space key={status.key} align="start" wrap style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item name={[status.name, 'label']} rules={required}>
                      <Input placeholder={t('assetTypes.fieldLabel')} style={{ width: 180 }} />
                    </Form.Item>
                    <Form.Item name={[status.name, 'code']} rules={required}>
                      <Input placeholder={t('assetTypes.fieldKey')} style={{ width: 140 }} />
                    </Form.Item>
                    <Form.Item name={[status.name, 'color']}>
                      <Select
                        style={{ width: 120 }}
                        options={COLORS.map((c) => ({ value: c, label: <Tag color={c === 'default' ? undefined : c}>{c}</Tag> }))}
                      />
                    </Form.Item>
                    <Button icon={<DeleteOutlined />} disabled={statuses.length === 1} onClick={() => remove(status.name)} />
                  </Space>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ color: 'default' })}>
                  {t('assetTypes.addStatus')}
                </Button>
                <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                  {t('assetTypes.statusesHint')}
                </Typography.Paragraph>
              </>
            )}
          </Form.List>
        </Form>
      )}
    </Drawer>
  )
}
