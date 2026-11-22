import { DownOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Dropdown, Flex, Form, Input, Modal, Popconfirm, Segmented, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { errorMessage } from '../../api/formErrors'
import { type Project, useProjectPersonnel } from '../../api/projects'
import {
  type ServiceAction,
  type ServiceItem,
  type ServiceStatus,
  type ServiceType,
  useCancelService,
  useProjectServices,
  useServiceAction,
  useServiceTypes,
} from '../../api/services'
import { formatAttribute } from '../../components/attributes'
import { localized } from '../../i18n/localized'
import { ServiceFormDrawer } from './ServiceFormDrawer'

const STATUS_COLORS: Record<ServiceStatus, string | undefined> = {
  planned: 'blue',
  in_progress: 'orange',
  completed: 'green',
  cancelled: undefined,
}

/** Скільки полів типу показувати в таблиці. */
const VISIBLE_FIELDS = 3

type Filter = 'open' | 'completed' | 'cancelled' | 'all'

/**
 * Послуги проєкту (свердловини, замовлення…). Менеджер і супервайзер проєкту ведуть їх повністю;
 * замовник (портал) додає й змінює лише заплановані.
 */
export function ProjectServicesTab({ project }: { project: Project }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const services = useProjectServices(project.id)
  const types = useServiceTypes(true)
  const crew = useProjectPersonnel(project.id)
  const action = useServiceAction()
  const cancel = useCancelService()
  const [filter, setFilter] = useState<Filter>('open')
  const [editing, setEditing] = useState<{ open: boolean; type?: ServiceType; service?: ServiceItem }>({ open: false })
  const [cancelling, setCancelling] = useState<ServiceItem>()
  const [cancelForm] = Form.useForm<{ reason: string }>()

  const lang = i18n.language
  const projectOpen = project.status === 'active'
  const manage = hasPermission(session, 'project.manage')
  const supervisor = (crew.data ?? []).some(
    (a) => a.active && a.projectRole === 'supervisor' && a.userId === session?.user.id,
  )
  const onSite = manage || supervisor
  const plan = hasPermission(session, 'service.plan')
  const canCreate = projectOpen && (onSite || plan)
  const canEdit = (s: ServiceItem) =>
    projectOpen && (onSite ? s.status === 'planned' || s.status === 'in_progress' : plan && s.status === 'planned')

  const typeById = new Map((types.data ?? []).map((type) => [type.id, type]))
  const activeTypes = (types.data ?? []).filter((type) => !type.archived)
  const rows = (services.data ?? []).filter((s) =>
    filter === 'all' ? true : filter === 'open' ? s.status === 'planned' || s.status === 'in_progress' : s.status === filter,
  )
  // Колонки полів — за першим типом у списку (зазвичай у проєкті один тип послуг)
  const mainType = typeById.get(rows[0]?.typeId ?? '') ?? activeTypes[0]

  const run = (s: ServiceItem, act: ServiceAction) =>
    action.mutate({ id: s.id, action: act }, { onError: (e) => message.error(errorMessage(e, t)) })

  const newButton =
    activeTypes.length <= 1 ? (
      <Button
        type="primary"
        icon={<PlusOutlined />}
        disabled={activeTypes.length === 0}
        onClick={() => setEditing({ open: true, type: activeTypes[0] })}
      >
        {activeTypes[0] ? t('services.new', { type: localized(activeTypes[0].name, lang) }) : t('services.noTypes')}
      </Button>
    ) : (
      <Dropdown
        menu={{
          items: activeTypes.map((type) => ({ key: type.id, label: localized(type.name, lang) })),
          onClick: ({ key }) => setEditing({ open: true, type: typeById.get(key) }),
        }}
      >
        <Button type="primary" icon={<PlusOutlined />}>
          {t('services.add')} <DownOutlined />
        </Button>
      </Dropdown>
    )

  return (
    <>
      <Flex justify="space-between" gap={8} wrap style={{ marginBottom: 16 }}>
        <Segmented<Filter>
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'open', label: t('services.filter.open') },
            { value: 'completed', label: t('services.status.completed') },
            { value: 'cancelled', label: t('services.status.cancelled') },
            { value: 'all', label: t('projects.all') },
          ]}
        />
        {canCreate && newButton}
      </Flex>

      <Table<ServiceItem>
        rowKey="id"
        size="middle"
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        loading={services.isPending}
        dataSource={rows}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('services.empty') }}
        columns={[
          {
            title: t('services.code'),
            dataIndex: 'code',
            render: (code: string, s) => (
              <Typography.Link onClick={() => setEditing({ open: true, type: typeById.get(s.typeId), service: s })}>
                {code}
              </Typography.Link>
            ),
          },
          ...(activeTypes.length > 1
            ? [
                {
                  title: t('assignments.type'),
                  key: 'type',
                  render: (_: unknown, s: ServiceItem) => localized(typeById.get(s.typeId)?.name, lang),
                },
              ]
            : []),
          {
            title: t('projects.statusLabel'),
            dataIndex: 'status',
            render: (status: ServiceStatus) => <Tag color={STATUS_COLORS[status]}>{t(`services.status.${status}`)}</Tag>,
          },
          ...(mainType?.fields.slice(0, VISIBLE_FIELDS) ?? []).map((field) => ({
            title: localized(field.label, lang),
            key: field.key,
            align: field.type === 'number' || field.type === 'integer' ? ('right' as const) : undefined,
            render: (_: unknown, s: ServiceItem) =>
              s.typeId === mainType?.id ? formatAttribute(field, s.attributes[field.key], lang) : '',
          })),
          ...(!project.customerId ? [{ title: t('projects.customer'), dataIndex: 'customerName' }] : []),
          {
            title: t('services.dates'),
            key: 'dates',
            render: (_: unknown, s: ServiceItem) =>
              s.actualStart
                ? `${dayjs(s.actualStart).format('DD.MM')} – ${s.actualEnd ? dayjs(s.actualEnd).format('DD.MM.YYYY') : '…'}`
                : s.plannedStart
                  ? `${t('services.planShort')} ${dayjs(s.plannedStart).format('DD.MM.YYYY')}`
                  : '',
          },
          {
            key: 'actions',
            align: 'right',
            render: (_: unknown, s: ServiceItem) =>
              projectOpen && (
                <Flex gap={4} justify="flex-end">
                  {onSite && s.status === 'planned' && (
                    <Button size="small" onClick={() => run(s, 'start')}>
                      {t('services.start')}
                    </Button>
                  )}
                  {onSite && s.status === 'in_progress' && (
                    <Popconfirm title={t('services.completeConfirm')} onConfirm={() => run(s, 'complete')}>
                      <Button size="small" type="primary">
                        {t('services.complete')}
                      </Button>
                    </Popconfirm>
                  )}
                  {canEdit(s) && (
                    <Button
                      size="small"
                      type="link"
                      danger
                      onClick={() => {
                        cancelForm.resetFields()
                        setCancelling(s)
                      }}
                    >
                      {t('services.cancel')}
                    </Button>
                  )}
                  {manage && (s.status === 'completed' || s.status === 'cancelled') && (
                    <Popconfirm title={t('services.reopenConfirm')} onConfirm={() => run(s, 'reopen')}>
                      <Button size="small" type="link">
                        {t('projects.reopen')}
                      </Button>
                    </Popconfirm>
                  )}
                </Flex>
              ),
          },
        ]}
      />

      <ServiceFormDrawer
        open={editing.open}
        project={project}
        type={editing.type}
        service={editing.service}
        editable={editing.service ? canEdit(editing.service) : canCreate}
        onClose={() => setEditing({ open: false })}
      />

      <Modal
        open={!!cancelling}
        title={t('services.cancelTitle', { code: cancelling?.code })}
        onCancel={() => setCancelling(undefined)}
        onOk={() => cancelForm.submit()}
        okText={t('services.cancel')}
        okButtonProps={{ danger: true }}
        cancelText={t('common.cancel')}
        confirmLoading={cancel.isPending}
        destroyOnHidden
      >
        <Form
          form={cancelForm}
          layout="vertical"
          onFinish={({ reason }) =>
            cancelling &&
            cancel.mutate(
              { id: cancelling.id, reason },
              { onSuccess: () => setCancelling(undefined), onError: (e) => message.error(errorMessage(e, t)) },
            )
          }
        >
          <Form.Item name="reason" label={t('services.cancelReason')} rules={[{ required: true, message: t('validation.required') }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
