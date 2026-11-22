import { DownloadOutlined } from '@ant-design/icons'
import { Button, Card, DatePicker, Descriptions, Flex, InputNumber, Segmented, Select, Space, Switch, Table, Tag, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAssets, useAssetTypes } from '../../api/assets'
import { useProjects } from '../../api/projects'
import { type SummaryPeriod, type SummaryRow, useSummary } from '../../api/reports'
import { localized } from '../../i18n/localized'

const DATE = 'YYYY-MM-DD'
const CATEGORY_COLORS: Record<string, string> = {
  productive: 'green',
  contractor_npt: 'red',
  client_standby: 'orange',
  other: 'default',
}

/** Зведені звіти по обладнанню: місяць / півріччя / рік — години, метри за діапазонами глибини, обсяги, послуги, витрати. */
export function SummaryReportPage() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [projectId, setProjectId] = useState<string>()
  const [assetId, setAssetId] = useState<string>()
  const [period, setPeriod] = useState<SummaryPeriod>('month')
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(5, 'month').startOf('month'), dayjs()])
  const [depthStep, setDepthStep] = useState(100)
  const [includeDrafts, setIncludeDrafts] = useState(false)

  const projects = useProjects()
  const types = useAssetTypes()
  const assets = useAssets({})
  const primaryTypes = useMemo(
    () => new Set((types.data ?? []).filter((tt) => tt.category === 'primary').map((tt) => tt.id)),
    [types.data],
  )
  const summary = useSummary({
    projectId,
    assetId,
    from: range[0].format(DATE),
    to: range[1].format(DATE),
    period,
    depthStep,
    includeDrafts,
  })

  const hours = (minutes: number) => Math.round((minutes / 60) * 10) / 10
  const num = (value?: number | null, digits = 1) => (value == null ? '—' : Number(value).toFixed(digits))
  const periodLabel = (row: SummaryRow) => {
    if (!row.periodStart) return t('summary.total')
    const start = dayjs(row.periodStart)
    if (period === 'year') return start.format('YYYY')
    if (period === 'half_year') return `${start.format('YYYY')} · ${start.month() < 6 ? 'H1' : 'H2'}`
    return start.format('MM.YYYY')
  }

  const bandLabel = (from: number, to: number) => `${Number(from)}–${Number(to)} m`
  const allBands = useMemo(() => {
    const keys = new Map<number, number>()
    for (const r of summary.data?.total.depthBands ?? []) keys.set(Number(r.from), Number(r.to))
    return [...keys.entries()].sort((a, b) => a[0] - b[0])
  }, [summary.data])

  const exportCsv = () => {
    const data = summary.data
    if (!data) return
    const header = [
      t('summary.equipment'),
      t('summary.period'),
      t('summary.shifts'),
      t('summary.submitted'),
      t('summary.draft'),
      t('summary.missing'),
      t('summary.billable'),
      t('summary.nonBillable'),
      t('summary.meters'),
      t('summary.core'),
      t('summary.recovery'),
      ...allBands.map(([f, to]) => bandLabel(f, to)),
      t('summary.completed'),
    ]
    const line = (r: SummaryRow) => [
      r.assetCode ?? t('summary.total'),
      periodLabel(r),
      r.shifts,
      r.reportsSubmitted,
      r.reportsDraft,
      r.reportsMissing,
      hours(r.billableMinutes),
      hours(r.nonBillableMinutes),
      r.meters,
      r.coreLength,
      r.recovery ?? '',
      ...allBands.map(([f]) => r.depthBands.find((b) => Number(b.from) === f)?.meters ?? 0),
      r.completedServices,
    ]
    const rows = [header, ...data.rows.map(line), line(data.total)]
    const csv = rows
      .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(';'))
      .join('\r\n')
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `safetics-summary-${data.from}-${data.to}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  const details = (r: SummaryRow) => (
    <Descriptions
      size="small"
      column={1}
      items={[
        {
          label: t('summary.byCategory'),
          children: (
            <Flex gap={4} wrap>
              {r.categories.map((c) => (
                <Tag key={c.category} color={CATEGORY_COLORS[c.category]}>
                  {t(`reportCatalogs.categories.${c.category}`)}: {hours(c.minutes)} {t('projects.hoursShort')}
                </Tag>
              ))}
            </Flex>
          ),
        },
        {
          label: t('summary.byCode'),
          children: (
            <Flex gap={4} wrap>
              {r.byCode.map((c) => (
                <Tag key={c.code} color={c.billable ? 'green' : undefined}>
                  {c.code} · {localized(c.name, lang)}: {hours(c.minutes)} {t('projects.hoursShort')}
                </Tag>
              ))}
            </Flex>
          ),
        },
        {
          label: t('summary.depthBands', { step: depthStep }),
          children: r.depthBands.length
            ? r.depthBands.map((b) => `${bandLabel(b.from, b.to)}: ${num(b.meters)} m`).join(' · ')
            : '—',
        },
        {
          label: t('summary.quantities'),
          children: r.quantities.length
            ? r.quantities.map((q) => `${num(q.quantity, 2)} ${q.unit ? t(`serviceTypes.units.${q.unit}`) : ''}`).join(' · ')
            : '—',
        },
        {
          label: t('summary.consumables'),
          children: r.consumables.length
            ? r.consumables.map((c) => `${localized(c.name, lang)}: ${Number(c.quantity)} ${c.unit}`).join(' · ')
            : '—',
        },
      ]}
    />
  )

  const columns = [
    { title: t('summary.equipment'), key: 'asset', render: (_: unknown, r: SummaryRow) => <Typography.Text strong>{r.assetCode}</Typography.Text> },
    { title: t('summary.period'), key: 'period', render: (_: unknown, r: SummaryRow) => periodLabel(r) },
    {
      title: t('summary.shifts'),
      key: 'shifts',
      render: (_: unknown, r: SummaryRow) => (
        <Space size={4}>
          {r.shifts}
          <Tag color="orange">{r.reportsSubmitted}</Tag>
          {r.reportsDraft > 0 && <Tag color="blue">{r.reportsDraft}</Tag>}
          {r.reportsMissing > 0 && <Tag color="red">{r.reportsMissing}</Tag>}
        </Space>
      ),
    },
    { title: t('summary.billable'), key: 'billable', align: 'right' as const, render: (_: unknown, r: SummaryRow) => hours(r.billableMinutes) },
    { title: t('summary.nonBillable'), key: 'nonBillable', align: 'right' as const, render: (_: unknown, r: SummaryRow) => hours(r.nonBillableMinutes) },
    { title: t('summary.meters'), key: 'meters', align: 'right' as const, render: (_: unknown, r: SummaryRow) => num(r.meters) },
    { title: t('summary.recovery'), key: 'recovery', align: 'right' as const, render: (_: unknown, r: SummaryRow) => (r.recovery == null ? '—' : `${num(r.recovery)}%`) },
    ...allBands.map(([from, to]) => ({
      title: bandLabel(from, to),
      key: `band-${from}`,
      align: 'right' as const,
      render: (_: unknown, r: SummaryRow) => num(r.depthBands.find((b) => Number(b.from) === from)?.meters ?? 0),
    })),
    { title: t('summary.completed'), key: 'completed', align: 'right' as const, render: (_: unknown, r: SummaryRow) => r.completedServices },
  ]

  return (
    <Flex vertical gap={16}>
      <Typography.Title level={3} style={{ margin: 0 }}>
        {t('summary.title')}
      </Typography.Title>
      <Card size="small">
        <Flex gap={12} wrap align="center">
          <Select
            allowClear
            style={{ width: 220 }}
            placeholder={t('summary.allProjects')}
            value={projectId}
            onChange={setProjectId}
            showSearch={{ optionFilterProp: 'label' }}
            options={(projects.data ?? []).map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` }))}
          />
          <Select
            allowClear
            style={{ width: 180 }}
            placeholder={t('summary.allEquipment')}
            value={assetId}
            onChange={setAssetId}
            showSearch={{ optionFilterProp: 'label' }}
            options={(assets.data?.items ?? []).filter((a) => primaryTypes.has(a.typeId)).map((a) => ({ value: a.id, label: a.code }))}
          />
          <Segmented<SummaryPeriod>
            value={period}
            onChange={setPeriod}
            options={[
              { value: 'month', label: t('summary.periods.month') },
              { value: 'half_year', label: t('summary.periods.half_year') },
              { value: 'year', label: t('summary.periods.year') },
            ]}
          />
          <DatePicker.RangePicker
            value={range}
            format="DD.MM.YYYY"
            allowClear={false}
            onChange={(v) => v?.[0] && v[1] && setRange([v[0], v[1]])}
          />
          <Space>
            {t('summary.depthStep')}
            <InputNumber min={1} max={10000} value={depthStep} onChange={(v) => v && setDepthStep(v)} suffix="m" style={{ width: 110 }} />
          </Space>
          <Space>
            <Switch checked={includeDrafts} onChange={setIncludeDrafts} />
            {t('summary.includeDrafts')}
          </Space>
          <Button icon={<DownloadOutlined />} onClick={exportCsv} disabled={!summary.data?.rows.length}>
            {t('summary.exportCsv')}
          </Button>
        </Flex>
      </Card>
      <Typography.Text type="secondary">{t('summary.hint')}</Typography.Text>
      <Table<SummaryRow>
        rowKey={(r) => `${r.assetId}|${r.periodStart}`}
        size="small"
        bordered
        loading={summary.isFetching}
        dataSource={summary.data?.rows ?? []}
        columns={columns}
        pagination={false}
        scroll={{ x: 'max-content' }}
        expandable={{ expandedRowRender: details }}
        summary={() =>
          summary.data && summary.data.rows.length > 0 ? (
            <Table.Summary fixed>
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} />
                {columns.map((c, i) => (
                  <Table.Summary.Cell key={c.key} index={i + 1} align={'align' in c ? c.align : undefined}>
                    <Typography.Text strong>
                      {i === 0 ? t('summary.total') : i === 1 ? '' : c.render(undefined, summary.data!.total)}
                    </Typography.Text>
                  </Table.Summary.Cell>
                ))}
              </Table.Summary.Row>
            </Table.Summary>
          ) : null
        }
      />
    </Flex>
  )
}
