import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Spin,
  Statistic,
  Tag,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import timezone from 'dayjs/plugin/timezone'
import utc from 'dayjs/plugin/utc'
import { useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import type { Project } from '../../api/projects'
import { useActivityCodes, useConsumableItems } from '../../api/reportCatalogs'
import { useProjectServices, useServiceTypes } from '../../api/services'
import { type ReportIssue, type ShiftReport, useSaveShiftReport, useShiftReport, useShiftReportAction } from '../../api/shiftReports'
import { localized } from '../../i18n/localized'
import { ReportHistory } from '../review/ReportHistory'
import { REPORT_STATUS_COLORS } from '../review/reportStatus'
import { intervalText, type TimeRow, timeRowsFromReport, timeRowsToRequest } from './timeLog'
import { TimeLogFields } from './TimeLogFields'

dayjs.extend(utc)
dayjs.extend(timezone)

interface QuantityRow {
  serviceId?: string
  quantity?: number
  comment?: string
}

interface ConsumableRow {
  itemId?: string
  quantity?: number
  comment?: string
}

interface MeterRow {
  assetId: string
  assetCode: string
  startValue?: number
  endValue?: number
}

interface FormValues {
  times: TimeRow[]
  quantities: QuantityRow[]
  consumables: ConsumableRow[]
  meters: MeterRow[]
  notes?: string
}

interface Props {
  project: Project
  shiftId?: string
  title?: string
  onClose: () => void
}

/** Хвилини доби (за часовим поясом проєкту) → хвилини від початку зміни. */
function clockToOffset(value: Dayjs, startClock: number, duration: number, isEnd: boolean) {
  const minute = value.hour() * 60 + value.minute()
  const offset = (minute - startClock + 1440) % 1440
  return isEnd && offset === 0 ? Math.min(duration, 1440) : offset
}

/** Змінний рапорт: журнал часу з інтервалами буріння, обсяги, витрати, лічильники, підсумки й перевірки. */
export function ShiftReportDrawer({ project, shiftId, title, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()
  const report = useShiftReport(shiftId)
  const codes = useActivityCodes(false, !!shiftId)
  const items = useConsumableItems(false, !!shiftId)
  const services = useProjectServices(project.id)
  const serviceTypes = useServiceTypes(true)
  const save = useSaveShiftReport()
  const action = useShiftReportAction()
  const data = report.data
  const editable = !!data?.canEdit

  const start = data ? dayjs(data.startsAt).tz(project.timeZone) : undefined
  const startClock = start ? start.hour() * 60 + start.minute() : 0
  const offsetToClock = (offset: number) => (start ? start.add(offset, 'minute') : dayjs())
  const clock = (offset: number) => offsetToClock(offset).format('HH:mm')

  useEffect(() => {
    if (!data) return
    form.resetFields()
    form.setFieldsValue(toForm(data))
    // toForm залежить лише від data і часу початку
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, form])

  function toForm(r: ShiftReport): FormValues {
    const isNew = r.status === 'new'
    return {
      notes: r.notes ?? undefined,
      times: isNew ? [{ from: offsetToClock(0) }] : timeRowsFromReport(r.times, offsetToClock),
      quantities: r.quantities.map((x) => ({ serviceId: x.serviceId, quantity: x.quantity, comment: x.comment ?? undefined })),
      consumables: r.consumables.map((x) => ({ itemId: x.itemId, quantity: x.quantity, comment: x.comment ?? undefined })),
      meters: r.meters.map((m) => ({
        assetId: m.assetId,
        assetCode: m.assetCode,
        startValue: m.startValue ?? undefined,
        endValue: m.endValue ?? undefined,
      })),
    }
  }

  const unitByType = useMemo(
    () => new Map((serviceTypes.data ?? []).map((st) => [st.id, st.unit ?? undefined])),
    [serviceTypes.data],
  )
  const openServices = (services.data ?? []).filter((s) => s.status === 'planned' || s.status === 'in_progress')
  const quantityServices = openServices.filter((s) => unitByType.get(s.typeId) !== 'm')
  const serviceOptions = (list: typeof openServices, current: string[]) => {
    const ids = new Set(list.map((s) => s.id))
    const extra = (services.data ?? []).filter((s) => current.includes(s.id) && !ids.has(s.id))
    return [...list, ...extra].map((s) => ({ value: s.id, label: s.code }))
  }
  const values = Form.useWatch([], form) as FormValues | undefined
  const usedServices = (values?.quantities ?? []).map((r) => r?.serviceId ?? '')
  const drillingCodes = new Set((codes.data ?? []).filter((c) => c.drilling).map((c) => c.id))
  const legacyIntervals = (data?.intervals ?? []).filter((x) => !x.linked)
  const itemById = new Map((items.data ?? []).map((i) => [i.id, i]))

  const toBody = (v: FormValues) => ({
    notes: v.notes,
    version: data?.version ?? 0,
    times: timeRowsToRequest(v.times, drillingCodes, (value, isEnd) =>
      clockToOffset(value, startClock, data!.durationMinutes, isEnd),
    ),
    quantities: (v.quantities ?? [])
      .filter((x) => x.serviceId && x.quantity != null)
      .map((x) => ({ serviceId: x.serviceId!, quantity: x.quantity!, comment: x.comment })),
    consumables: (v.consumables ?? [])
      .filter((x) => x.itemId && x.quantity != null)
      .map((x) => ({ itemId: x.itemId!, quantity: x.quantity!, comment: x.comment })),
    meters: (v.meters ?? [])
      .filter((m) => m.startValue != null && m.endValue != null)
      .map((m) => ({ assetId: m.assetId, startValue: m.startValue!, endValue: m.endValue! })),
  })

  const onSave = (v: FormValues) => {
    if (!shiftId) return
    save.mutate(
      { shiftId, body: toBody(v) },
      {
        onSuccess: () => message.success(t('common.saved')),
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const run = (kind: 'submit' | 'reopen' | 'agree') =>
    shiftId &&
    data &&
    action.mutate(
      { shiftId, action: kind, version: data.version },
      {
        onSuccess: () =>
          message.success(t(kind === 'submit' ? 'reports.submitted' : kind === 'agree' ? 'review.agreed' : 'reports.reopened')),
        onError: (e) => message.error(errorMessage(e, t)),
      },
    )

  const issueText = (issue: ReportIssue) => {
    const p = { ...issue.params }
    if (issue.code === 'time_gap' || issue.code === 'time_overlap' || issue.code === 'drilling_without_interval') {
      p.from = clock(Number(p.from))
      p.to = clock(Number(p.to))
    }
    if (issue.code === 'time_total') {
      p.expected = String(Number(p.expected) / 60)
      p.actual = String(Math.round((Number(p.actual) / 60) * 100) / 100)
    }
    return t(`reports.issues.${issue.code}`, p)
  }

  const hours = (minutes: number) => `${Math.round((minutes / 60) * 100) / 100} ${t('projects.hoursShort')}`
  const required = [{ required: true, message: t('validation.required') }]

  return (
    <Drawer
      open={!!shiftId}
      onClose={onClose}
      size={1100}
      destroyOnHidden
      title={
        <Flex gap={8} align="center">
          {t('reports.title')}
          {title && <Typography.Text type="secondary">{title}</Typography.Text>}
          {data && <Tag color={REPORT_STATUS_COLORS[data.status]}>{t(`reports.status.${data.status}`)}</Tag>}
        </Flex>
      }
      footer={
        data && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            {data.canAgree && (
              <Popconfirm title={t('review.agreeConfirm')} onConfirm={() => run('agree')}>
                <Button type="primary" ghost loading={action.isPending}>
                  {t('review.agree')}
                </Button>
              </Popconfirm>
            )}
            {data.canReopen && (
              <Popconfirm title={t('reports.reopenConfirm')} onConfirm={() => run('reopen')}>
                <Button loading={action.isPending}>{t('reports.reopen')}</Button>
              </Popconfirm>
            )}
            {editable && (
              <>
                <Button loading={save.isPending} onClick={() => form.submit()}>
                  {t('reports.saveDraft')}
                </Button>
                <Popconfirm
                  title={t('reports.submitConfirm')}
                  disabled={data.status === 'new' || data.errors.length > 0}
                  onConfirm={() => run('submit')}
                >
                  <Button
                    type="primary"
                    disabled={data.status === 'new' || data.errors.length > 0}
                    loading={action.isPending}
                    title={data.status === 'new' ? t('reports.saveFirst') : undefined}
                  >
                    {t('reports.submit')}
                  </Button>
                </Popconfirm>
              </>
            )}
          </Flex>
        )
      }
    >
      {!data ? (
        <Spin />
      ) : (
        <Form<FormValues> form={form} onFinish={onSave} disabled={!editable} layout="vertical" requiredMark={false}>
          <ReportHistory report={data} clock={clock} />
          <Descriptions
            size="small"
            column={3}
            items={[
              { label: t('reports.shiftTime'), children: `${clock(0)} – ${clock(data.durationMinutes)} (${hours(data.durationMinutes)})` },
              { label: t('reports.billable'), children: hours(data.totals.billableMinutes) },
              { label: t('reports.nonBillable'), children: hours(data.totals.nonBillableMinutes) },
              ...(data.submittedAt
                ? [{ label: t('reports.submittedBy'), children: `${data.submittedByName ?? ''} · ${dayjs(data.submittedAt).format('DD.MM HH:mm')}` }]
                : []),
            ]}
          />
          {data.status !== 'new' && data.errors.length > 0 && (
            <Alert
              type="error"
              showIcon
              style={{ marginTop: 12 }}
              title={t('reports.errorsTitle')}
              description={
                <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                  {data.errors.map((e, i) => (
                    <li key={i}>{issueText(e)}</li>
                  ))}
                </ul>
              }
            />
          )}
          {data.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 12 }}
              title={t('reports.warningsTitle')}
              description={
                <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                  {data.warnings.map((e, i) => (
                    <li key={i}>{issueText(e)}</li>
                  ))}
                </ul>
              }
            />
          )}

          <Divider titlePlacement="start">{t('reports.timeLog')}</Divider>
          <TimeLogFields
            form={form}
            codes={codes.data ?? []}
            taskOptions={data.taskOptions}
            prefill={data.prefill}
            editable={editable}
            firstFrom={() => offsetToClock(0)}
          />
          {legacyIntervals.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 12 }}
              title={t('reports.legacyIntervalsTitle')}
              description={
                <>
                  {editable && <Typography.Paragraph>{t('reports.legacyIntervalsHint')}</Typography.Paragraph>}
                  <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                    {legacyIntervals.map((x, i) => (
                      <li key={i}>
                        {x.serviceCode}: {intervalText(x, t)}
                      </li>
                    ))}
                  </ul>
                </>
              }
            />
          )}
          {(data.totals.meters > 0 || drillingCodes.size > 0) && (
            <Flex gap={32} style={{ marginTop: 12 }}>
              <Statistic title={t('reports.meters')} value={data.totals.meters} suffix="m" />
              <Statistic title={t('reports.core')} value={data.totals.coreLength} suffix="m" />
              <Statistic title={t('reports.recovery')} value={data.totals.recovery ?? '—'} suffix={data.totals.recovery != null ? '%' : undefined} />
            </Flex>
          )}

          {(quantityServices.length > 0 || data.quantities.length > 0) && (
            <>
              <Divider titlePlacement="start">{t('reports.quantities')}</Divider>
              <Form.List name="quantities">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map((field) => {
                      const service = (services.data ?? []).find((s) => s.id === values?.quantities?.[field.name]?.serviceId)
                      const unit = service ? unitByType.get(service.typeId) : undefined
                      return (
                        <Flex key={field.key} gap={8} align="start">
                          <Form.Item name={[field.name, 'serviceId']} rules={required} style={{ marginBottom: 8, width: 200 }}>
                            <Select placeholder={t('reports.service')} options={serviceOptions(quantityServices, usedServices)} />
                          </Form.Item>
                          <Form.Item name={[field.name, 'quantity']} rules={required} style={{ marginBottom: 8 }}>
                            <InputNumber min={0} step={0.1} decimalSeparator="," suffix={unit ? t(`serviceTypes.units.${unit}`) : undefined} style={{ width: 160 }} />
                          </Form.Item>
                          <Form.Item name={[field.name, 'comment']} style={{ marginBottom: 8, flex: 1 }}>
                            <Input placeholder={t('reports.comment')} />
                          </Form.Item>
                          {editable && <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />}
                        </Flex>
                      )
                    })}
                    {editable && (
                      <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({})}>
                        {t('reports.addQuantity')}
                      </Button>
                    )}
                  </>
                )}
              </Form.List>
            </>
          )}

          <Divider titlePlacement="start">{t('reports.consumables')}</Divider>
          <Form.List name="consumables">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => {
                  const item = itemById.get(values?.consumables?.[field.name]?.itemId ?? '')
                  return (
                    <Flex key={field.key} gap={8} align="start">
                      <Form.Item name={[field.name, 'itemId']} rules={required} style={{ marginBottom: 8, width: 280 }}>
                        <Select
                          showSearch={{ optionFilterProp: 'label' }}
                          placeholder={t('reports.item')}
                          options={(items.data ?? []).map((i) => ({ value: i.id, label: localized(i.name, lang) }))}
                        />
                      </Form.Item>
                      <Form.Item name={[field.name, 'quantity']} rules={required} style={{ marginBottom: 8 }}>
                        <InputNumber min={0} step={1} decimalSeparator="," suffix={item?.unit} style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'comment']} style={{ marginBottom: 8, flex: 1 }}>
                        <Input placeholder={t('reports.comment')} />
                      </Form.Item>
                      {editable && <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />}
                    </Flex>
                  )
                })}
                {editable && (
                  <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({})}>
                    {t('reports.addConsumable')}
                  </Button>
                )}
              </>
            )}
          </Form.List>

          {data.meters.length > 0 && (
            <>
              <Divider titlePlacement="start">{t('reports.meterReadings')}</Divider>
              <Form.List name="meters">
                {(fields) =>
                  fields.map((field) => (
                    <Flex key={field.key} gap={8} align="start">
                      <Typography.Text strong style={{ width: 140, lineHeight: '32px' }}>
                        {values?.meters?.[field.name]?.assetCode}
                      </Typography.Text>
                      <Form.Item name={[field.name, 'startValue']} style={{ marginBottom: 8 }}>
                        <InputNumber min={0} step={0.1} decimalSeparator="," placeholder={t('reports.meterStart')} suffix={t('projects.hoursShort')} style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'endValue']} style={{ marginBottom: 8 }}>
                        <InputNumber min={0} step={0.1} decimalSeparator="," placeholder={t('reports.meterEnd')} suffix={t('projects.hoursShort')} style={{ width: 160 }} />
                      </Form.Item>
                    </Flex>
                  ))
                }
              </Form.List>
            </>
          )}

          <Divider titlePlacement="start">{t('reports.notes')}</Divider>
          <Form.Item name="notes">
            <Input.TextArea rows={3} />
          </Form.Item>

          {data.totals.byCode.length > 0 && (
            <>
              <Divider titlePlacement="start">{t('reports.byCode')}</Divider>
              <Flex gap={8} wrap>
                {data.totals.byCode.map((c) => (
                  <Tag key={c.activityCode} color={c.billable ? 'green' : undefined}>
                    {c.activityCode} · {localized(c.name, lang)}: {hours(c.minutes)}
                  </Tag>
                ))}
              </Flex>
            </>
          )}
        </Form>
      )}
    </Drawer>
  )
}
