import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type TaskType = Schemas['TaskTypeView']
export type TaskTypeRequest = Schemas['TaskTypeRequest']
export type TaskStatusDefinition = Schemas['TaskStatusDefinition']
export type Task = Schemas['TaskView']
export type TaskCategory = Task['statusCategory']
export type TaskLinks = Schemas['TaskLinksView']
export type TaskRef = Schemas['TaskRef']
export type TaskHistoryEntry = Schemas['TaskHistoryEntry']

export const CATEGORY_COLORS: Record<TaskCategory, string | undefined> = {
  new: undefined,
  active: 'blue',
  done: 'green',
  cancelled: undefined,
}

export function useTaskTypes(includeArchived = false, enabled = true) {
  return useQuery({
    queryKey: ['task-types', includeArchived],
    queryFn: () => unwrap(api.GET('/api/v1/task-types', { params: { query: { includeArchived } } })),
    staleTime: 5 * 60_000,
    enabled,
  })
}

export function useProjectTasks(projectId: string, enabled = true) {
  return useQuery({
    queryKey: ['tasks', 'project', projectId],
    queryFn: () => unwrap(api.GET('/api/v1/projects/{projectId}/tasks', { params: { path: { projectId } } })),
    enabled,
  })
}

export function useMyTasks(includeClosed: boolean) {
  return useQuery({
    queryKey: ['tasks', 'my', includeClosed],
    queryFn: () => unwrap(api.GET('/api/v1/tasks/my', { params: { query: { includeClosed } } })),
  })
}

export function useTask(id: string | undefined) {
  return useQuery({
    queryKey: ['tasks', 'one', id],
    queryFn: () => unwrap(api.GET('/api/v1/tasks/{id}', { params: { path: { id: id! } } })),
    enabled: !!id,
  })
}

export function useTaskLinks(id: string | undefined) {
  return useQuery({
    queryKey: ['tasks', 'links', id],
    queryFn: () => unwrap(api.GET('/api/v1/tasks/{id}/links', { params: { path: { id: id! } } })),
    enabled: !!id,
  })
}

export function useTaskHistory(id: string | undefined) {
  return useQuery({
    queryKey: ['tasks', 'history', id],
    queryFn: () => unwrap(api.GET('/api/v1/tasks/{id}/history', { params: { path: { id: id! } } })),
    enabled: !!id,
  })
}

/** Після будь-якої зміни задачі оновлюємо всі списки задач і послуги (синхронізація статусів). */
function useInvalidateTasks() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['tasks'] }),
      client.invalidateQueries({ queryKey: ['services'] }),
    ])
}

export function useCreateTask(projectId: string) {
  const invalidate = useInvalidateTasks()
  return useMutation({
    mutationFn: (body: Schemas['TaskCreateRequest']) =>
      unwrap(api.POST('/api/v1/projects/{projectId}/tasks', { params: { path: { projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useUpdateTask() {
  const invalidate = useInvalidateTasks()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['TaskUpdateRequest'] }) =>
      unwrap(api.PUT('/api/v1/tasks/{id}', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export function useChangeTaskStatus() {
  const invalidate = useInvalidateTasks()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['TaskStatusRequest'] }) =>
      unwrap(api.POST('/api/v1/tasks/{id}/status', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export function useAddTaskLink() {
  const invalidate = useInvalidateTasks()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['TaskLinkRequest'] }) =>
      unwrap(api.POST('/api/v1/tasks/{id}/links', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export function useRemoveTaskLink() {
  const invalidate = useInvalidateTasks()
  return useMutation({
    mutationFn: ({ id, linkId }: { id: string; linkId: string }) =>
      unwrap(api.DELETE('/api/v1/tasks/{id}/links/{linkId}', { params: { path: { id, linkId } } })),
    onSuccess: invalidate,
  })
}

export function useSaveTaskType() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: TaskTypeRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/task-types/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/task-types', { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['task-types'] }),
  })
}

export function useArchiveTaskType() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/task-types/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/task-types/{id}/archive', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['task-types'] }),
  })
}
