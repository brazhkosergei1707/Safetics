import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type ReviewListItem = Schemas['ReviewListItem']
export type ReviewReport = Schemas['ReviewReportView']
export type ReportCorrectionRequest = Schemas['ReportCorrectionRequest']
export type ReportEvent = Schemas['ReportEventView']
export type ReportCorrection = Schemas['CorrectionView']

export interface ReviewFilter {
  projectId?: string
  from?: string
  to?: string
  status?: string
}

/** Портал: подані рапорти проєктів свого замовника. */
export function useReviewReports(filter: ReviewFilter) {
  return useQuery({
    queryKey: ['review', 'list', filter],
    queryFn: () => unwrap(api.GET('/api/v1/review/reports', { params: { query: filter } })),
    placeholderData: keepPreviousData,
  })
}

export function useReviewReport(shiftId?: string) {
  return useQuery({
    queryKey: ['review', 'report', shiftId],
    queryFn: () => unwrap(api.GET('/api/v1/review/reports/{shiftId}', { params: { path: { shiftId: shiftId! } } })),
    enabled: !!shiftId,
  })
}

export type ReviewAction =
  | { kind: 'accept'; version: number }
  | { kind: 'return'; version: number; comment: string }
  | { kind: 'correct'; body: ReportCorrectionRequest }

export function useReviewAction() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ shiftId, action }: { shiftId: string; action: ReviewAction }) => {
      const path = { params: { path: { shiftId } } }
      if (action.kind === 'accept') {
        return unwrap(api.POST('/api/v1/review/reports/{shiftId}/accept', { ...path, body: { version: action.version } }))
      }
      if (action.kind === 'return') {
        return unwrap(
          api.POST('/api/v1/review/reports/{shiftId}/return', {
            ...path,
            body: { version: action.version, comment: action.comment },
          }),
        )
      }
      return unwrap(api.POST('/api/v1/review/reports/{shiftId}/correct', { ...path, body: action.body }))
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['review'] }),
  })
}
