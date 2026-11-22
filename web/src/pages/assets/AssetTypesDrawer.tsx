import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Checkbox, Divider, Drawer, Flex, Form, Input, List, Select, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type AssetType, useAllAssetTypes, useArchiveAssetType, useSaveAssetType } from '../../api/assets'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { FieldsEditor } from '../../components/FieldsEditor'
import { type FieldFormValues, fieldsFromForm, fieldsToForm } from '../../components/fieldsForm'
import { localized } from '../../i18n/localized'

const COLORS = ['green', 'blue', 'orange', 'red', 'default']

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
  fields: FieldFormValues[]
  statuses: StatusValues[]
  allowedChildTypes: string[]
  checklist: { key?: string; label: string; critical?: boolean; otherLabels?: Record<string, string> }[]
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

  const startEdit = (type?: AssetType) => {
    form.resetFields()
    form.setFieldsValue(
      type
        ? {
            code: type.code,
            name: localized(type.name, lang),
            category: type.category,
            allowedChildTypes: type.allowedChildTypes,
            checklist: (type.checklist ?? []).map((c) => ({
              key: c.key,
              label: localized(c.label, lang),
              critical: c.critical,
              otherLabels: c.label,
            })),
            fields: fieldsToForm(type.fields, lang),
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
            checklist: [],
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
      checklist: checklistFromForm(values.checklist ?? [], lang),
      fields: fieldsFromForm(values.fields, lang),
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
          <FieldsEditor form={form} />

          <Divider titlePlacement="start">{t('assetTypes.checklist')}</Divider>
          <Typography.Paragraph type="secondary">{t('assetTypes.checklistHint')}</Typography.Paragraph>
          <Form.List name="checklist">
            {(items, { add, remove, move }) => (
              <>
                {items.map((item, index) => (
                  <Space key={item.key} align="start" style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item name={[item.name, 'label']} rules={required} style={{ marginBottom: 8 }}>
                      <Input placeholder={t('assetTypes.checklistItem')} style={{ width: 380 }} />
                    </Form.Item>
                    <Form.Item name={[item.name, 'critical']} valuePropName="checked" style={{ marginBottom: 8 }}>
                      <Checkbox>{t('checklists.critical')}</Checkbox>
                    </Form.Item>
                    <Button size="small" disabled={index === 0} onClick={() => move(index, index - 1)}>
                      ↑
                    </Button>
                    <Button icon={<DeleteOutlined />} onClick={() => remove(item.name)} />
                  </Space>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ critical: false })}>
                  {t('assetTypes.addChecklistItem')}
                </Button>
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

/** Пункти чек-листа з форми: наявні ключі зберігаються, нові — з назви (латиниця) або item_N. */
function checklistFromForm(
  rows: { key?: string; label: string; critical?: boolean; otherLabels?: Record<string, string> }[],
  lang: string,
): { key: string; label: Record<string, string>; critical: boolean }[] {
  const used = new Set(rows.map((r) => r.key).filter(Boolean) as string[])
  let n = 1
  return rows.map((r) => {
    let key = r.key
    if (!key) {
      const slug = r.label
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '')
        .slice(0, 40)
      key = /^[a-z]/.test(slug) ? slug : ''
      while (!key || used.has(key)) key = `item_${n++}`
      used.add(key)
    }
    return { key, label: { ...(r.otherLabels ?? {}), [lang]: r.label }, critical: !!r.critical }
  })
}
