import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Divider, Drawer, Flex, Form, Input, InputNumber, List, Select, Space, Switch, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  type TaskCategory,
  type TaskRule,
  type TaskType,
  useArchiveTaskType,
  useSaveTaskType,
  useTaskTypes,
} from '../../api/tasks'
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
  rules: TaskRule[]
}

const ASSIGN_TO = ['supervisor', 'storekeeper', 'parent_assignee', 'author', 'shift_lead', 'none'] as const

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
  const rules = Form.useWatch('rules', form) as TaskRule[] | undefined

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
            rules: type.rules,
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
            rules: [],
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
          // Задається шаблоном («Бурова на проєкті»), у редакторі не змінюється
          autoCreate: editing?.type?.autoCreate,
          statuses: values.statuses.map((s) => ({
            code: s.code,
            label: { ...(s.otherLabels ?? {}), [lang]: s.label },
            category: s.category,
            color: s.color,
          })),
          fields: fieldsFromForm(values.fields, lang),
          closingFields: fieldsFromForm(values.closingFields, lang),
          rules: (values.rules ?? []).map((r) => ({
            ...r,
            action: 'create_subtask' as const,
            category: r.trigger === 'status_category' ? r.category : undefined,
            title: r.title || undefined,
          })),
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
                      {type.rules.length > 0 && <Tag color="purple">{t('taskTypes.rulesCount', { count: type.rules.length })}</Tag>}
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

          <Divider titlePlacement="start">{t('taskTypes.rules')}</Divider>
          <Typography.Paragraph type="secondary">{t('taskTypes.rulesHint')}</Typography.Paragraph>
          <Form.List name="rules">
            {(items, { add, remove }) => (
              <>
                {items.map((item, index) => {
                  const rule = rules?.[item.name]
                  const previous = (rules ?? [])
                    .map((r, i) => ({ r, i }))
                    .filter(({ r, i }) => i < index && r?.trigger === rule?.trigger && r?.category === rule?.category)
                  return (
                    <Flex key={item.key} vertical gap={0} style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, marginBottom: 8 }}>
                      <Flex gap={8} align="center" style={{ marginBottom: 8 }}>
                        <Tag>#{index}</Tag>
                        <Typography.Text type="secondary">{t('taskTypes.ruleCreates')}</Typography.Text>
                        <Flex flex={1} />
                        <Button size="small" icon={<DeleteOutlined />} onClick={() => remove(item.name)} />
                      </Flex>
                      <Space align="start" wrap>
                        <Form.Item name={[item.name, 'trigger']} rules={required} label={t('taskTypes.trigger')}>
                          <Select
                            style={{ width: 170 }}
                            options={(['created', 'status_category', 'shift_created'] as const).map((tr) => ({ value: tr, label: t(`taskTypes.triggers.${tr}`) }))}
                          />
                        </Form.Item>
                        {rule?.trigger === 'status_category' && (
                          <Form.Item name={[item.name, 'category']} rules={required} label={t('taskTypes.toCategory')}>
                            <Select
                              style={{ width: 150 }}
                              options={(['active', 'done', 'cancelled'] as const).map((c) => ({ value: c, label: t(`tasks.category.${c}`) }))}
                            />
                          </Form.Item>
                        )}
                        <Form.Item name={[item.name, 'taskType']} rules={required} label={t('taskTypes.subtaskType')}>
                          <Select
                            style={{ width: 190 }}
                            options={[
                              ...(types.data ?? []).filter((tt) => !tt.archived).map((tt) => ({ value: tt.code, label: localized(tt.name, lang) })),
                            ]}
                          />
                        </Form.Item>
                        <Form.Item name={[item.name, 'assignTo']} label={t('taskTypes.assignTo')}>
                          <Select style={{ width: 200 }} options={ASSIGN_TO.map((a) => ({ value: a, label: t(`taskTypes.assignees.${a}`) }))} />
                        </Form.Item>
                        <Form.Item name={[item.name, 'title']} label={t('taskTypes.titleTemplate')} extra={t('taskTypes.titleHint')}>
                          <Input placeholder="{type}: {service}" style={{ width: 220 }} />
                        </Form.Item>
                        <Form.Item name={[item.name, 'dueInDays']} label={t('taskTypes.dueInDays')}>
                          <InputNumber min={0} max={3650} style={{ width: 110 }} />
                        </Form.Item>
                        <Form.Item name={[item.name, 'blockedBy']} label={t('taskTypes.blockedBy')}>
                          <Select
                            allowClear
                            style={{ width: 150 }}
                            options={previous.map(({ i }) => ({ value: i, label: `#${i}` }))}
                          />
                        </Form.Item>
                      </Space>
                    </Flex>
                  )
                })}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ trigger: 'created', assignTo: 'supervisor' })}>
                  {t('taskTypes.addRule')}
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      )}
    </Drawer>
  )
}
