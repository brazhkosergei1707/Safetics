import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type WorkShift = Schemas['WorkShiftView']
export type CrewMember = Schemas['CrewMemberView']
export type CrewEntry = Schemas['CrewEntry']
export type ShiftBoard = Schemas['ShiftBoardView']

/** Дошка «обладнання × зміни» проєкту за період (дати — YYYY-MM-DD, включно). */
export function useShiftBoard(projectId: string, from: string, to: string, enabled = true) {
  return useQuery({
    queryKey: ['shifts', 'board', projectId, from, to],
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{projectId}/shift-board', {
          params: { path: { projectId }, query: { from, to } },
        }),
      ),
    enabled,
    placeholderData: keepPreviousData,
  })
}

export function useWorkShift(id?: string) {
  return useQuery({
    queryKey: ['shifts', 'one', id],
    queryFn: () => unwrap(api.GET('/api/v1/shifts/{id}', { params: { path: { id: id! } } })),
    enabled: !!id,
  })
}

function useInvalidateShifts() {
  const client = useQueryClient()
  return () => {
    void client.invalidateQueries({ queryKey: ['shifts'] })
    void client.invalidateQueries({ queryKey: ['tasks'] })
  }
}

export function useCreateShift(projectId: string) {
  const invalidate = useInvalidateShifts()
  return useMutation({
    mutationFn: (body: Schemas['WorkShiftCreateRequest']) =>
      unwrap(api.POST('/api/v1/projects/{projectId}/shifts', { params: { path: { projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useUpdateCrew() {
  const invalidate = useInvalidateShifts()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['CrewUpdateRequest'] }) =>
      unwrap(api.PUT('/api/v1/shifts/{id}/crew', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}
