import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Descriptions, Divider, Drawer, Empty, Flex, Form, InputNumber, List, Select, Spin, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage, leadRename } from '../../api/formErrors'
import { type Project, useProjectPersonnel } from '../../api/projects'
import { useUpdateCrew, useWorkShift } from '../../api/shifts'
import { CATEGORY_COLORS, type TaskType } from '../../api/tasks'
import { localized } from '../../i18n/localized'
import { ShiftChecklistDrawer } from './ShiftChecklistDrawer'
import { ShiftReportDrawer } from './ShiftReportDrawer'

interface CrewRow {
  personnelId?: string
  hours?: number
}

interface FormValues {
  crew: CrewRow[]
  /** Відповідальний виконавець: на нього задачі зміни (чек-лист, рапорт) */
  leadId?: string
}

interface Props {
  project: Project
  shiftId?: string
  types: TaskType[]
  /** Менеджер або супервайзер проєкту */
  canEdit: boolean
  onClose: () => void
  onOpenTask: (id: string) => void
}

/** Картка зміни: час, бригада з одним відповідальним (він отримує задачі зміни), задачі зміни. */
export function ShiftDrawer({ project, shiftId, types, canEdit, onClose, onOpenTask }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()
  const shift = useWorkShift(shiftId)
  const personnel = useProjectPersonnel(project.id)
  const update = useUpdateCrew()
  const current = shift.data
  const crew = Form.useWatch('crew', form)
  const leadId = Form.useWatch('leadId', form)
  const [reportOpen, setReportOpen] = useState(false)
  const [checklistOpen, setChecklistOpen] = useState(false)

  useEffect(() => {
    if (!current) return
    form.setFieldsValue({
      crew: current.crew.map((c) => ({ personnelId: c.personnelId, hours: c.minutes / 60 })),
      leadId: current.crew.find((c) => c.lead)?.personnelId,
    })
  }, [current, form])

  // У бригаду — лише ті, хто в день зміни призначений на проєкт
  const { options, withLogin } = useMemo(() => {
    const seen = new Map<string, string>()
    const login = new Set<string>()
    if (current) {
      for (const a of personnel.data ?? []) {
        if (a.startDate <= current.date && (!a.endDate || a.endDate >= current.date)) {
          seen.set(a.personnelId, `${a.fullName} · ${localized(a.positionName, lang)}`)
          if (a.hasLogin) login.add(a.personnelId)
        }
      }
    }
    return { options: [...seen.entries()].map(([value, label]) => ({ value, label })), withLogin: login }
  }, [personnel.data, current, lang])
  const crewIds = (crew ?? []).map((c) => c?.personnelId).filter((id): id is string => !!id)
  // Відповідальний — із бригади і лише з входом у систему
  const leadOptions = options
    .filter((o) => crewIds.includes(o.value))
    .map((o) => ({ ...o, disabled: !withLogin.has(o.value), title: withLogin.has(o.value) ? undefined : t('shifts.leadNoLogin') }))
  const leadInCrew = !leadId || crewIds.includes(leadId)
  useEffect(() => {
    if (!leadInCrew) form.setFieldValue('leadId', undefined)
  }, [leadInCrew, form])

  const onFinish = (values: FormValues) => {
    if (!current) return
    update.mutate(
      {
        id: current.id,
        body: {
          version: current.version,
          crew: (values.crew ?? [])
            .filter((c) => c.personnelId)
            .map((c) => ({
              personnelId: c.personnelId!,
              lead: c.personnelId === values.leadId,
              minutes: c.hours == null ? undefined : Math.round(c.hours * 60),
            })),
        },
      },
      {
        onSuccess: () => message.success(t('common.saved')),
        onError: (error) => {
          if (!applyFieldErrors(form, error, t, leadRename(error))) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const statusTag = (typeCode: string, status: string, category: string) => {
    const def = types.find((tt) => tt.code === typeCode)?.statuses.find((s) => s.code === status)
    const color =
      def?.color && def.color !== 'default' ? def.color : CATEGORY_COLORS[category as keyof typeof CATEGORY_COLORS]
    return <Tag color={color}>{localized(def?.label, lang) || status}</Tag>
  }

  const fmt = (v: string) => dayjs(v).format('DD.MM HH:mm')
  const maxHours = (current?.durationMinutes ?? 1440) / 60

  return (
    <Drawer
      open={!!shiftId}
      onClose={onClose}
      size={560}
      destroyOnHidden
      title={current ? `${current.assetCode} · ${dayjs(current.date).format('DD.MM.YYYY')} · ${current.shiftName}` : ''}
      footer={
        canEdit &&
        current && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" loading={update.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        )
      }
    >
      {!current ? (
        <Spin />
      ) : (
        <>
          <Descriptions
            size="small"
            column={1}
            items={[
              { label: t('shifts.time'), children: `${fmt(current.startsAt)} – ${fmt(current.endsAt)} (${current.durationMinutes / 60} ${t('projects.hoursShort')})` },
              { label: t('projects.statusLabel'), children: <Tag>{t(`shifts.states.${current.state}`)}</Tag> },
              { label: t('shifts.source'), children: t(`shifts.sources.${current.source}`) },
            ]}
          />

          <Flex gap={8} style={{ marginTop: 12 }}>
            <Button onClick={() => setChecklistOpen(true)}>{t('checklists.open')}</Button>
            <Button type="primary" ghost onClick={() => setReportOpen(true)}>
              {t('reports.open')}
            </Button>
          </Flex>

          <Divider titlePlacement="start">{t('shifts.tasks')}</Divider>
          {current.tasks.length === 0 ? (
            <Typography.Text type="secondary">{t('shifts.noTasks')}</Typography.Text>
          ) : (
            <List
              size="small"
              dataSource={current.tasks}
              renderItem={(task) => (
                <List.Item>
                  <Typography.Link onClick={() => onOpenTask(task.id)}>
                    #{task.number} {task.title}
                  </Typography.Link>
                  {statusTag(task.typeCode, task.status, task.statusCategory)}
                </List.Item>
              )}
            />
          )}

          <Divider titlePlacement="start">{t('shifts.crew')}</Divider>
          <Typography.Paragraph type="secondary">{t('shifts.crewHint')}</Typography.Paragraph>
          <Form<FormValues> form={form} onFinish={onFinish} disabled={!canEdit}>
            <Form.List name="crew">
              {(fields, { add, remove }, { errors }) => (
                <>
                  {fields.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('shifts.noCrew')} />}
                  {fields.map((field) => (
                    <Flex key={field.key} gap={8} align="start">
                      <Form.Item
                        name={[field.name, 'personnelId']}
                        style={{ flex: 1, marginBottom: 8 }}
                        rules={[{ required: true, message: t('validation.required') }]}
                      >
                        <Select
                          showSearch={{ optionFilterProp: 'label' }}
                          placeholder={t('shifts.person')}
                          options={options.map((o) => ({
                            ...o,
                            disabled: (crew ?? []).some((c, i) => i !== field.name && c?.personnelId === o.value),
                          }))}
                        />
                      </Form.Item>
                      <Form.Item name={[field.name, 'hours']} style={{ marginBottom: 8 }}>
                        <InputNumber min={0} max={maxHours} step={0.5} suffix={t('projects.hoursShort')} style={{ width: 110 }} />
                      </Form.Item>
                      <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                    </Flex>
                  ))}
                  <Form.ErrorList errors={errors} />
                  {canEdit && (
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ hours: maxHours })}>
                      {t('shifts.addPerson')}
                    </Button>
                  )}
                </>
              )}
            </Form.List>
            <Form.Item
              name="leadId"
              label={t('shifts.lead')}
              extra={t('shifts.leadHint')}
              layout="vertical"
              style={{ marginTop: 16 }}
              rules={[{ required: crewIds.length > 0, message: t('validation.required') }]}
            >
              <Select allowClear placeholder={t('shifts.leadPlaceholder')} options={leadOptions} />
            </Form.Item>
          </Form>
        </>
      )}
      <ShiftChecklistDrawer
        shiftId={checklistOpen ? shiftId : undefined}
        title={current ? `${current.assetCode} · ${dayjs(current.date).format('DD.MM.YYYY')} · ${current.shiftName}` : undefined}
        onClose={() => setChecklistOpen(false)}
        onOpenTask={onOpenTask}
      />
      <ShiftReportDrawer
        project={project}
        shiftId={reportOpen ? shiftId : undefined}
        title={current ? `${current.assetCode} · ${dayjs(current.date).format('DD.MM.YYYY')} · ${current.shiftName}` : undefined}
        onClose={() => setReportOpen(false)}
      />
    </Drawer>
  )
}
