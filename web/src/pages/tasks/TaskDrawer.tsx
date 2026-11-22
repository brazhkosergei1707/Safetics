import { DeleteOutlined, LinkOutlined, PlusOutlined } from '@ant-design/icons'
import {
  App,
  Button,
  ConfigProvider,
  DatePicker,
  Descriptions,
  Divider,
  Drawer,
  Dropdown,
  Empty,
  Flex,
  Form,
  type FormInstance,
  Input,
  List,
  Modal,
  Select,
  Space,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAssets } from '../../api/assets'
import { ApiError } from '../../api/client'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import type { Project } from '../../api/projects'
import { useProjectServices } from '../../api/services'
import {
  CATEGORY_COLORS,
  type Task,
  type TaskRef,
  type TaskType,
  useAddTaskLink,
  useChangeTaskStatus,
  useCreateTask,
  useProjectTasks,
  useRemoveTaskLink,
  useTask,
  useTaskHistory,
  useTaskLinks,
  useUpdateTask,
} from '../../api/tasks'
import { AttributeField } from '../../components/AttributeField'
import { localized } from '../../i18n/localized'
import { ShiftReportDrawer } from '../shifts/ShiftReportDrawer'
import { useAssigneeOptions, useTaskRights } from './taskRights'

interface FormValues {
  title: string
  description?: string
  assigneeId?: string
  serviceId?: string
  assetId?: string
  plannedStart?: Dayjs
  plannedEnd?: Dayjs
  forecastEnd?: Dayjs
  attributes: Record<string, unknown>
}

interface Props {
  open: boolean
  project: Project
  types: TaskType[]
  /** Відкрита задача; без неї — створення нової */
  taskId?: string
  /** Для нової задачі */
  newType?: TaskType
  parentId?: string
  onClose: () => void
  /** Відкрити іншу задачу (підзадачу чи пов'язану) */
  onOpenTask: (id: string) => void
  /** Створити підзадачу вибраного типу */
  onAddChild: (parent: Task, type: TaskType) => void
}

