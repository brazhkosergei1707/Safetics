import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type AssetType = Schemas['AssetTypeView']
export type Asset = Schemas['AssetView']
export type FieldDefinition = Schemas['FieldDefinition']
export type StatusDefinition = Schemas['StatusDefinition']
export type AssetCreateRequest = Schemas['AssetCreateRequest']
export type AssetUpdateRequest = Schemas['AssetUpdateRequest']

export interface AssetFilter {
  typeId?: string
  q?: string
  includeArchived?: boolean
}

export function useAssetTypes() {
  return useQuery({
    queryKey: ['asset-types'],
    queryFn: () => unwrap(api.GET('/api/v1/asset-types')),
    staleTime: 5 * 60_000,
  })
}

export function useAssets(filter: AssetFilter, enabled = true) {
  return useQuery({
    queryKey: ['assets', filter],
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/assets', {
          params: { query: { ...filter, q: filter.q || undefined, limit: 500 } },
        }),
      ),
    enabled,
  })
}

function useInvalidateAssets() {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['assets'] })
}

export function useCreateAsset() {
  const invalidate = useInvalidateAssets()
  return useMutation({
    mutationFn: (body: AssetCreateRequest) => unwrap(api.POST('/api/v1/assets', { body })),
    onSuccess: invalidate,
  })
}

export function useUpdateAsset() {
  const invalidate = useInvalidateAssets()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: AssetUpdateRequest }) =>
      unwrap(api.PUT('/api/v1/assets/{id}', { params: { path: { id } }, body })),
    onSuccess: invalidate,
  })
}

export function useArchiveAsset() {
  const invalidate = useInvalidateAssets()
  return useMutation({
    mutationFn: (id: string) => unwrap(api.POST('/api/v1/assets/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useInstallTemplate() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (templateKey: string) =>
      unwrap(api.POST('/api/v1/asset-types/install-template', { body: { templateKey } })),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: ['asset-types'] }),
        client.invalidateQueries({ queryKey: ['positions'] }),
      ]),
  })
}

export type AssetTypeRequest = Schemas['AssetTypeRequest']

export function useSaveAssetType() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: AssetTypeRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/asset-types/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/asset-types', { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['asset-types'] }),
  })
}

export function useArchiveAssetType() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/asset-types/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/asset-types/{id}/archive', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['asset-types'] }),
  })
}

/** Усі типи разом з архівними — для редактора типів. */
export function useAllAssetTypes(enabled: boolean) {
  return useQuery({
    queryKey: ['asset-types', 'all'],
    queryFn: () => unwrap(api.GET('/api/v1/asset-types', { params: { query: { includeArchived: true } } })),
    enabled,
  })
}
