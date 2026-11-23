import { LeftOutlined, PlusOutlined, RightOutlined, TeamOutlined } from '@ant-design/icons'
import { App, Button, DatePicker, Empty, Flex, Form, Modal, Select, Table, Tag, Tooltip, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errorMessage } from '../../api/formErrors'
import type { Project, Shift } from '../../api/projects'
import { useGenerateShifts } from '../../api/shiftReports'
import { type WorkShift, useShiftBoard } from '../../api/shifts'
import { CATEGORY_COLORS, type TaskType, useTaskTypes } from '../../api/tasks'
import { localized } from '../../i18n/localized'
import { ServiceLevelTag } from '../assets/ServiceLevelTag'
import { TaskDrawer } from '../tasks/TaskDrawer'
import type { TaskDrawerState } from '../tasks/ProjectTasksTab'
import { useTaskRights } from '../tasks/taskRights'
import { type NewShiftTarget, NewShiftModal } from './NewShiftModal'
import { ShiftDrawer } from './ShiftDrawer'

const DAYS = 7
const DATE = 'YYYY-MM-DD'

interface Row {
  assetId: string
  code: string
  name?: string
  ranges: { start: string; end?: string }[]
  serviceLevel: string
}

/**
 * Дошка «основне обладнання × зміни» за тиждень. Зміни створює планувальник у момент їх початку;
 * менеджер і супервайзер можуть додати пропущену чи позапланову зміну й змінити бригаду.
 */