/** Картка задачі: поля, статус, підзадачі, «блокує», історія. Форма закриття — при переході в «завершена». */
export function TaskDrawer({ open, project, types, taskId, newType, parentId, onClose, onOpenTask, onAddChild }: Props) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const lang = i18n.language
  const [form] = Form.useForm<FormValues>()
  const task = useTask(open ? taskId : undefined)
  const links = useTaskLinks(open ? taskId : undefined)
  const history = useTaskHistory(open ? taskId : undefined)
  const rights = useTaskRights(project)
  const assignees = useAssigneeOptions(project)
  const create = useCreateTask(project.id)
  const update = useUpdateTask()
  const changeStatus = useChangeTaskStatus()
  const addLink = useAddTaskLink()
  const removeLink = useRemoveTaskLink()
  const [closing, setClosing] = useState<{ status: string } | null>(null)
  const [closingForm] = Form.useForm<{ closingValues: Record<string, unknown> }>()
  const [linking, setLinking] = useState(false)
  const [linkForm] = Form.useForm<{ taskId: string; kind: string }>()

  const current = task.data
  const [reportShift, setReportShift] = useState<string>()
  const type = current ? types.find((tt) => tt.id === current.typeId) : newType
  const services = useProjectServices(project.id)
  const assets = useAssets({}, open && type?.subjectKind === 'asset')
  const projectTasks = useProjectTasks(project.id, linking)

  const closed = current ? current.statusCategory === 'done' || current.statusCategory === 'cancelled' : false
  const fullEdit = !closed && rights.full
  const datesEdit = !closed && (rights.full || rights.isAssignee(current))
  const statusEdit = current ? rights.canWork(current) : false

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (current) {
      form.setFieldsValue({
        title: current.title,
        description: current.description ?? undefined,
        assigneeId: current.assigneeId ?? undefined,
        serviceId: current.serviceId ?? undefined,
        assetId: current.assetId ?? undefined,
        plannedStart: current.plannedStart ? dayjs(current.plannedStart) : undefined,
        plannedEnd: current.plannedEnd ? dayjs(current.plannedEnd) : undefined,
        forecastEnd: current.forecastEnd ? dayjs(current.forecastEnd) : undefined,
        attributes: current.attributes,
      })
    } else {
      form.setFieldsValue({ attributes: {} })
    }
  }, [open, current, form])

  if (!type) return null

  const onError = (error: Error, target: FormInstance = form) => {
    if (!applyFieldErrors(target, error, t)) message.error(errorMessage(error, t))
  }

  const onFinish = (values: FormValues) => {
    const attributes = Object.fromEntries(
      Object.entries(values.attributes ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''),
    )
    const dates = {
      plannedStart: values.plannedStart?.format('YYYY-MM-DD'),
      plannedEnd: values.plannedEnd?.format('YYYY-MM-DD'),
    }
    if (current) {
      update.mutate(
        {
          id: current.id,
          body: {
            title: values.title,
            description: values.description,
            assigneeId: values.assigneeId,
            serviceId: values.serviceId,
            assetId: values.assetId,
            ...dates,
            forecastEnd: values.forecastEnd?.format('YYYY-MM-DD'),
            attributes,
            version: current.version,
          },
        },
        { onSuccess: () => message.success(t('common.saved')), onError: (e) => onError(e) },
      )
    } else {
      create.mutate(
        {
          typeId: type.id,
          title: values.title,
          description: values.description,
          assigneeId: values.assigneeId,
          serviceId: values.serviceId,
          assetId: values.assetId,
          parentId,
          ...dates,
          attributes,
        },
        {
          onSuccess: (created) => {
            message.success(t('common.saved'))
            onOpenTask(created.id)
          },
          onError: (e) => onError(e),
        },
      )
    }
  }

  const moveTo = (status: string, closingValues?: Record<string, unknown>) => {
    if (!current) return
    changeStatus.mutate(
      { id: current.id, body: { status, closingValues, version: current.version } },
      {
        onSuccess: () => setClosing(null),
        onError: (e) => (closingValues ? onError(e, closingForm) : message.error(errorMessage(e, t))),
      },
    )
  }

  const onStatusSelect = (status: string) => {
    const target = type.statuses.find((s) => s.code === status)
    if (target?.category === 'done' && type.closingFields.length > 0) {
      closingForm.resetFields()
      setClosing({ status })
    } else {
      moveTo(status)
    }
  }

  const statusOf = (code: string) => type.statuses.find((s) => s.code === code)
  const statusTag = (code: string, category: string) => (
    <Tag color={statusOf(code)?.color && statusOf(code)?.color !== 'default' ? statusOf(code)?.color : CATEGORY_COLORS[category as Task['statusCategory']]}>
      {localized(statusOf(code)?.label, lang) || code}
    </Tag>
  )
  const refItem = (ref: TaskRef, onRemove?: () => void) => (
    <List.Item
      actions={onRemove && fullEdit ? [<Button key="rm" type="text" size="small" icon={<DeleteOutlined />} onClick={onRemove} />] : []}
    >
      <Flex gap={8} align="center">
        <Typography.Link onClick={() => onOpenTask(ref.id)}>#{ref.number}</Typography.Link>
        {ref.title}
        <Tag color={CATEGORY_COLORS[ref.statusCategory as Task['statusCategory']]}>{t(`tasks.category.${ref.statusCategory}`)}</Tag>
      </Flex>
    </List.Item>
  )

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={640}
      destroyOnHidden
      title={
        current ? (
          <Flex gap={8} align="center">
            #{current.number} {localized(type.name, lang)}
            {current.parentNumber && (
              <Typography.Link style={{ fontWeight: 'normal' }} onClick={() => current.parentId && onOpenTask(current.parentId)}>
                ↑ #{current.parentNumber}
              </Typography.Link>
            )}
          </Flex>
        ) : (
          t('tasks.new', { type: localized(type.name, lang) })
        )
      }
      footer={
        (fullEdit || datesEdit || !current) && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" loading={create.isPending || update.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        )
      }
    >
      {current && (
        <>
          <Flex gap={12} align="center" wrap style={{ marginBottom: 12 }}>
            {statusEdit ? (
              <Select
                value={current.status}
                style={{ minWidth: 180 }}
                loading={changeStatus.isPending}
                onChange={onStatusSelect}
                options={type.statuses.map((s) => ({
                  value: s.code,
                  label: localized(s.label, lang),
                  disabled:
                    (!rights.full && (s.category === 'cancelled' || (closed && s.category !== 'done'))) ||
                    (current.openBlockers > 0 && (s.category === 'active' || s.category === 'done')),
                }))}
              />
            ) : (
              statusTag(current.status, current.statusCategory)
            )}
            {current.openBlockers > 0 && <Tag color="red">{t('tasks.blockedCount', { count: current.openBlockers })}</Tag>}
            {current.childrenTotal > 0 && (
              <Tag>{t('tasks.childrenProgress', { done: current.childrenTotal - current.childrenOpen, total: current.childrenTotal })}</Tag>
            )}
          </Flex>
          {current.workShiftId && type.code === 'shift_report' && (
            <Button type="primary" ghost style={{ marginBottom: 12 }} onClick={() => setReportShift(current.workShiftId ?? undefined)}>
              {t('reports.open')}
            </Button>
          )}
          <Descriptions
            size="small"
            column={2}
            style={{ marginBottom: 12 }}
            items={[
              { label: t('tasks.author'), children: current.authorName ?? '—' },
              { label: t('services.actualStart'), children: current.actualStart ? dayjs(current.actualStart).format('DD.MM.YYYY HH:mm') : '—' },
              { label: t('tasks.created'), children: dayjs(current.createdAt).format('DD.MM.YYYY') },
              { label: t('services.actualEnd'), children: current.actualEnd ? dayjs(current.actualEnd).format('DD.MM.YYYY HH:mm') : '—' },
            ]}
          />
          {current.closingValues && type.closingFields.length > 0 && (
            <Descriptions
              size="small"
              bordered
              column={1}
              title={t('tasks.closingForm')}
              style={{ marginBottom: 12 }}
              items={type.closingFields.map((f) => ({
                label: localized(f.label, lang),
                children: String(current.closingValues?.[f.key] ?? '—') + (f.unit && current.closingValues?.[f.key] != null ? ` ${f.unit}` : ''),
              }))}
            />
          )}
        </>
      )}

      <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <Form.Item name="title" label={t('tasks.title')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input maxLength={300} disabled={!!current && !fullEdit} />
        </Form.Item>
        <Form.Item name="description" label={t('tasks.description')}>
          <Input.TextArea rows={3} disabled={!!current && !fullEdit} />
        </Form.Item>
        <Flex gap={12} wrap>
          <Form.Item name="assigneeId" label={t('tasks.assignee')} style={{ minWidth: 240, flex: 1 }}>
            <Select allowClear showSearch={{ optionFilterProp: 'label' }} options={assignees} disabled={!!current && !fullEdit} />
          </Form.Item>
          {type.subjectKind === 'service' && (
            <Form.Item name="serviceId" label={t('tasks.service')} rules={[{ required: true, message: t('validation.required') }]} style={{ minWidth: 200 }}>
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                disabled={!!current && !fullEdit}
                options={(services.data ?? [])
                  .filter((s) => s.status !== 'cancelled' || s.id === current?.serviceId)
                  .map((s) => ({ value: s.id, label: s.code }))}
              />
            </Form.Item>
          )}
          {type.subjectKind === 'asset' && (
            <Form.Item name="assetId" label={t('tasks.asset')} rules={[{ required: true, message: t('validation.required') }]} style={{ minWidth: 200 }}>
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                disabled={!!current && !fullEdit}
                loading={assets.isPending}
                options={(assets.data?.items ?? []).map((a) => ({ value: a.id, label: [a.code, a.name].filter(Boolean).join(' · ') }))}
              />
            </Form.Item>
          )}
        </Flex>
        <Flex gap={12} wrap>
          <Form.Item name="plannedStart" label={t('services.plannedStart')}>
            <DatePicker format="DD.MM.YYYY" disabled={!!current && !datesEdit} />
          </Form.Item>
          <Form.Item name="plannedEnd" label={t('services.plannedEnd')}>
            <DatePicker format="DD.MM.YYYY" disabled={!!current && !datesEdit} />
          </Form.Item>
          {current && (
            <Form.Item name="forecastEnd" label={t('tasks.forecastEnd')}>
              <DatePicker format="DD.MM.YYYY" disabled={!datesEdit} />
            </Form.Item>
          )}
        </Flex>
        <ConfigProvider componentDisabled={!!current && !fullEdit}>
          {type.fields.map((field) => (
            <AttributeField key={field.key} field={field} language={lang} />
          ))}
        </ConfigProvider>
      </Form>

      {current && (
        <>
          <Divider titlePlacement="start">{t('tasks.children')}</Divider>
          <List
            size="small"
            dataSource={links.data?.children ?? []}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('tasks.noChildren')} /> }}
            renderItem={(ref) => refItem(ref)}
          />
          {fullEdit && (
            <Dropdown
              menu={{
                items: types.filter((tt) => !tt.archived).map((tt) => ({ key: tt.id, label: localized(tt.name, lang) })),
                onClick: ({ key }) => {
                  const childType = types.find((tt) => tt.id === key)
                  if (childType) onAddChild(current, childType)
                },
              }}
            >
              <Button size="small" icon={<PlusOutlined />} style={{ marginTop: 8 }}>
                {t('tasks.addChild')}
              </Button>
            </Dropdown>
          )}

          <Divider titlePlacement="start">{t('tasks.links')}</Divider>
          {(['blockedBy', 'blocks', 'related'] as const).map((group) =>
            (links.data ? links.data[group] : []).length > 0 ? (
              <div key={group}>
                <Typography.Text type="secondary">{t(`tasks.linkGroups.${group}`)}</Typography.Text>
                <List
                  size="small"
                  dataSource={links.data?.[group] ?? []}
                  renderItem={(ref) =>
                    refItem(ref, () =>
                      ref.linkId &&
                      removeLink.mutate(
                        { id: current.id, linkId: ref.linkId },
                        { onError: (e) => message.error(errorMessage(e, t)) },
                      ),
                    )
                  }
                />
              </div>
            ) : null,
          )}
          {fullEdit && (
            <Button
              size="small"
              icon={<LinkOutlined />}
              onClick={() => {
                linkForm.resetFields()
                linkForm.setFieldsValue({ kind: 'blocked_by' })
                setLinking(true)
              }}
            >
              {t('tasks.addLink')}
            </Button>
          )}

          <Divider titlePlacement="start">{t('tasks.history')}</Divider>
          <Timeline
            items={(history.data ?? []).map((h) => ({
              content: (
                <Space orientation="vertical" size={0}>
                  <Typography.Text>
                    {t(`tasks.actions.${h.action}`, { defaultValue: h.action })}
                    {statusChange(h.changes)
                      .map((c) => localized(statusOf(c)?.label, lang) || c)
                      .join(' → ')
                      .replace(/^(.)/, ': $1')}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {[h.userName, dayjs(h.createdAt).format('DD.MM.YYYY HH:mm')].filter(Boolean).join(' · ')}
                  </Typography.Text>
                </Space>
              ),
            }))}
          />
        </>
      )}

      <Modal
        open={!!closing}
        title={t('tasks.closingForm')}
        onCancel={() => setClosing(null)}
        onOk={() => closingForm.submit()}
        okText={t('tasks.close')}
        cancelText={t('common.cancel')}
        confirmLoading={changeStatus.isPending}
        destroyOnHidden
      >
        <Form
          form={closingForm}
          layout="vertical"
          requiredMark="optional"
          onFinish={(values) => {
            const filled = Object.fromEntries(
              Object.entries(values.closingValues ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''),
            )
            if (closing) moveTo(closing.status, filled)
          }}
        >
          {type.closingFields.map((field) => (
            <AttributeField key={field.key} field={field} language={lang} namePrefix="closingValues" />
          ))}
        </Form>
      </Modal>

      <Modal
        open={linking}
        title={t('tasks.addLink')}
        onCancel={() => setLinking(false)}
        onOk={() => linkForm.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={addLink.isPending}
        destroyOnHidden
      >
        <Form
          form={linkForm}
          layout="vertical"
          onFinish={(values) =>
            current &&
            addLink.mutate(
              { id: current.id, body: { taskId: values.taskId, kind: values.kind as 'blocks' | 'blocked_by' | 'relates' } },
              {
                onSuccess: () => setLinking(false),
                onError: (e) => {
                  if (e instanceof ApiError && Object.keys(e.fields).length) applyFieldErrors(linkForm, e, t)
                  else message.error(errorMessage(e, t))
                },
              },
            )
          }
        >
          <Form.Item name="kind" label={t('tasks.linkKind')}>
            <Select
              options={(['blocked_by', 'blocks', 'relates'] as const).map((k) => ({ value: k, label: t(`tasks.linkKinds.${k}`) }))}
            />
          </Form.Item>
          <Form.Item name="taskId" label={t('tasks.task')} rules={[{ required: true, message: t('validation.required') }]}>
            <Select
              showSearch={{ optionFilterProp: 'label' }}
              loading={projectTasks.isPending}
              options={(projectTasks.data ?? [])
                .filter((tk) => tk.id !== current?.id)
                .map((tk) => ({ value: tk.id, label: `#${tk.number} ${tk.title}` }))}
            />
          </Form.Item>
        </Form>
      </Modal>
      <ShiftReportDrawer
        project={project}
        shiftId={reportShift}
        title={current?.title}
        onClose={() => setReportShift(undefined)}
      />
    </Drawer>
  )
}

/** ["new", "in_progress"] зі зміни статусу в історії або порожньо. */
function statusChange(changes?: Record<string, unknown> | null): string[] {
  const status = changes?.status
  return Array.isArray(status) ? status.map(String) : []
}
