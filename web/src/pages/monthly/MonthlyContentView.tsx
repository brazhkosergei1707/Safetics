import { DeleteOutlined } from '@ant-design/icons'
import { Button, Divider, Popconfirm, Statistic, Flex, Switch, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import type { MonthlyContent } from '../../api/monthly'
import { localized } from '../../i18n/localized'
import { REPORT_STATUS_COLORS } from '../review/reportStatus'

interface Props {
  content: MonthlyContent
  /** Включити неперевірений рапорт в основну частину (лише підрядник, місяць відкрито) */
  onInclude?: (shiftId: string, included: boolean) => void
  onDeleteAdjustment?: (id: string) => void
  onOpenShift?: (shiftId: string) => void
}

const hours = (minutes: number) => Math.round((minutes / 60) * 100) / 100

/** Зміст місячного звіту: разом, по бурових (метри, години, обсяги), неперевірені рапорти, коригування. */
export function MonthlyContentView({ content, onInclude, onDeleteAdjustment, onOpenShift }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const h = t('projects.hoursShort')

  return (
    <>
      <Flex gap={32} wrap style={{ marginBottom: 16 }}>
        <Statistic title={t('monthly.totalMeters')} value={content.totals.meters} suffix="m" />
        <Statistic title={t('monthly.totalBillable')} value={hours(content.totals.billableMinutes)} suffix={h} />
        {content.totals.quantities.map((q) => (
          <Statistic key={q.unit ?? '-'} title={t('monthly.totalQuantity')} value={q.quantity} suffix={q.unit ?? ''} />
        ))}
        <Statistic title={t('monthly.acceptedReports')} value={content.acceptedCount} />
        <Statistic title={t('monthly.draftReports')} value={content.draftCount} />
      </Flex>
      {(content.totals.adjustmentMeters !== 0 || content.totals.adjustmentHours !== 0) && (
        <Typography.Paragraph type="secondary">
          {t('monthly.adjustmentsIncluded', { meters: content.totals.adjustmentMeters, hours: content.totals.adjustmentHours })}
        </Typography.Paragraph>
      )}

      <Divider titlePlacement="start">{t('monthly.byRig')}</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="assetId"
        dataSource={content.assets}
        locale={{ emptyText: t('monthly.noAccepted') }}
        expandable={{
          expandedRowRender: (asset) => (
            <Flex gap={24} wrap align="start">
              <Table
                size="small"
                pagination={false}
                rowKey={(r) => `${r.serviceCode}-${r.coreSize}`}
                dataSource={asset.metersByHole}
                columns={[
                  { title: t('reports.hole'), dataIndex: 'serviceCode' },
                  { title: t('reports.coreSize'), dataIndex: 'coreSize' },
                  { title: t('reports.meters'), dataIndex: 'meters', align: 'right' },
                  { title: t('reports.coreLength'), dataIndex: 'coreLength', align: 'right' },
                ]}
              />
              <Table
                size="small"
                pagination={false}
                rowKey="code"
                dataSource={asset.hours}
                columns={[
                  { title: t('reports.activityCode'), render: (_, r) => `${r.code} · ${localized(r.name, lang)}` },
                  { title: t('reports.billable'), render: (_, r) => `${hours(r.minutes)} ${h}`, align: 'right' },
                ]}
              />
              {asset.quantities.length > 0 && (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="serviceCode"
                  dataSource={asset.quantities}
                  columns={[
                    { title: t('reports.hole'), dataIndex: 'serviceCode' },
                    { title: t('reports.quantities'), render: (_, r) => `${r.quantity} ${r.unit ?? ''}`, align: 'right' },
                  ]}
                />
              )}
            </Flex>
          ),
        }}
        columns={[
          { title: t('review.rig'), dataIndex: 'assetCode' },
          { title: t('monthly.reports'), dataIndex: 'reports', align: 'right' },
          { title: t('reports.meters'), render: (_, r) => `${r.meters} m`, align: 'right' },
          { title: t('reports.billable'), render: (_, r) => `${hours(r.billableMinutes)} ${h}`, align: 'right' },
        ]}
      />

      <Divider titlePlacement="start">{t('monthly.unreviewed')}</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="shiftId"
        dataSource={content.unreviewed}
        locale={{ emptyText: t('monthly.noUnreviewed') }}
        onRow={(r) => (onOpenShift ? { onClick: () => onOpenShift(r.shiftId), style: { cursor: 'pointer' } } : {})}
        columns={[
          { title: t('review.date'), render: (_, r) => dayjs(r.date).format('DD.MM.YYYY') },
          { title: t('review.rig'), dataIndex: 'assetCode' },
          { title: t('review.shift'), dataIndex: 'shiftName' },
          { title: t('projects.statusLabel'), render: (_, r) => <Tag color={REPORT_STATUS_COLORS[r.status]}>{t(`reports.status.${r.status}`)}</Tag> },
          { title: t('reports.meters'), render: (_, r) => `${r.meters} m`, align: 'right' },
          { title: t('reports.billable'), render: (_, r) => `${hours(r.billableMinutes)} ${h}`, align: 'right' },
          {
            title: t('monthly.included'),
            render: (_, r) =>
              onInclude ? (
                <Switch
                  size="small"
                  checked={r.included}
                  onClick={(checked, e) => {
                    e.stopPropagation()
                    onInclude(r.shiftId, checked)
                  }}
                />
              ) : r.included ? (
                <Tag color="green">{t('monthly.includedTag')}</Tag>
              ) : (
                <Tag>{t('monthly.notIncludedTag')}</Tag>
              ),
          },
        ]}
      />

      <Divider titlePlacement="start">{t('monthly.adjustments')}</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="id"
        dataSource={content.adjustments}
        locale={{ emptyText: t('monthly.noAdjustments') }}
        columns={[
          { title: t('monthly.kind'), render: (_, r) => t(`monthly.kinds.${r.kind}`) },
          {
            title: t('monthly.amount'),
            render: (_, r) => `${r.amount > 0 ? '+' : ''}${r.amount} ${r.kind === 'meters' ? 'm' : r.kind === 'hours' ? h : (r.unit ?? '')}`,
            align: 'right',
          },
          { title: t('review.rig'), dataIndex: 'assetCode' },
          {
            title: t('review.comment'),
            render: (_, r) => (
              <Flex vertical>
                <span>{r.comment}</span>
                {r.correctsLabel && <Typography.Text type="secondary">{t('monthly.corrects', { label: r.correctsLabel })}</Typography.Text>}
              </Flex>
            ),
          },
          { title: t('monthly.author'), render: (_, r) => `${r.createdByName ?? ''} · ${dayjs(r.createdAt).format('DD.MM HH:mm')}` },
          ...(onDeleteAdjustment
            ? [
                {
                  key: 'delete',
                  width: 48,
                  render: (_: unknown, r: { id: string }) => (
                    <Popconfirm title={t('monthly.deleteAdjustment')} onConfirm={() => onDeleteAdjustment(r.id)}>
                      <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                  ),
                },
              ]
            : []),
        ]}
      />
    </>
  )
}
