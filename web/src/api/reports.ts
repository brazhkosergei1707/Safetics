import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type Summary = Schemas['SummaryView']
export type SummaryRow = Schemas['SummaryRow']
export type SummaryPeriod = Summary['period']

export interface SummaryFilter {
  projectId?: string
  assetId?: string
  from: string
  to: string
  period: SummaryPeriod
  depthStep: number
  includeDrafts: boolean
}

/** Зведений звіт: обладнання × період з підсумком. */
export function useSummary(filter: SummaryFilter) {
  return useQuery({
    queryKey: ['reports', 'summary', filter],
    queryFn: () => unwrap(api.GET('/api/v1/reports/summary', { params: { query: filter } })),
    placeholderData: keepPreviousData,
  })
}
