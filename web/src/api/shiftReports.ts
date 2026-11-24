import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type ShiftReport = Schemas['ShiftReportView']
export type ShiftReportRequest = Schemas['ShiftReportRequest']
export type ReportIssue = Schemas['ReportIssue']

export function useShiftReport(shiftId?: string) {
  return useQuery({
    queryKey: ['shifts', 'report', shiftId],
    queryFn: () => unwrap(api.GET('/api/v1/shifts/{id}/report', { params: { path: { id: shiftId! } } })),
    enabled: !!shiftId,
  })
}

function useInvalidate() {
  const client = useQueryClient()
  return () => {
    void client.invalidateQueries({ queryKey: ['shifts'] })
    void client.invalidateQueries({ queryKey: ['tasks'] })
    void client.invalidateQueries({ queryKey: ['assets'] })
  }
}

export function useSaveShiftReport() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ shiftId, body }: { shiftId: string; body: ShiftReportRequest }) =>
      unwrap(api.PUT('/api/v1/shifts/{id}/report', { params: { path: { id: shiftId } }, body })),
    onSuccess: invalidate,
  })
}

export function useShiftReportAction() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ shiftId, action, version }: { shiftId: string; action: 'submit' | 'reopen' | 'agree'; version: number }) => {
      const options = { params: { path: { id: shiftId } }, body: { version } }
      if (action === 'agree') return unwrap(api.POST('/api/v1/shifts/{id}/report/agree', options))
      return action === 'submit'
        ? unwrap(api.POST('/api/v1/shifts/{id}/report/submit', options))
        : unwrap(api.POST('/api/v1/shifts/{id}/report/reopen', options))
    },
    onSuccess: invalidate,
  })
}

export function useGenerateShifts(projectId: string) {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: Schemas['GenerateShiftsRequest']) =>
      unwrap(api.POST('/api/v1/projects/{projectId}/shifts/generate', { params: { path: { projectId } }, body })),
    onSuccess: invalidate,
  })
}
