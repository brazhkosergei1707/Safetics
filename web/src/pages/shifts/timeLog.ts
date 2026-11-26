import type { Dayjs } from 'dayjs'
import type { Schemas } from '../../api/client'

type ShiftReport = Schemas['ShiftReportView']

export interface IntervalFields {
  depthFrom?: number
  depthTo?: number
  coreSize?: string
  coreLength?: number
  comment?: string
}

/** Рядок журналу часу; interval — лише для коду «Буріння» (рядок часу = один інтервал). */
export interface TimeRow {
  from?: Dayjs
  to?: Dayjs
  activityCodeId?: string
  taskId?: string
  comment?: string
  interval?: IntervalFields
}

/** Рядки рапорту → рядки форми (час — у годинник зміни). */
export function timeRowsFromReport(times: ShiftReport['times'], toClock: (offset: number) => Dayjs): TimeRow[] {
  return times.map((x) => ({
    from: toClock(x.startMinute),
    to: toClock(x.endMinute),
    activityCodeId: x.activityCodeId,
    taskId: x.taskId ?? undefined,
    comment: x.comment ?? undefined,
    interval: x.interval
      ? {
          depthFrom: x.interval.depthFrom,
          depthTo: x.interval.depthTo,
          coreSize: x.interval.coreSize ?? undefined,
          coreLength: x.interval.coreLength ?? undefined,
          comment: x.interval.comment ?? undefined,
        }
      : undefined,
  }))
}

/** Рядки форми → запит. Інтервал надсилається лише для коду «Буріння» з глибинами. */
export function timeRowsToRequest(
  rows: TimeRow[] | undefined,
  drillingCodes: Set<string>,
  toOffset: (value: Dayjs, isEnd: boolean) => number,
): Schemas['TimeEntryRequest'][] {
  return (rows ?? [])
    .filter((x) => x.from && x.to && x.activityCodeId)
    .map((x) => {
      const i = x.interval
      const withInterval = drillingCodes.has(x.activityCodeId!) && i?.depthFrom != null && i?.depthTo != null
      return {
        startMinute: toOffset(x.from!, false),
        endMinute: toOffset(x.to!, true),
        activityCodeId: x.activityCodeId!,
        taskId: x.taskId,
        comment: x.comment,
        interval: withInterval
          ? { depthFrom: i.depthFrom!, depthTo: i.depthTo!, coreSize: i.coreSize, coreLength: i.coreLength, comment: i.comment }
          : undefined,
      }
    })
}

/** Інтервал у таблиці «лише для читання»: 35,5 → 41,5 м · HQ · керн 5,8 м · 96,7 %. */
export function intervalText(interval: Schemas['IntervalView'] | undefined, t: (key: string) => string) {
  if (!interval) return ''
  const parts = [`${interval.depthFrom} → ${interval.depthTo} m (${interval.meters} m)`]
  if (interval.coreSize) parts.push(interval.coreSize)
  if (interval.coreLength != null) parts.push(`${t('reports.core')} ${interval.coreLength} m`)
  if (interval.recovery != null) parts.push(`${interval.recovery}%`)
  return parts.join(' · ')
}
