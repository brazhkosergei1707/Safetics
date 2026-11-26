import { DeleteOutlined } from '@ant-design/icons'
import {
  App,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import timezone from 'dayjs/plugin/timezone'
import utc from 'dayjs/plugin/utc'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { useProject } from '../../api/projects'
import { useActivityCodes } from '../../api/reportCatalogs'
import { type ReviewReport, useReviewAction, useReviewReport } from '../../api/reportReview'
import { useProjectServices } from '../../api/services'
import { localized } from '../../i18n/localized'
import { intervalText, type TimeRow, timeRowsFromReport, timeRowsToRequest } from '../shifts/timeLog'
import { TimeLogFields } from '../shifts/TimeLogFields'
import { ReportHistory } from './ReportHistory'
import { REPORT_STATUS_COLORS } from './reportStatus'

dayjs.extend(utc)
dayjs.extend(timezone)

interface QuantityRow {
  serviceId?: string
  quantity?: number
  comment?: string
}

interface CorrectionValues {
  comment: string
  times: TimeRow[]
  quantities: QuantityRow[]
}

/**
 * Рапорт для замовника (без витрат і лічильників): прийняти, повернути з коментарем або виправити
 * журнал часу, інтервали й обсяги (правку погоджує підрядник).
 */
export function ReviewDrawer({ shiftId, onClose }: { shiftId?: string; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const review = useReviewReport(shiftId)
  const data = review.data
  const project = useProject(data?.projectId)
  const action = useReviewAction()
  const [returning, setReturning] = useState(false)
  const [correcting, setCorrecting] = useState(false)

  const timeZone = project.data?.timeZone ?? 'UTC'
  const start = data ? dayjs(data.report.startsAt).tz(timeZone) : undefined
  const clock = (offset: number) => (start ? start.add(offset, 'minute').format('HH:mm') : '')
  const hours = (minutes: number) => `${Math.round((minutes / 60) * 100) / 100} ${t('projects.hoursShort')}`

  const done = (key: string) => () => {
    message.success(t(key))
    setReturning(false)
    setCorrecting(false)
  }
  const onError = (e: unknown) => message.error(errorMessage(e, t))

  const title = data ? `${data.assetCode} · ${dayjs(data.report.date).format('DD.MM.YYYY')} · ${data.shiftName}` : ''

  return (
    <Drawer
      open={!!shiftId}
      onClose={onClose}
      size={1000}
      destroyOnHidden
      title={
        <Flex gap={8} align="center">
          {t('reports.title')}
          <Typography.Text type="secondary">{title}</Typography.Text>
          {data && <Tag color={REPORT_STATUS_COLORS[data.report.status]}>{t(`reports.status.${data.report.status}`)}</Tag>}
        </Flex>
      }
      footer={
        data?.canReview && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button danger onClick={() => setReturning(true)}>
              {t('review.return')}
            </Button>
            <Button onClick={() => setCorrecting(true)}>{t('review.correct')}</Button>
            <Popconfirm
              title={t('review.acceptConfirm')}
              onConfirm={() =>
                action.mutate(
                  { shiftId: data.report.shiftId, action: { kind: 'accept', version: data.report.version } },
                  { onSuccess: done('review.acceptedMessage'), onError },
                )
              }
            >
              <Button type="primary" loading={action.isPending}>
                {t('review.accept')}
              </Button>
            </Popconfirm>
          </Flex>
        )
      }
    >
      {!data ? (
        <Spin />
      ) : (
        <>
          <ReportHistory report={data.report} clock={clock} />
          <Descriptions
            size="small"
            column={3}
            items={[
              { label: t('review.project'), children: `${data.projectCode} · ${data.projectName}` },
              {
                label: t('reports.shiftTime'),
                children: `${clock(0)} – ${clock(data.report.durationMinutes)} (${hours(data.report.durationMinutes)})`,
              },
              ...(data.report.submittedAt
                ? [
                    {
                      label: t('reports.submittedBy'),
                      children: `${data.report.submittedByName ?? ''} · ${dayjs(data.report.submittedAt).format('DD.MM HH:mm')}`,
                    },
                  ]
                : []),
              { label: t('reports.billable'), children: hours(data.report.totals.billableMinutes) },
              { label: t('reports.nonBillable'), children: hours(data.report.totals.nonBillableMinutes) },
              { label: t('reports.meters'), children: `${data.report.totals.meters} m` },
            ]}
          />

          <Divider titlePlacement="start">{t('reports.timeLog')}</Divider>
          <Table
            size="small"
            pagination={false}
            rowKey={(r) => `${r.startMinute}-${r.activityCodeId}`}
            dataSource={data.report.times}
            columns={[
              { title: t('reports.from'), render: (_, r) => clock(r.startMinute), width: 80 },
              { title: t('reports.to'), render: (_, r) => clock(r.endMinute), width: 80 },
              {
                title: t('reports.activityCode'),
                render: (_, r) => (
                  <Flex gap={6} align="center">
                    {r.activityCode} · {localized(r.activityName, lang)}
                    {r.billable && <Tag color="green">{t('review.billableTag')}</Tag>}
                  </Flex>
                ),
              },
              { title: t('reports.task'), render: (_, r) => (r.taskNumber ? `#${r.taskNumber} ${r.taskTitle ?? ''}` : '') },
              {
                title: t('reports.interval'),
                render: (_, r) => (r.interval ? `${r.interval.serviceCode}: ${intervalText(r.interval, t)}` : ''),
              },
              { title: t('reports.comment'), dataIndex: 'comment' },
            ]}
          />

          {data.report.intervals.some((x) => !x.linked) && (
            <>
              <Divider titlePlacement="start">{t('reports.legacyIntervalsTitle')}</Divider>
              <Table
                size="small"
                pagination={false}
                rowKey={(r) => `${r.serviceId}-${r.depthFrom}`}
                dataSource={data.report.intervals.filter((x) => !x.linked)}
                columns={[
                  { title: t('reports.hole'), dataIndex: 'serviceCode' },
                  { title: t('reports.depthFrom'), dataIndex: 'depthFrom' },
                  { title: t('reports.depthTo'), dataIndex: 'depthTo' },
                  { title: t('reports.meters'), dataIndex: 'meters' },
                  { title: t('reports.coreSize'), dataIndex: 'coreSize' },
                  { title: t('reports.coreLength'), dataIndex: 'coreLength' },
                  { title: t('reports.recovery'), render: (_, r) => (r.recovery != null ? `${r.recovery}%` : '') },
                ]}
              />
            </>
          )}

          {data.report.quantities.length > 0 && (
            <>
              <Divider titlePlacement="start">{t('reports.quantities')}</Divider>
              <Flex gap={8} wrap>
                {data.report.quantities.map((q) => (
                  <Tag key={q.serviceId}>
                    {q.serviceCode}: {q.quantity} {q.unit ? t(`serviceTypes.units.${q.unit}`) : ''}
                  </Tag>
                ))}
              </Flex>
            </>
          )}

          <Divider titlePlacement="start">{t('shifts.crew')}</Divider>
          <Flex gap={8} wrap>
            {data.crew.map((c) => (
              <Tag key={c.personnelId} color={c.lead ? 'orange' : undefined}>
                {c.fullName} · {localized(c.positionName, lang)} · {hours(c.minutes)}
              </Tag>
            ))}
          </Flex>
          {data.report.notes && (
            <>
              <Divider titlePlacement="start">{t('reports.notes')}</Divider>
              <Typography.Paragraph>{data.report.notes}</Typography.Paragraph>
            </>
          )}
        </>
      )}

      <ReturnModal
        open={returning}
        loading={action.isPending}
        onCancel={() => setReturning(false)}
        onOk={(comment) =>
          data &&
          action.mutate(
            { shiftId: data.report.shiftId, action: { kind: 'return', version: data.report.version, comment } },
            { onSuccess: done('review.returnedMessage'), onError },
          )
        }
      />
      {data && correcting && (
        <CorrectionModal
          data={data}
          startsAt={start!}
          onCancel={() => setCorrecting(false)}
          onDone={done('review.correctedMessage')}
        />
      )}
    </Drawer>
  )
}

function ReturnModal({ open, loading, onCancel, onOk }: {
  open: boolean
  loading: boolean
  onCancel: () => void
  onOk: (comment: string) => void
}) {
  const { t } = useTranslation()
  const [form] = Form.useForm<{ comment: string }>()
  return (
    <Modal
      open={open}
      title={t('review.return')}
      onCancel={onCancel}
      onOk={() => form.submit()}
      okText={t('review.return')}
      okButtonProps={{ danger: true }}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={(v) => onOk(v.comment)} preserve={false}>
        <Form.Item
          name="comment"
          label={t('review.comment')}
          extra={t('review.returnHint')}
          rules={[{ required: true, whitespace: true, message: t('validation.required') }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

/** Правка замовника: журнал часу з інтервалами й обсяги; коментар обов'язковий. */
function CorrectionModal({ data, startsAt, onCancel, onDone }: {
  data: ReviewReport
  startsAt: Dayjs
  onCancel: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<CorrectionValues>()
  const action = useReviewAction()
  const codes = useActivityCodes(false)
  const services = useProjectServices(data.projectId)
  const report = data.report
  const startClock = startsAt.hour() * 60 + startsAt.minute()
  const toClock = (offset: number) => startsAt.add(offset, 'minute')
  const toOffset = (value: Dayjs, isEnd: boolean) => {
    const offset = (value.hour() * 60 + value.minute() - startClock + 1440) % 1440
    return isEnd && offset === 0 ? Math.min(report.durationMinutes, 1440) : offset
  }
  const required = [{ required: true, message: t('validation.required') }]
  const drillingCodes = new Set((codes.data ?? []).filter((c) => c.drilling).map((c) => c.id))
  const serviceOptions = (services.data ?? []).map((s) => ({ value: s.id, label: s.code }))

  const initial: CorrectionValues = {
    comment: '',
    times: timeRowsFromReport(report.times, toClock),
    quantities: report.quantities.map((x) => ({ serviceId: x.serviceId, quantity: x.quantity, comment: x.comment ?? undefined })),
  }

  const onFinish = (v: CorrectionValues) =>
    action.mutate(
      {
        shiftId: report.shiftId,
        action: {
          kind: 'correct',
          body: {
            version: report.version,
            comment: v.comment,
            times: timeRowsToRequest(v.times, drillingCodes, toOffset),
            quantities: (v.quantities ?? [])
              .filter((x) => x.serviceId && x.quantity != null)
              .map((x) => ({ serviceId: x.serviceId!, quantity: x.quantity!, comment: x.comment })),
          },
        },
      },
      {
        onSuccess: onDone,
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )

  return (
    <Modal
      open
      width={1000}
      title={t('review.correct')}
      onCancel={onCancel}
      onOk={() => form.submit()}
      okText={t('review.sendCorrection')}
      cancelText={t('common.cancel')}
      confirmLoading={action.isPending}
    >
      <Typography.Paragraph type="secondary">{t('review.correctHint')}</Typography.Paragraph>
      <Form<CorrectionValues> form={form} layout="vertical" initialValues={initial} onFinish={onFinish} requiredMark={false}>
        <Divider titlePlacement="start">{t('reports.timeLog')}</Divider>
        <TimeLogFields
          form={form}
          codes={codes.data ?? []}
          taskOptions={report.taskOptions}
          prefill={[]}
          editable
          client
          firstFrom={() => toClock(0)}
        />

        {report.quantities.length > 0 && (
          <>
            <Divider titlePlacement="start">{t('reports.quantities')}</Divider>
            <Form.List name="quantities">
              {(fields, { remove }) =>
                fields.map((field) => (
                  <Flex key={field.key} gap={8} align="start">
                    <Form.Item name={[field.name, 'serviceId']} rules={required} style={{ marginBottom: 8, width: 200 }}>
                      <Select options={serviceOptions} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'quantity']} rules={required} style={{ marginBottom: 8 }}>
                      <InputNumber min={0} step={0.1} decimalSeparator="," style={{ width: 160 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'comment']} style={{ marginBottom: 8, flex: 1 }}>
                      <Input placeholder={t('reports.comment')} />
                    </Form.Item>
                    <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                  </Flex>
                ))
              }
            </Form.List>
          </>
        )}

        <Form.Item
          name="comment"
          label={t('review.comment')}
          extra={t('review.correctCommentHint')}
          style={{ marginTop: 16 }}
          rules={[{ required: true, whitespace: true, message: t('validation.required') }]}
        >
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
