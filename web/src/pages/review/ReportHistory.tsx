import { Alert, Flex, Table, Tag, Timeline, Typography } from 'antd'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import type { ShiftReport } from '../../api/shiftReports'
import { localized } from '../../i18n/localized'

const EVENT_COLORS: Record<string, string> = {
  submitted: 'blue',
  returned: 'red',
  corrected: 'purple',
  agreed: 'green',
  accepted: 'green',
  reopened: 'gray',
}

interface Props {
  report: ShiftReport
  /** Хвилини від початку зміни → годинник (за часовим поясом проєкту) */
  clock: (offset: number) => string
}

/**
 * Перевірка замовником: правка замовника поруч із рапортом (журнал часу, інтервали, обсяги) і
 * журнал подій з коментарями.
 */
export function ReportHistory({ report, clock }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const hours = (minutes: number) => `${Math.round((minutes / 60) * 100) / 100} ${t('projects.hoursShort')}`
  const correction = report.correction
  const lastReturn = [...report.events].reverse().find((e) => e.kind === 'returned')

  return (
    <>
      {report.status === 'returned' && lastReturn && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          title={t('review.returnedBy', { name: lastReturn.authorName ?? '' })}
          description={lastReturn.comment}
        />
      )}
      {correction && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          title={t('review.correctedBy', { name: correction.authorName ?? '', at: dayjs(correction.createdAt).format('DD.MM HH:mm') })}
          description={
            <Flex vertical gap={8}>
              <Typography.Text>{correction.comment}</Typography.Text>
              <Flex gap={24} wrap>
                <Typography.Text>
                  {t('reports.billable')}: {hours(report.totals.billableMinutes)} → <b>{hours(correction.totals.billableMinutes)}</b>
                </Typography.Text>
                <Typography.Text>
                  {t('reports.meters')}: {report.totals.meters} → <b>{correction.totals.meters}</b> m
                </Typography.Text>
              </Flex>
              <Table
                size="small"
                pagination={false}
                rowKey={(r) => `${r.startMinute}-${r.activityCodeId}`}
                dataSource={correction.times}
                columns={[
                  { title: t('reports.from'), render: (_, r) => clock(r.startMinute), width: 80 },
                  { title: t('reports.to'), render: (_, r) => clock(r.endMinute), width: 80 },
                  { title: t('reports.activityCode'), render: (_, r) => `${r.activityCode} · ${localized(r.activityName, lang)}` },
                  { title: t('reports.comment'), dataIndex: 'comment' },
                ]}
              />
              {correction.intervals.length > 0 && (
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r) => `${r.serviceId}-${r.depthFrom}`}
                  dataSource={correction.intervals}
                  columns={[
                    { title: t('reports.hole'), dataIndex: 'serviceCode' },
                    { title: t('reports.depthFrom'), dataIndex: 'depthFrom' },
                    { title: t('reports.depthTo'), dataIndex: 'depthTo' },
                    { title: t('reports.coreSize'), dataIndex: 'coreSize' },
                    { title: t('reports.coreLength'), dataIndex: 'coreLength' },
                  ]}
                />
              )}
              {correction.quantities.length > 0 && (
                <Flex gap={8} wrap>
                  {correction.quantities.map((q) => (
                    <Tag key={q.serviceId}>
                      {q.serviceCode}: {q.quantity} {q.unit ?? ''}
                    </Tag>
                  ))}
                </Flex>
              )}
            </Flex>
          }
        />
      )}
      {report.events.length > 0 && (
        <Timeline
          style={{ marginTop: 8 }}
          items={report.events.map((e) => ({
            color: EVENT_COLORS[e.kind],
            content: (
              <Flex vertical>
                <Typography.Text>
                  {t(`review.events.${e.kind}`)} · {e.authorName ?? ''} · {dayjs(e.createdAt).format('DD.MM.YYYY HH:mm')}
                </Typography.Text>
                {e.comment && <Typography.Text type="secondary">{e.comment}</Typography.Text>}
              </Flex>
            ),
          }))}
        />
      )}
    </>
  )
}
