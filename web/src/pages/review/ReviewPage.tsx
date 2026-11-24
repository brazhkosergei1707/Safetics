import { DatePicker, Flex, Select, Table, Tag, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type ReviewListItem, useReviewReports } from '../../api/reportReview'
import { useProjects } from '../../api/projects'
import { REPORT_STATUS_COLORS } from './reportStatus'
import { ReviewDrawer } from './ReviewDrawer'

const DATE = 'YYYY-MM-DD'
const STATUSES = ['submitted', 'returned', 'corrected', 'accepted']

/** Портал замовника: подані змінні рапорти своїх проєктів — прийняти, повернути або виправити. */
export function ReviewPage() {
  const { t } = useTranslation()
  const projects = useProjects()
  const [projectId, setProjectId] = useState<string>()
  const [status, setStatus] = useState<string | undefined>('submitted')
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>([dayjs().subtract(30, 'day'), dayjs()])
  const [shiftId, setShiftId] = useState<string>()
  const list = useReviewReports({
    projectId,
    status,
    from: range?.[0].format(DATE),
    to: range?.[1].format(DATE),
  })
  const hours = (minutes: number) => `${Math.round((minutes / 60) * 100) / 100} ${t('projects.hoursShort')}`

  return (
    <>
      <Typography.Title level={2}>{t('review.title')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('review.hint')}</Typography.Paragraph>
      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder={t('review.allProjects')}
          style={{ width: 240 }}
          value={projectId}
          onChange={setProjectId}
          options={(projects.data ?? []).map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` }))}
        />
        <Select
          allowClear
          placeholder={t('review.allStatuses')}
          style={{ width: 200 }}
          value={status}
          onChange={setStatus}
          options={STATUSES.map((s) => ({ value: s, label: t(`reports.status.${s}`) }))}
        />
        <DatePicker.RangePicker format="DD.MM.YYYY" value={range} onChange={(v) => setRange(v as [Dayjs, Dayjs] | null)} />
      </Flex>
      <Table<ReviewListItem>
        rowKey="shiftId"
        size="middle"
        loading={list.isPending}
        dataSource={list.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        onRow={(r) => ({ onClick: () => setShiftId(r.shiftId), style: { cursor: 'pointer' } })}
        columns={[
          { title: t('review.date'), render: (_, r) => dayjs(r.date).format('DD.MM.YYYY'), width: 110 },
          { title: t('review.shift'), dataIndex: 'shiftName', width: 100 },
          { title: t('review.project'), dataIndex: 'projectCode' },
          { title: t('review.rig'), dataIndex: 'assetCode' },
          { title: t('reports.meters'), render: (_, r) => `${r.meters} m`, align: 'right' },
          { title: t('reports.billable'), render: (_, r) => hours(r.billableMinutes), align: 'right' },
          {
            title: t('projects.statusLabel'),
            render: (_, r) => <Tag color={REPORT_STATUS_COLORS[r.status]}>{t(`reports.status.${r.status}`)}</Tag>,
          },
        ]}
      />
      <ReviewDrawer shiftId={shiftId} onClose={() => setShiftId(undefined)} />
    </>
  )
}
