import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Flex, Form, Modal, Popconfirm, Select, Table, Tag, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  type EquipmentAssignment,
  formatPeriod,
  type Project,
  useAssignEquipment,
  useAvailableEquipment,
  useDeleteEquipmentAssignment,
  useProjectEquipment,
  useUpdateEquipmentAssignment,
} from '../../api/projects'
import { localized } from '../../i18n/localized'
import { AssignmentDates } from './AssignmentDates'

interface FormValues {
  assetId?: string
  startDate: Dayjs
  endDate?: Dayjs
}

/**
 * Обладнання на проєкті. Основне — лише на одному проєкті одночасно, допоміжне — на кількох;
 * вкладене їде з батьківським. Дати в формі вибираються першими: від них залежить, що вільне.
 */
export function ProjectEquipmentTab({ project }: { project: Project }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const equipment = useProjectEquipment(project.id)
  const canManage = hasPermission(session, 'project.manage') && project.status === 'active'
  const [editing, setEditing] = useState<{ open: boolean; assignment?: EquipmentAssignment }>({ open: false })
  const [form] = Form.useForm<FormValues>()
  const startDate = Form.useWatch('startDate', form)
  const endDate = Form.useWatch('endDate', form)
  const available = useAvailableEquipment(
    project.id,
    startDate?.format('YYYY-MM-DD'),
    endDate?.format('YYYY-MM-DD'),
    editing.open && !editing.assignment && !!startDate,
  )
  const assign = useAssignEquipment(project.id)
  const update = useUpdateEquipmentAssignment(project.id)
  const remove = useDeleteEquipmentAssignment(project.id)

  const open = (assignment?: EquipmentAssignment) => {
    form.resetFields()
    form.setFieldsValue(
      assignment
        ? { startDate: dayjs(assignment.startDate), endDate: assignment.endDate ? dayjs(assignment.endDate) : undefined }
        : { startDate: dayjs() },
    )
    setEditing({ open: true, assignment })
  }

  const onFinish = (values: FormValues) => {
    const dates = { startDate: values.startDate.format('YYYY-MM-DD'), endDate: values.endDate?.format('YYYY-MM-DD') }
    const handlers = {
      onSuccess: () => {
        message.success(t('common.saved'))
        setEditing({ open: false })
      },
      onError: (error: Error) => {
        if (error instanceof ApiError && error.code === 'overlap') {
          form.setFields([{ name: 'startDate', errors: [t('assignments.overlapEquipment')] }])
        } else if (!applyFieldErrors(form, error, t)) {
          message.error(errorMessage(error, t))
        }
      },
    }
    if (editing.assignment) update.mutate({ id: editing.assignment.id, body: dates }, handlers)
    else assign.mutate({ assetId: values.assetId!, ...dates }, handlers)
  }

  /** «Зайнято до 15.12.2026 (TZG-01)», «Уже на цьому проєкті» */
  const busyLabel = (a: (typeof options)[number]) => {
    if (a.onThisProject) return t('assignments.onThisProject')
    const until = a.busyUntil ? dayjs(a.busyUntil).format('DD.MM.YYYY') : t('assignments.indefinitely')
    return t('assignments.busyUntil', { until, project: a.busyProjectCode ?? t('assignments.otherProject') })
  }
  const options = available.data ?? []

  return (
    <>
      {canManage && (
        <Flex justify="flex-end" style={{ marginBottom: 16 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => open()}>
            {t('assignments.assignEquipment')}
          </Button>
        </Flex>
      )}
      <Table<EquipmentAssignment>
        rowKey="id"
        size="middle"
        pagination={false}
        loading={equipment.isPending}
        dataSource={equipment.data ?? []}
        locale={{ emptyText: t('assignments.noEquipment') }}
        columns={[
          { title: t('assets.code'), dataIndex: 'assetCode' },
          { title: t('assets.name'), dataIndex: 'assetName' },
          {
            title: t('assignments.type'),
            key: 'type',
            render: (_, a) => (
              <Flex gap={6} align="center">
                {localized(a.typeName, i18n.language)}
                {a.category === 'primary' && <Tag color="orange">{t('assets.primary')}</Tag>}
              </Flex>
            ),
          },
          {
            title: t('projects.dates'),
            key: 'dates',
            render: (_, a) => (
              <Flex gap={6} align="center">
                {formatPeriod(a.startDate, a.endDate)}
                {a.active && <Tag color="green">{t('projects.current')}</Tag>}
              </Flex>
            ),
          },
          ...(canManage
            ? [
                {
                  key: 'actions',
                  align: 'right' as const,
                  render: (_: unknown, a: EquipmentAssignment) => (
                    <Flex gap={4} justify="flex-end">
                      <Button type="link" size="small" onClick={() => open(a)}>
                        {t('common.edit')}
                      </Button>
                      <Popconfirm
                        title={t('assignments.deleteConfirm')}
                        onConfirm={() => remove.mutate(a.id, { onError: (e) => message.error(errorMessage(e, t)) })}
                      >
                        <Button type="link" size="small" danger>
                          {t('common.delete')}
                        </Button>
                      </Popconfirm>
                    </Flex>
                  ),
                },
              ]
            : []),
        ]}
      />
      <Modal
        open={editing.open}
        title={editing.assignment ? editing.assignment.assetCode : t('assignments.assignEquipment')}
        onCancel={() => setEditing({ open: false })}
        onOk={() => form.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={assign.isPending || update.isPending}
        destroyOnHidden
      >
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          <AssignmentDates />
          {!editing.assignment && (
            <Form.Item
              name="assetId"
              label={t('assignments.equipment')}
              rules={[{ required: true, message: t('validation.required') }]}
              extra={t('assignments.nestedHint')}
            >
              <Select
                showSearch={{ optionFilterProp: 'title' }}
                loading={available.isFetching}
                options={options.map((a) => ({
                  value: a.assetId,
                  disabled: !a.available,
                  title: [a.assetCode, a.assetName].filter(Boolean).join(' · '),
                  label: (
                    <Flex justify="space-between" gap={8}>
                      <span>
                        {[a.assetCode, a.assetName].filter(Boolean).join(' · ')}{' '}
                        <Typography.Text type="secondary">
                          {localized(a.typeName, i18n.language)}
                          {a.category === 'auxiliary' ? ` · ${t('assets.auxiliary')}` : ''}
                        </Typography.Text>
                      </span>
                      {!a.available && <Typography.Text type="warning">{busyLabel(a)}</Typography.Text>}
                    </Flex>
                  ),
                }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </>
  )
}
