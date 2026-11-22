import { App, Alert, Button, DatePicker, Descriptions, Drawer, Flex, Form, Input, Select } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { useCustomers } from '../../api/customers'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import type { Project } from '../../api/projects'
import { type ServiceItem, type ServiceType, useCreateService, useUpdateService } from '../../api/services'
import { AttributeField } from '../../components/AttributeField'
import { localized } from '../../i18n/localized'
import { useAssigneeOptions } from '../tasks/taskRights'

interface FormValues {
  code: string
  name?: string
  customerId?: string
  plannedStart?: Dayjs
  plannedEnd?: Dayjs
  assigneeId?: string
  attributes: Record<string, unknown>
}

interface Props {
  open: boolean
  project: Project
  type?: ServiceType
  /** undefined — нова послуга */
  service?: ServiceItem
  /** false — лише перегляд (закрита послуга або немає прав) */
  editable: boolean
  /** Менеджер або супервайзер: обирає відповідального (замовник — ні, буде супервайзер) */
  canAssign: boolean
  onClose: () => void
}

/** Форма послуги: поля будуються з типу послуги, як у обладнання. */
export function ServiceFormDrawer({ open, project, type, service, editable, canAssign, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const [form] = Form.useForm<FormValues>()
  const create = useCreateService(project.id)
  const update = useUpdateService()
  // Власний замовник — лише в постійному проєкті без замовника
  const ownCustomer = !project.customerId
  const customers = useCustomers({}, open && ownCustomer && hasPermission(session, 'client.view'))
  const assignees = useAssigneeOptions(project, open && canAssign)

  useEffect(() => {
    if (!open) return
    form.resetFields()
    form.setFieldsValue(
      service
        ? {
            code: service.code,
            name: service.name ?? undefined,
            customerId: ownCustomer ? (service.customerId ?? undefined) : undefined,
            plannedStart: service.plannedStart ? dayjs(service.plannedStart) : undefined,
            plannedEnd: service.plannedEnd ? dayjs(service.plannedEnd) : undefined,
            assigneeId: service.assigneeId ?? undefined,
            attributes: service.attributes,
          }
        : { attributes: {} },
    )
  }, [open, service, form, ownCustomer])

  if (!type) return null

  const onFinish = (values: FormValues) => {
    const attributes = Object.fromEntries(
      Object.entries(values.attributes ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''),
    )
    const common = {
      code: values.code,
      name: values.name,
      customerId: ownCustomer ? values.customerId : undefined,
      plannedStart: values.plannedStart?.format('YYYY-MM-DD'),
      plannedEnd: values.plannedEnd?.format('YYYY-MM-DD'),
      // Без права призначати — лишаємо поточного (сервер усе одно не змінить)
      assigneeId: canAssign ? values.assigneeId : (service?.assigneeId ?? undefined),
      attributes,
    }
    const handlers = {
      onSuccess: () => {
        message.success(t('common.saved'))
        onClose()
      },
      onError: (error: Error) => {
        if (error instanceof ApiError && error.code === 'duplicate') {
          form.setFields([{ name: 'code', errors: [t('validation.duplicate')] }])
        } else if (!applyFieldErrors(form, error, t)) {
          message.error(errorMessage(error, t))
        }
      },
    }
    if (service) update.mutate({ id: service.id, body: { ...common, version: service.version } }, handlers)
    else create.mutate({ ...common, typeId: type.id }, handlers)
  }

  const typeName = localized(type.name, i18n.language)
  const fmt = (v?: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—')

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={480}
      destroyOnHidden
      title={service ? `${typeName} ${service.code}` : t('services.new', { type: typeName })}
      footer={
        editable && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" loading={create.isPending || update.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        )
      }
    >
      {service && (
        <Descriptions
          size="small"
          column={1}
          style={{ marginBottom: 16 }}
          items={[
            { label: t('projects.statusLabel'), children: t(`services.status.${service.status}`) },
            { label: t('services.actualStart'), children: fmt(service.actualStart) },
            { label: t('services.actualEnd'), children: fmt(service.actualEnd) },
            ...(service.customerName ? [{ label: t('projects.customer'), children: service.customerName }] : []),
            ...(service.mainTaskNumber != null
              ? [{ label: t('services.mainTask'), children: `#${service.mainTaskNumber}` }]
              : []),
          ]}
        />
      )}
      {service?.cancelReason && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} title={t('services.cancelReason')} description={service.cancelReason} />
      )}
      <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional" disabled={!editable}>
        <Form.Item name="code" label={t('services.code')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item name="name" label={t('assets.name')}>
          <Input maxLength={200} />
        </Form.Item>
        {ownCustomer && (
          <Form.Item name="customerId" label={t('projects.customer')}>
            <Select
              allowClear
              showSearch={{ optionFilterProp: 'label' }}
              loading={customers.isPending}
              options={(customers.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
            />
          </Form.Item>
        )}
        <Flex gap={12}>
          <Form.Item name="plannedStart" label={t('services.plannedStart')}>
            <DatePicker format="DD.MM.YYYY" />
          </Form.Item>
          <Form.Item name="plannedEnd" label={t('services.plannedEnd')}>
            <DatePicker format="DD.MM.YYYY" />
          </Form.Item>
        </Flex>
        {canAssign ? (
          <Form.Item name="assigneeId" label={t('services.assignee')} extra={t('services.assigneeHint')}>
            <Select allowClear showSearch={{ optionFilterProp: 'label' }} options={assignees} />
          </Form.Item>
        ) : (
          service?.assigneeName && (
            <Form.Item label={t('services.assignee')}>
              <Input value={service.assigneeName} disabled />
            </Form.Item>
          )
        )}
        {type.fields.map((field) => (
          <AttributeField key={field.key} field={field} language={i18n.language} />
        ))}
      </Form>
    </Drawer>
  )
}
