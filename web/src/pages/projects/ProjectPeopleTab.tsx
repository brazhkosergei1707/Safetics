import { KeyOutlined, PlusOutlined, UserAddOutlined } from '@ant-design/icons'
import { App, Button, Flex, Form, Input, Modal, Popconfirm, Segmented, Select, Table, Tag } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { EMPLOYMENT_TYPES, type EmploymentType, usePersonnel, usePositions } from '../../api/personnel'
import {
  formatPeriod,
  type PersonnelAssignment,
  type Project,
  useAddCrewMember,
  useAssignPersonnel,
  useDeletePersonnelAssignment,
  useProjectPersonnel,
  useUpdatePersonnelAssignment,
} from '../../api/projects'
import { localized } from '../../i18n/localized'
import { AssignmentDates } from './AssignmentDates'

type Mode = { kind: 'assign' } | { kind: 'crew' } | { kind: 'edit'; assignment: PersonnelAssignment }

interface FormValues {
  personnelId?: string
  fullName?: string
  phone?: string
  employmentType?: EmploymentType
  organization?: string
  positionId?: string
  startDate: Dayjs
  endDate?: Dayjs
}

/**
 * Люди на проєкті. Менеджер призначає виконавців; супервайзер проєкту додає людей без входу
 * з посадами «може додавати супервайзер».
 */
