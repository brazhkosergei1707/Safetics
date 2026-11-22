import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type ActivityCode = Schemas['ActivityCodeView']
export type ActivityCodeRequest = Schemas['ActivityCodeRequest']
export type ActivityCategory = ActivityCode['category']
export type ConsumableItem = Schemas['ConsumableItemView']
export type ConsumableItemRequest = Schemas['ConsumableItemRequest']

export function useActivityCodes(includeArchived = false, enabled = true) {
  return useQuery({
    queryKey: ['activity-codes', includeArchived],
    queryFn: () => unwrap(api.GET('/api/v1/activity-codes', { params: { query: { includeArchived } } })),
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useConsumableItems(includeArchived = false, enabled = true) {
  return useQuery({
    queryKey: ['consumable-items', includeArchived],
    queryFn: () => unwrap(api.GET('/api/v1/consumable-items', { params: { query: { includeArchived } } })),
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useSaveActivityCode() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: ActivityCodeRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/activity-codes/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/activity-codes', { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['activity-codes'] }),
  })
}

export function useArchiveActivityCode() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/activity-codes/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/activity-codes/{id}/archive', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['activity-codes'] }),
  })
}

export function useSaveConsumableItem() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: ConsumableItemRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/consumable-items/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/consumable-items', { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['consumable-items'] }),
  })
}

export function useArchiveConsumableItem() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/consumable-items/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/consumable-items/{id}/archive', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['consumable-items'] }),
  })
}
