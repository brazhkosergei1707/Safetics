import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Divider, Drawer, Flex, Form, Input, List, Select, Space, Switch, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type TaskCategory, type TaskType, useArchiveTaskType, useSaveTaskType, useTaskTypes } from '../../api/tasks'
import { FieldsEditor } from '../../components/FieldsEditor'
import { type FieldFormValues, fieldsFromForm, fieldsToForm } from '../../components/fieldsForm'
import { localized } from '../../i18n/localized'

const CATEGORIES: TaskCategory[] = ['new', 'active', 'done', 'cancelled']
const COLORS = ['default', 'blue', 'orange', 'green', 'red', 'purple']

interface StatusValues {
  code: string
  label: string
  category: TaskCategory
  color?: string
  otherLabels?: Record<string, string>
}

interface FormValues {
  code: string
  name: string
  subjectKind: TaskType['subjectKind']
  syncService: boolean
  childrenBlockClose: boolean
  statuses: StatusValues[]
  fields: FieldFormValues[]
  closingFields: FieldFormValues[]
}

/** Типи задач: статуси з категоріями, поля, форма закриття, предмет, синхронізація з послугою. */
export function TaskTypesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const lang = i18n.language
  const types = useTaskTypes(true, open)
  const save = useSaveTaskType()
  const archive = useArchiveTaskType()
  const [form] = Form.useForm<FormValues>()
  const [editing, setEditing] = useState<{ type?: TaskType } | null>(null)
  const subjectKind = Form.useWatch('subjectKind', form)

  const startEdit = (type?: TaskType) => {
    form.resetFields()
    form.setFieldsValue(
      type
        ? {
            code: type.code,
            name: localized(type.name, lang),
            subjectKind: type.subjectKind,
            syncService: type.syncService,
            childrenBlockClose: type.childrenBlockClose,
            statuses: type.statuses.map((s) => ({
              code: s.code,
              label: localized(s.label, lang),
              category: s.category,
              color: s.color ?? 'default',
              otherLabels: s.label,
            })),
            fields: fieldsToForm(type.fields, lang),
            closingFields: fieldsToForm(type.closingFields, lang),
          }
        : {
            subjectKind: 'none',
            syncService: false,
            childrenBlockClose: false,
            statuses: [
              { code: 'new', label: t('tasks.defaultStatuses.new'), category: 'new', color: 'default' },
              { code: 'in_progress', label: t('tasks.defaultStatuses.in_progress'), category: 'active', color: 'blue' },
              { code: 'done', label: t('tasks.defaultStatuses.done'), category: 'done', color: 'green' },
              { code: 'cancelled', label: t('tasks.defaultStatuses.cancelled'), category: 'cancelled', color: 'default' },
            ],
            fields: [],
            closingFields: [],
          },
    )
    setEditing({ type })
  }

  const onFinish = (values: FormValues) => {
    save.mutate(
      {
        id: editing?.type?.id,
        body: {
          code: values.code,
          name: { ...(editing?.type?.name ?? {}), [lang]: values.name },
          subjectKind: values.subjectKind,
          syncService: values.subjectKind === 'service' && values.syncService,
          childrenBlockClose: values.childrenBlockClose,
          statuses: values.statuses.map((s) => ({
            code: s.code,
            label: { ...(s.otherLabels ?? {}), [lang]: s.label },
            category: s.category,
            color: s.color,
          })),
          fields: fieldsFromForm(values.fields, lang),
          closingFields: fieldsFromForm(values.closingFields, lang),
        },
      },
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

  return (
    <Drawer
      open={open}
      onClose={() => {
        setEditing(null)
        onClose()
      }}
      size={760}
      destroyOnHidden
      title={editing ? (editing.type ? localized(editing.type.name, lang) : t('taskTypes.new')) : t('taskTypes.title')}
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
              {t('taskTypes.new')}
            </Button>
          </Flex>
          <List<TaskType>
            loading={types.isPending}
            dataSource={types.data ?? []}
            renderItem={(type) => (
              <List.Item
                actions={[
                  <Button key="edit" type="link" size="small" onClick={() => startEdit(type)}>
                    {t('common.edit')}
                  </Button>,
                  <Button key="archive" type="link" size="small" onClick={() => archive.mutate({ id: type.id, restore: type.archived })}>
                    {type.archived ? t('customers.restore') : t('assets.archive')}
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Flex gap={8} align="center" wrap>
                      {localized(type.name, lang)}
                      {type.subjectKind !== 'none' && <Tag>{t(`taskTypes.subjects.${type.subjectKind}`)}</Tag>}
                      {type.syncService && <Tag color="blue">{t('taskTypes.syncShort')}</Tag>}
                      {type.archived && <Tag>{t('validation.archived')}</Tag>}
                    </Flex>
                  }
                  description={type.statuses.map((s) => localized(s.label, lang)).join(' → ')}
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
          <Flex gap={16} wrap align="flex-start">
            <Form.Item name="subjectKind" label={t('taskTypes.subject')} style={{ minWidth: 200 }}>
              <Select
                options={(['none', 'service', 'asset'] as const).map((k) => ({ value: k, label: t(`taskTypes.subjects.${k}`) }))}
              />
            </Form.Item>
            {subjectKind === 'service' && (
              <Form.Item name="syncService" label={t('taskTypes.syncService')} valuePropName="checked" extra={t('taskTypes.syncHint')}>
                <Switch />
              </Form.Item>
            )}
            <Form.Item name="childrenBlockClose" label={t('taskTypes.childrenBlockClose')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Flex>

          <Divider titlePlacement="start">{t('assetTypes.statuses')}</Divider>
          <Form.List name="statuses">
            {(statuses, { add, remove }) => (
              <>
                {statuses.map((status) => (
                  <Space key={status.key} align="start" wrap style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item name={[status.name, 'label']} rules={required}>
                      <Input placeholder={t('assetTypes.fieldLabel')} style={{ width: 170 }} />
                    </Form.Item>
                    <Form.Item name={[status.name, 'code']} rules={required}>
                      <Input placeholder={t('assetTypes.fieldKey')} style={{ width: 130 }} />
                    </Form.Item>
                    <Form.Item name={[status.name, 'category']} rules={required}>
                      <Select style={{ width: 150 }} options={CATEGORIES.map((c) => ({ value: c, label: t(`tasks.category.${c}`) }))} />
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
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ category: 'active', color: 'default' })}>
                  {t('assetTypes.addStatus')}
                </Button>
                <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                  {t('taskTypes.statusesHint')}
                </Typography.Paragraph>
              </>
            )}
          </Form.List>

          <Divider titlePlacement="start">{t('assetTypes.fields')}</Divider>
          <FieldsEditor form={form} name="fields" />

          <Divider titlePlacement="start">{t('tasks.closingForm')}</Divider>
          <Typography.Paragraph type="secondary">{t('taskTypes.closingHint')}</Typography.Paragraph>
          <FieldsEditor form={form} name="closingFields" />
        </Form>
      )}
    </Drawer>
  )
}
