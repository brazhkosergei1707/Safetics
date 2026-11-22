import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type ServiceType = Schemas['ServiceTypeView']
export type ServiceTypeRequest = Schemas['ServiceTypeRequest']
export type ServiceItem = Schemas['ServiceView']
export type ServiceStatus = ServiceItem['status']

export function useServiceTypes(includeArchived = false) {
  return useQuery({
    queryKey: ['service-types', includeArchived],
    queryFn: () => unwrap(api.GET('/api/v1/service-types', { params: { query: { includeArchived } } })),
    staleTime: 5 * 60_000,
  })
}

export function useProjectServices(projectId: string) {
  return useQuery({
    queryKey: ['services', projectId],
    queryFn: () =>
      unwrap(api.GET('/api/v1/projects/{projectId}/services', { params: { path: { projectId } } })),
  })
}

function useInvalidate(key: string) {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: [key] })
}

/** Послуга змінює й головну задачу — оновлюємо обидва списки. */
function useInvalidateServices() {
  const client = useQueryClient()
  return () => {
    void client.invalidateQueries({ queryKey: ['services'] })
    void client.invalidateQueries({ queryKey: ['tasks'] })
  }
}

export function useSaveServiceType() {
  const client = useQueryClient()
  const invalidate = () => {
    void client.invalidateQueries({ queryKey: ['service-types'] })
    void client.invalidateQueries({ queryKey: ['task-types'] })
  }
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: ServiceTypeRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/service-types/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/service-types', { body })),
    onSuccess: invalidate,
  })
}

export function useArchiveServiceType() {
  const invalidate = useInvalidate('service-types')
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/service-types/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/service-types/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useCreateService(projectId: string) {
  const invalidate = useInvalidateServices()
  return useMutation({
    mutationFn: (body: Schemas['ServiceCreateRequest']) =>
      unwrap(api.POST('/api/v1/projects/{projectId}/services', { params: { path: { projectId } }, body })),
    onSuccess: invalidate,
  })
}

export function useUpdateService() {
  const invalidate = useInvalidateServices()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: Schemas['ServiceUpdateRequest'] }) =>
      unwrap(api.PUT('/api/v1/services/{id}', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export type ServiceAction = 'start' | 'complete' | 'reopen'

export function useServiceAction() {
  const invalidate = useInvalidateServices()
  return useMutation({
    mutationFn: ({
      id,
      action,
      closingValues,
    }: {
      id: string
      action: ServiceAction
      closingValues?: Record<string, unknown>
    }) => {
      const options = { params: { path: { id } } }
      switch (action) {
        case 'start':
          return unwrap(api.POST('/api/v1/services/{id}/start', options))
        case 'complete':
          return unwrap(api.POST('/api/v1/services/{id}/complete', { ...options, body: { closingValues } }))
        default:
          return unwrap(api.POST('/api/v1/services/{id}/reopen', options))
      }
    },
    onSuccess: invalidate,
  })
}

export function useCancelService() {
  const invalidate = useInvalidateServices()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      unwrap(api.POST('/api/v1/services/{id}/cancel', { params: { path: { id } }, body: { reason } })),
    onSuccess: invalidate,
  })
}
