import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type ShiftChecklist = Schemas['ShiftChecklistView']
export type ChecklistAnswer = Schemas['ChecklistAnswer']
export type ChecklistItem = Schemas['ChecklistItem']

export function useShiftChecklist(shiftId?: string) {
  return useQuery({
    queryKey: ['shifts', 'checklist', shiftId],
    queryFn: () => unwrap(api.GET('/api/v1/shifts/{id}/checklist', { params: { path: { id: shiftId! } } })),
    enabled: !!shiftId,
  })
}

export function useSubmitChecklist() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ shiftId, body }: { shiftId: string; body: Schemas['ChecklistSubmitRequest'] }) =>
      unwrap(api.POST('/api/v1/shifts/{id}/checklist', { params: { path: { id: shiftId } }, body })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['shifts'] })
      void client.invalidateQueries({ queryKey: ['tasks'] })
      void client.invalidateQueries({ queryKey: ['assets'] })
      void client.invalidateQueries({ queryKey: ['alerts'] })
    },
  })
}