export function ProjectPeopleTab({ project }: { project: Project }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const assignments = useProjectPersonnel(project.id)
  const positions = usePositions()
  const canManage = hasPermission(session, 'project.manage')
  const canManagePeople = canManage && hasPermission(session, 'personnel.manage')
  const personnel = usePersonnel({}, canManagePeople)
  const assign = useAssignPersonnel(project.id)
  const addCrew = useAddCrewMember(project.id)
  const update = useUpdatePersonnelAssignment(project.id)
  const remove = useDeletePersonnelAssignment(project.id)
  const [form] = Form.useForm<FormValues>()
  const [mode, setMode] = useState<Mode>()
  const [onlyActive, setOnlyActive] = useState(true)

  const isSupervisor = (assignments.data ?? []).some(
    (a) => a.active && a.projectRole === 'supervisor' && a.userId === session?.user.id,
  )
  const open = project.status === 'active'
  const canAddCrew = open && (canManagePeople || isSupervisor)
  const crewPositions = (positions.data ?? []).filter((p) => canManagePeople || p.supervisorCanAdd)
  const rows = (assignments.data ?? []).filter((a) => !onlyActive || a.active || dayjs(a.startDate).isAfter(dayjs()))

  const start = (next: Mode) => {
    form.resetFields()
    if (next.kind === 'edit') {
      const a = next.assignment
      form.setFieldsValue({
        positionId: a.positionId,
        startDate: dayjs(a.startDate),
        endDate: a.endDate ? dayjs(a.endDate) : undefined,
      })
    } else {
      form.setFieldsValue({ startDate: dayjs(), employmentType: 'staff' })
    }
    setMode(next)
  }

  const onFinish = (values: FormValues) => {
    if (!mode) return
    const dates = { startDate: values.startDate.format('YYYY-MM-DD'), endDate: values.endDate?.format('YYYY-MM-DD') }
    const handlers = {
      onSuccess: () => {
        message.success(t('common.saved'))
        setMode(undefined)
      },
      onError: (error: Error) => {
        if (error instanceof ApiError && error.code === 'overlap') {
          form.setFields([{ name: 'startDate', errors: [t('assignments.overlapPerson')] }])
        } else if (!applyFieldErrors(form, error, t)) {
          message.error(errorMessage(error, t))
        }
      },
    }
    if (mode.kind === 'assign') {
      assign.mutate({ personnelId: values.personnelId!, positionId: values.positionId, ...dates }, handlers)
    } else if (mode.kind === 'crew') {
      addCrew.mutate(
        {
          fullName: values.fullName!,
          phone: values.phone,
          employmentType: values.employmentType,
          organization: values.organization,
          positionId: values.positionId!,
          ...dates,
        },
        handlers,
      )
    } else {
      update.mutate({ id: mode.assignment.id, body: { positionId: values.positionId!, ...dates } }, handlers)
    }
  }

  const positionOptions = (list: typeof crewPositions) =>
    list.map((p) => ({ value: p.id, label: localized(p.name, i18n.language) }))
  const assigned = new Set((assignments.data ?? []).filter((a) => a.active).map((a) => a.personnelId))

  return (
    <>
      <Flex justify="space-between" gap={8} wrap style={{ marginBottom: 16 }}>
        <Segmented
          value={onlyActive ? 'active' : 'all'}
          onChange={(v) => setOnlyActive(v === 'active')}
          options={[
            { value: 'active', label: t('assignments.currentAndPlanned') },
            { value: 'all', label: t('assignments.history') },
          ]}
        />
        <Flex gap={8}>
          {canAddCrew && (
            <Button icon={<UserAddOutlined />} onClick={() => start({ kind: 'crew' })}>
              {t('assignments.addCrew')}
            </Button>
          )}
          {open && canManagePeople && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => start({ kind: 'assign' })}>
              {t('assignments.assignPerson')}
            </Button>
          )}
        </Flex>
      </Flex>

      <Table<PersonnelAssignment>
        rowKey="id"
        size="middle"
        pagination={false}
        loading={assignments.isPending}
        dataSource={rows}
        locale={{ emptyText: t('assignments.noPeople') }}
        columns={[
          {
            title: t('customers.fullName'),
            dataIndex: 'fullName',
            render: (name: string, a) => (
              <Flex gap={6} align="center">
                {name}
                {a.hasLogin && <KeyOutlined title={t('personnel.login')} />}
              </Flex>
            ),
          },
          {
            title: t('personnel.position'),
            key: 'position',
            render: (_, a) => (
              <Flex gap={6} align="center">
                {localized(a.positionName, i18n.language)}
                {a.projectRole !== 'none' && <Tag color="orange">{t(`personnel.projectRoles.${a.projectRole}`)}</Tag>}
              </Flex>
            ),
          },
          {
            title: t('personnel.employmentType'),
            key: 'employment',
            render: (_, a) => [t(`personnel.employment.${a.employmentType}`), a.organization].filter(Boolean).join(' · '),
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
          ...(open && canManage
            ? [
                {
                  key: 'actions',
                  align: 'right' as const,
                  render: (_: unknown, a: PersonnelAssignment) => (
                    <Flex gap={4} justify="flex-end">
                      <Button type="link" size="small" onClick={() => start({ kind: 'edit', assignment: a })}>
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
        open={!!mode}
        title={
          mode?.kind === 'crew'
            ? t('assignments.addCrew')
            : mode?.kind === 'edit'
              ? mode.assignment.fullName
              : t('assignments.assignPerson')
        }
        onCancel={() => setMode(undefined)}
        onOk={() => form.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={assign.isPending || addCrew.isPending || update.isPending}
        destroyOnHidden
      >
        <Form<FormValues> name="assignPerson" form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          {mode?.kind === 'assign' && (
            <Form.Item name="personnelId" label={t('assignments.person')} rules={[{ required: true, message: t('validation.required') }]}>
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                loading={personnel.isPending}
                options={(personnel.data ?? [])
                  .filter((p) => !assigned.has(p.id))
                  .map((p) => ({ value: p.id, label: p.fullName }))}
                onChange={(id: string) => {
                  const person = personnel.data?.find((p) => p.id === id)
                  if (person?.positionId) form.setFieldValue('positionId', person.positionId)
                }}
              />
            </Form.Item>
          )}
          {mode?.kind === 'crew' && (
            <>
              <Form.Item name="fullName" label={t('customers.fullName')} rules={[{ required: true, message: t('validation.required') }]}>
                <Input />
              </Form.Item>
              <Form.Item name="employmentType" label={t('personnel.employmentType')}>
                <Select options={EMPLOYMENT_TYPES.map((e) => ({ value: e, label: t(`personnel.employment.${e}`) }))} />
              </Form.Item>
              <Form.Item name="organization" label={t('personnel.organization')}>
                <Input />
              </Form.Item>
              <Form.Item name="phone" label={t('customers.phone')}>
                <Input />
              </Form.Item>
            </>
          )}
          <Form.Item
            name="positionId"
            label={t('assignments.positionOnProject')}
            rules={[{ required: mode?.kind !== 'assign', message: t('validation.required') }]}
            extra={mode?.kind === 'crew' && !canManagePeople ? t('assignments.crewPositionsHint') : undefined}
          >
            <Select options={positionOptions(mode?.kind === 'crew' ? crewPositions : positions.data ?? [])} />
          </Form.Item>
          <AssignmentDates />
        </Form>
      </Modal>
    </>
  )
}
