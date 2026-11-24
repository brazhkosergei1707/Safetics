import type { TFunction } from 'i18next'
import type { MonthlyContent, MonthlyReport } from '../../api/monthly'

export const MONTHLY_STATUS_COLORS: Record<string, string | undefined> = {
  draft: 'blue',
  sent: 'orange',
  approved: 'green',
  returned: 'red',
  superseded: undefined,
}

const hours = (minutes: number) => Math.round((minutes / 60) * 100) / 100

type T = TFunction

function rows(content: MonthlyContent, t: T): string[][] {
  const out: string[][] = [[t('monthly.csv.section'), t('monthly.csv.asset'), t('monthly.csv.item'), t('monthly.csv.value'), t('monthly.csv.unit')]]
  for (const asset of content.assets) {
    out.push([t('monthly.csv.meters'), asset.assetCode, '', String(asset.meters), 'm'])
    for (const line of asset.metersByHole) {
      out.push([t('monthly.csv.meters'), asset.assetCode, `${line.serviceCode} ${line.coreSize ?? ''}`.trim(), String(line.meters), 'm'])
    }
    out.push([t('monthly.csv.hours'), asset.assetCode, '', String(hours(asset.billableMinutes)), 'h'])
    for (const line of asset.hours) {
      out.push([t('monthly.csv.hours'), asset.assetCode, line.code, String(hours(line.minutes)), 'h'])
    }
    for (const q of asset.quantities) {
      out.push([t('monthly.csv.quantity'), asset.assetCode, q.serviceCode ?? '', String(q.quantity), q.unit ?? ''])
    }
  }
  for (const a of content.adjustments) {
    out.push([t('monthly.csv.adjustment'), a.assetCode ?? '', `${a.comment}${a.correctsLabel ? ` (${a.correctsLabel})` : ''}`, String(a.amount),
      a.kind === 'meters' ? 'm' : a.kind === 'hours' ? 'h' : (a.unit ?? '')])
  }
  out.push([t('monthly.csv.total'), '', t('monthly.csv.meters'), String(content.totals.meters), 'm'])
  out.push([t('monthly.csv.total'), '', t('monthly.csv.hours'), String(hours(content.totals.billableMinutes)), 'h'])
  for (const q of content.totals.quantities) out.push([t('monthly.csv.total'), '', t('monthly.csv.quantity'), String(q.quantity), q.unit ?? ''])
  return out
}

export function downloadCsv(report: MonthlyReport, t: T) {
  const csv = rows(report.content, t)
    .map((r) => r.map((c) => `"${c.replaceAll('"', '""')}"`).join(';'))
    .join('\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${report.projectCode}_${report.month.slice(0, 7)}_v${report.version}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

const escape = (s: string) => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')

/** Сторінка для друку (або «Зберегти як PDF» у браузері). */
export function printReport(report: MonthlyReport, t: T) {
  const table = rows(report.content, t)
    .map((r, i) => `<tr>${r.map((c) => (i === 0 ? `<th>${escape(c)}</th>` : `<td>${escape(c)}</td>`)).join('')}</tr>`)
    .join('')
  const win = window.open('', '_blank')
  if (!win) return
  win.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>${escape(report.projectCode)} ${report.month.slice(0, 7)}</title>
<style>body{font:12px system-ui,sans-serif;margin:24px}h1{font-size:18px}table{border-collapse:collapse;width:100%}
th,td{border:1px solid #ccc;padding:4px 6px;text-align:left}th{background:#f3f3f3}</style></head><body>
<h1>${escape(t('monthly.printTitle', { project: `${report.projectCode} · ${report.projectName}`, month: report.month.slice(0, 7) }))}</h1>
<p>${escape(report.customerName ?? '')} · v${report.version} · ${escape(t(`monthly.status.${report.status}`))}</p>
<table>${table}</table></body></html>`)
  win.document.close()
  win.focus()
  win.print()
}
