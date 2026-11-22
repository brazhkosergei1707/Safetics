import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type Project = Schemas['ProjectView']
export type ProjectCreateRequest = Schemas['ProjectCreateRequest']
export type ProjectUpdateRequest = Schemas['ProjectUpdateRequest']
export type Shift = Schemas['Shift']
export type ShiftSchedule = Schemas['ShiftScheduleView']
export type ProjectAccess = Schemas['ProjectAccessView']
export type Member = Schemas['MemberView']

export function useProjects(filter: { status?: string; customerId?: string; q?: string } = {}) {
  return useQuery({
    queryKey: ['projects', filter],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects', { params: { query: { ...filter, q: filter.q || undefined } } })),
  })
}

export function useProject(id: string | undefined) {
  return useQuery({
    queryKey: ['projects', 'one', id],
    queryFn: () => unwrap(api.GET('/api/v1/projects/{id}', { params: { path: { id: id! } } })),
    enabled: !!id,
    retry: false,
  })
}

export function useShiftSchedules(projectId: string) {
  return useQuery({
    queryKey: ['shift-schedules', projectId],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{id}/shift-schedules', { params: { path: { id: projectId } } })),
  })
}

export function useProjectAccess(projectId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['project-access', projectId],
    queryFn: () => unwrap(api.GET('/api/v1/projects/{id}/access', { params: { path: { id: projectId } } })),
    enabled,
  })
}

export function useMembers(enabled: boolean) {
  return useQuery({
    queryKey: ['members'],
    queryFn: () => unwrap(api.GET('/api/v1/members')),
    enabled,
    staleTime: 60_000,
  })
}

function useInvalidate(...keys: string[]) {
  const client = useQueryClient()
  return () => Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: [key] })))
}

export function useCreateProject() {
  const invalidate = useInvalidate('projects')
  return useMutation({
    mutationFn: (body: ProjectCreateRequest) => unwrap(api.POST('/api/v1/projects', { body })),
    onSuccess: invalidate,
  })
}

