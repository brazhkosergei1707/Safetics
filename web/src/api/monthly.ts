import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type MonthView = Schemas['MonthView']
export type MonthlyReport = Schemas['MonthlyReportView']
export type MonthlyContent = Schemas['MonthlyContent']
export type AdjustmentRequest = Schemas['AdjustmentRequest']

/** Місяць проєкту (YYYY-MM): живий перегляд, версії звіту, чи закрито. */
export function useProjectMonth(projectId: string, month: string) {
  return useQuery({
    queryKey: ['monthly', projectId, month],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{projectId}/months/{month}', { params: { path: { projectId, month } } })),
  })
}

export type MonthAction =
  | { kind: 'generate' }
  | { kind: 'send'; reportId: string }
  | { kind: 'include'; shiftId: string; included: boolean }
  | { kind: 'adjust'; body: AdjustmentRequest }
  | { kind: 'deleteAdjustment'; adjustmentId: string }

export function useMonthAction(projectId: string, month: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (action: MonthAction): Promise<unknown> => {
      const path = { params: { path: { projectId, month } } }
      switch (action.kind) {
        case 'generate':
          return unwrap(api.POST('/api/v1/projects/{projectId}/months/{month}/generate', path))
        case 'send':
          return unwrap(api.POST('/api/v1/monthly-reports/{id}/send', { params: { path: { id: action.reportId } } }))
        case 'include':
          return unwrap(
            api.POST('/api/v1/projects/{projectId}/months/{month}/inclusions', {
              ...path,
              body: { shiftId: action.shiftId, included: action.included },
            }),
          )
        case 'adjust':
          return unwrap(api.POST('/api/v1/projects/{projectId}/months/{month}/adjustments', { ...path, body: action.body }))
        case 'deleteAdjustment':
          return unwrap(
            api.DELETE('/api/v1/projects/{projectId}/months/{month}/adjustments/{adjustmentId}', {
              params: { path: { projectId, month, adjustmentId: action.adjustmentId } },
            }),
          )
      }
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['monthly', projectId, month] }),
  })
}

/** Портал: надіслані замовнику місячні звіти. */
export function usePortalMonthlyReports() {
  return useQuery({
    queryKey: ['monthly', 'portal'],
    queryFn: () => unwrap(api.GET('/api/v1/review/monthly-reports')),
  })
}

export function usePortalMonthlyDecision() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, approve, comment }: { id: string; approve: boolean; comment?: string }) =>
      approve
        ? unwrap(api.POST('/api/v1/review/monthly-reports/{id}/approve', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/review/monthly-reports/{id}/return', { params: { path: { id } }, body: { comment: comment ?? '' } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['monthly', 'portal'] }),
  })
}
