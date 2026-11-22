import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type Position = Schemas['PositionView']
export type PositionRequest = Schemas['PositionRequest']
export type Personnel = Schemas['PersonnelView']
export type PersonnelRequest = Schemas['PersonnelRequest']
export type EmploymentType = Personnel['employmentType']

export const EMPLOYMENT_TYPES: EmploymentType[] = ['staff', 'subcontractor', 'hired']

/** Системні ролі, які можна видати виконавцю (сервер ще перевірить «не більше прав, ніж маєш сам»). */
export const STAFF_ROLES = ['worker', 'manager', 'director', 'admin'] as const

export function usePositions(includeArchived = false) {
  return useQuery({
    queryKey: ['positions', includeArchived],
    queryFn: () => unwrap(api.GET('/api/v1/positions', { params: { query: { includeArchived } } })),
    staleTime: 5 * 60_000,
  })
}

export function usePersonnel(filter: { q?: string; includeArchived?: boolean }, enabled = true) {
  return useQuery({
    queryKey: ['personnel', filter],
    queryFn: () =>
      unwrap(api.GET('/api/v1/personnel', { params: { query: { ...filter, q: filter.q || undefined } } })),
    enabled,
  })
}

export function usePersonnelHistory(id: string | undefined) {
  return useQuery({
    queryKey: ['personnel-assignments', id],
    queryFn: () => unwrap(api.GET('/api/v1/personnel/{id}/assignments', { params: { path: { id: id! } } })),
    enabled: !!id,
  })
}

function useInvalidate(...keys: string[]) {
  const client = useQueryClient()
  return () => Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: [key] })))
}

export function useSavePosition() {
  const invalidate = useInvalidate('positions')
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: PositionRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/positions/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/positions', { body })),
    onSuccess: invalidate,
  })
}

export function useArchivePosition() {
  const invalidate = useInvalidate('positions')
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/positions/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/positions/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useSavePersonnel() {
  const invalidate = useInvalidate('personnel', 'personnel-assignments')
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: PersonnelRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/personnel/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/personnel', { body })),
    onSuccess: invalidate,
  })
}

export function useArchivePersonnel() {
  const invalidate = useInvalidate('personnel')
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/personnel/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/personnel/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useGrantLogin() {
  const invalidate = useInvalidate('personnel', 'members', 'project-access')
  return useMutation({
    mutationFn: ({ id, role, password }: { id: string; role: string; password?: string }) =>
      unwrap(
        api.POST('/api/v1/personnel/{id}/login', {
          params: { path: { id } },
          body: { role, password: password || undefined },
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useRevokeLogin() {
  const invalidate = useInvalidate('personnel', 'members')
  return useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE('/api/v1/personnel/{id}/login', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}