export function useUpdateProject() {
  const invalidate = useInvalidate('projects', 'shift-schedules')
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ProjectUpdateRequest }) =>
      unwrap(api.PUT('/api/v1/projects/{id}', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export function useCloseProject() {
  const invalidate = useInvalidate('projects')
  return useMutation({
    mutationFn: ({ id, reopen }: { id: string; reopen?: boolean }) =>
      reopen
        ? unwrap(api.POST('/api/v1/projects/{id}/reopen', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/projects/{id}/close', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useSaveShiftSchedule(projectId: string) {
  const invalidate = useInvalidate('shift-schedules', 'projects')
  return useMutation({
    mutationFn: (body: Schemas['ShiftScheduleRequest']) =>
      unwrap(api.POST('/api/v1/projects/{id}/shift-schedules', { params: { path: { id: projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useDeleteShiftSchedule(projectId: string) {
  const invalidate = useInvalidate('shift-schedules', 'projects')
  return useMutation({
    mutationFn: (scheduleId: string) =>
      unwrap(
        api.DELETE('/api/v1/projects/{id}/shift-schedules/{scheduleId}', {
          params: { path: { id: projectId, scheduleId } },
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useGrantProjectAccess(projectId: string) {
  const invalidate = useInvalidate('project-access')
  return useMutation({
    mutationFn: (userId: string) =>
      unwrap(api.POST('/api/v1/projects/{id}/access', { params: { path: { id: projectId } }, body: { userId } })),
    onSuccess: invalidate,
  })
}

export function useRevokeProjectAccess(projectId: string) {
  const invalidate = useInvalidate('project-access')
  return useMutation({
    mutationFn: (userId: string) =>
      unwrap(
        api.DELETE('/api/v1/projects/{id}/access/{userId}', { params: { path: { id: projectId, userId } } }),
      ),
    onSuccess: invalidate,
  })
}

/** «2×12», «3×8», «12 + 8» — короткий опис графіка для таблиці. */
export function shiftsSummary(shifts: Shift[]): string {
  if (shifts.length === 0) return '—'
  const hours = shifts.map((s) => s.durationMinutes / 60)
  const same = hours.every((h) => h === hours[0])
  const fmt = (h: number) => (Number.isInteger(h) ? String(h) : h.toFixed(1))
  return same ? `${shifts.length}×${fmt(hours[0])}` : hours.map(fmt).join(' + ')
}

// ---------------------------------------------------------------- призначення

export type PersonnelAssignment = Schemas['PersonnelAssignmentView']
export type EquipmentAssignment = Schemas['EquipmentAssignmentView']

export function useProjectPersonnel(projectId: string) {
  return useQuery({
    queryKey: ['project-personnel', projectId],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{id}/personnel-assignments', { params: { path: { id: projectId } } })),
  })
}

export function useProjectEquipment(projectId: string) {
  return useQuery({
    queryKey: ['project-equipment', projectId],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{id}/equipment-assignments', { params: { path: { id: projectId } } })),
  })
}

export function useAssignPersonnel(projectId: string) {
  const invalidate = useInvalidate('project-personnel', 'personnel-assignments', 'project-access')
  return useMutation({
    mutationFn: (body: Schemas['PersonnelAssignmentRequest']) =>
      unwrap(api.POST('/api/v1/projects/{id}/personnel-assignments', { params: { path: { id: projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useAddCrewMember(projectId: string) {
  const invalidate = useInvalidate('project-personnel', 'personnel')
  return useMutation({
    mutationFn: (body: Schemas['CrewMemberRequest']) =>
      unwrap(api.POST('/api/v1/projects/{id}/crew', { params: { path: { id: projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useUpdatePersonnelAssignment(projectId: string) {
  const invalidate = useInvalidate('project-personnel', 'personnel-assignments')
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['PersonnelAssignmentUpdateRequest'] }) =>
      unwrap(
        api.PUT('/api/v1/projects/{id}/personnel-assignments/{assignmentId}', {
          params: { path: { id: projectId, assignmentId: id } },
          body,
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useDeletePersonnelAssignment(projectId: string) {
  const invalidate = useInvalidate('project-personnel', 'personnel-assignments')
  return useMutation({
    mutationFn: (id: string) =>
      unwrap(
        api.DELETE('/api/v1/projects/{id}/personnel-assignments/{assignmentId}', {
          params: { path: { id: projectId, assignmentId: id } },
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useAssignEquipment(projectId: string) {
  const invalidate = useInvalidate('project-equipment', 'available-equipment')
  return useMutation({
    mutationFn: (body: Schemas['EquipmentAssignmentRequest']) =>
      unwrap(api.POST('/api/v1/projects/{id}/equipment-assignments', { params: { path: { id: projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useUpdateEquipmentAssignment(projectId: string) {
  const invalidate = useInvalidate('project-equipment')
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['AssignmentDatesRequest'] }) =>
      unwrap(
        api.PUT('/api/v1/projects/{id}/equipment-assignments/{assignmentId}', {
          params: { path: { id: projectId, assignmentId: id } },
          body,
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useDeleteEquipmentAssignment(projectId: string) {
  const invalidate = useInvalidate('project-equipment')
  return useMutation({
    mutationFn: (id: string) =>
      unwrap(
        api.DELETE('/api/v1/projects/{id}/equipment-assignments/{assignmentId}', {
          params: { path: { id: projectId, assignmentId: id } },
        }),
      ),
    onSuccess: invalidate,
  })
}

/** «01.10.2026 – …» */
export function formatPeriod(start: string, end?: string | null): string {
  const fmt = (d: string) => d.split('-').reverse().join('.')
  return `${fmt(start)} – ${end ? fmt(end) : '…'}`
}

export type AvailableEquipment = Schemas['AvailableEquipmentView']

/** Обладнання для вибору на дати: вільне / зайняте. */
export function useAvailableEquipment(projectId: string, startDate: string | undefined, endDate: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['available-equipment', projectId, startDate, endDate],
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/projects/{id}/available-equipment', {
          params: { path: { id: projectId }, query: { startDate, endDate } },
        }),
      ),
    enabled,
  })
}