export function ProjectShiftsTab({ project }: { project: Project }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const rights = useTaskRights(project)
  const [end, setEnd] = useState<Dayjs>(dayjs())
  const from = end.subtract(DAYS - 1, 'day')
  const board = useShiftBoard(project.id, from.format(DATE), end.format(DATE))
  const types = useTaskTypes(true)
  const [newShift, setNewShift] = useState<NewShiftTarget>()
  const [shiftId, setShiftId] = useState<string>()
  const [taskDrawer, setTaskDrawer] = useState<TaskDrawerState>({ open: false })
  const generate = useGenerateShifts(project.id)
  const [generating, setGenerating] = useState(false)
  const [generateForm] = Form.useForm<{ range: [Dayjs, Dayjs]; assetId?: string }>()

  const typeList = useMemo(() => types.data ?? [], [types.data])
  const typeByCode = useMemo(() => new Map(typeList.map((tt) => [tt.code, tt])), [typeList])
  const days = Array.from({ length: DAYS }, (_, i) => from.add(i, 'day'))
  const today = dayjs().format(DATE)

  const rows: Row[] = useMemo(() => {
    const byAsset = new Map<string, Row>()
    for (const a of board.data?.assets ?? []) {
      const row = byAsset.get(a.assetId) ?? {
        assetId: a.assetId,
        code: a.code,
        name: a.name ?? undefined,
        ranges: [],
        serviceLevel: a.serviceLevel,
      }
      row.ranges.push({ start: a.startDate, end: a.endDate ?? undefined })
      byAsset.set(a.assetId, row)
    }
    for (const s of board.data?.shifts ?? []) {
      if (!byAsset.has(s.assetId)) {
        byAsset.set(s.assetId, { assetId: s.assetId, code: s.assetCode, name: s.assetName ?? undefined, ranges: [], serviceLevel: 'ok' })
      }
    }
    return [...byAsset.values()]
  }, [board.data])

  // Колонки змін: за графіком на кінцеву дату + коди, що трапились у змінах тижня
  const shiftColumns: Shift[] = useMemo(() => {
    const list = [...(board.data?.scheduleShifts ?? [])]
    for (const s of board.data?.shifts ?? []) {
      if (!list.some((x) => x.code === s.shiftCode)) {
        list.push({ code: s.shiftCode, name: s.shiftName, start: '', durationMinutes: s.durationMinutes })
      }
    }
    return list
  }, [board.data])

  const shiftsByKey = useMemo(() => {
    const map = new Map<string, WorkShift>()
    for (const s of board.data?.shifts ?? []) map.set(`${s.assetId}|${s.date}|${s.shiftCode}`, s)
    return map
  }, [board.data])

  const taskTag = (status: string, category: string, typeCode: string) => {
    const type: TaskType | undefined = typeByCode.get(typeCode)
    const def = type?.statuses.find((s) => s.code === status)
    const color =
      def?.color && def.color !== 'default' ? def.color : CATEGORY_COLORS[category as keyof typeof CATEGORY_COLORS]
    return (
      <Tag color={color} style={{ marginInlineEnd: 0 }}>
        {localized(def?.label, lang) || status}
      </Tag>
    )
  }

  const onAssignment = (row: Row, date: string) =>
    row.ranges.some((r) => r.start <= date && (!r.end || r.end >= date))

  const cell = (row: Row, day: Dayjs, shift: Shift) => {
    const date = day.format(DATE)
    const existing = shiftsByKey.get(`${row.assetId}|${date}|${shift.code}`)
    if (existing) {
      const lead = existing.crew.find((c) => c.lead)
      const report = existing.tasks.find((task) => task.typeCode === 'shift_report') ?? existing.tasks[0]
      return (
        <Button
          key={shift.code}
          size="small"
          block
          type={existing.state === 'running' ? 'primary' : 'default'}
          ghost={existing.state === 'running'}
          onClick={() => setShiftId(existing.id)}
          style={{ height: 'auto', padding: '2px 6px', textAlign: 'left' }}
        >
          <Flex vertical gap={2} style={{ width: '100%' }}>
            <Flex justify="space-between" gap={4}>
              <Typography.Text strong style={{ fontSize: 12 }}>
                {shift.name}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                <TeamOutlined /> {existing.crew.length}
              </Typography.Text>
            </Flex>
            {lead && (
              <Typography.Text ellipsis style={{ fontSize: 12, maxWidth: 140 }}>
                {lead.fullName}
              </Typography.Text>
            )}
            {report && taskTag(report.status, report.statusCategory, report.typeCode)}
          </Flex>
        </Button>
      )
    }
    if (!rights.full || !onAssignment(row, date) || date > dayjs().add(1, 'day').format(DATE)) {
      return null
    }
    return (
      <Tooltip key={shift.code} title={t('shifts.addManual')}>
        <Button
          size="small"
          block
          type="dashed"
          icon={<PlusOutlined />}
          onClick={() =>
            setNewShift({ assetId: row.assetId, assetCode: row.code, date, shiftCode: shift.code, shiftName: shift.name })
          }
        >
          {shift.name}
        </Button>
      </Tooltip>
    )
  }

  return (
    <>
      <Flex justify="space-between" align="center" gap={8} wrap style={{ marginBottom: 16 }}>
        <Typography.Text type="secondary">{t('shifts.hint', { timeZone: project.timeZone })}</Typography.Text>
        <Flex gap={8}>
          <Button icon={<LeftOutlined />} onClick={() => setEnd(end.subtract(DAYS, 'day'))} />
          <DatePicker value={end} onChange={(d) => d && setEnd(d)} allowClear={false} format="DD.MM.YYYY" />
          <Button icon={<RightOutlined />} onClick={() => setEnd(end.add(DAYS, 'day'))} />
          <Button onClick={() => setEnd(dayjs())}>{t('shifts.today')}</Button>
          {rights.full && (
            <Button
              icon={<PlusOutlined />}
              onClick={() => {
                generateForm.resetFields()
                setGenerating(true)
              }}
            >
              {t('shifts.generate')}
            </Button>
          )}
        </Flex>
      </Flex>

      {rows.length === 0 && !board.isPending ? (
        <Empty description={t('shifts.noEquipment')} />
      ) : (
        <Table<Row>
          rowKey="assetId"
          size="small"
          bordered
          pagination={false}
          loading={board.isPending}
          dataSource={rows}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('shifts.equipment'),
              key: 'asset',
              fixed: 'left',
              render: (_, row) => (
                <Flex vertical>
                  <Typography.Text strong>{row.code}</Typography.Text>
                  {row.name && <Typography.Text type="secondary">{row.name}</Typography.Text>}
                  <span>
                    <ServiceLevelTag level={row.serviceLevel} />
                  </span>
                </Flex>
              ),
            },
            ...days.map((day) => ({
              title: (
                <Typography.Text strong={day.format(DATE) === today}>{day.format('dd DD.MM')}</Typography.Text>
              ),
              key: day.format(DATE),
              width: 150,
              render: (_: unknown, row: Row) => (
                <Flex vertical gap={4}>
                  {shiftColumns.map((shift) => cell(row, day, shift))}
                </Flex>
              ),
            })),
          ]}
        />
      )}

      <ShiftDrawer
        project={project}
        shiftId={shiftId}
        types={typeList}
        canEdit={rights.full}
        onClose={() => setShiftId(undefined)}
        onOpenTask={(id) => setTaskDrawer({ open: true, taskId: id })}
      />

      <TaskDrawer
        open={taskDrawer.open}
        project={project}
        types={typeList}
        taskId={taskDrawer.taskId}
        newType={taskDrawer.newType}
        parentId={taskDrawer.parentId}
        onClose={() => setTaskDrawer({ open: false })}
        onOpenTask={(id) => setTaskDrawer({ open: true, taskId: id })}
        onAddChild={(parent, childType) => setTaskDrawer({ open: true, parentId: parent.id, newType: childType })}
      />
      <NewShiftModal
        project={project}
        target={newShift}
        onClose={() => setNewShift(undefined)}
        onCreated={(s) => setShiftId(s.id)}
      />
      <Modal
        open={generating}
        title={t('shifts.generate')}
        onCancel={() => setGenerating(false)}
        onOk={() => generateForm.submit()}
        okText={t('shifts.generateOk')}
        cancelText={t('common.cancel')}
        confirmLoading={generate.isPending}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">{t('shifts.generateHint')}</Typography.Paragraph>
        <Form
          form={generateForm}
          layout="vertical"
          onFinish={(v) =>
            generate.mutate(
              { from: v.range[0].format(DATE), to: v.range[1].format(DATE), assetId: v.assetId },
              {
                onSuccess: (r) => {
                  message.success(t('shifts.generated', { count: r.created }))
                  setGenerating(false)
                  setEnd(v.range[1])
                },
                onError: (e) => message.error(errorMessage(e, t)),
              },
            )
          }
        >
          <Form.Item name="range" label={t('shifts.period')} rules={[{ required: true, message: t('validation.required') }]}>
            <DatePicker.RangePicker
              format="DD.MM.YYYY"
              disabledDate={(d) => d.isAfter(dayjs(), 'day') || d.isBefore(dayjs(project.startDate), 'day')}
            />
          </Form.Item>
          <Form.Item name="assetId" label={t('shifts.equipment')}>
            <Select allowClear placeholder={t('shifts.allEquipment')} options={rows.map((r) => ({ value: r.assetId, label: r.code }))} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
