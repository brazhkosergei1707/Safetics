import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type Alert = Schemas['AlertView']

/** Мої алерти; оновлюються раз на хвилину. */
export function useAlerts(enabled = true) {
  return useQuery({
    queryKey: ['alerts'],
    queryFn: () => unwrap(api.GET('/api/v1/alerts')),
    enabled,
    refetchInterval: 60_000,
  })
}

export function useReadAlert() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id?: string) =>
      id
        ? unwrap(api.POST('/api/v1/alerts/{id}/read', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/alerts/read-all')),
    onSuccess: () => client.invalidateQueries({ queryKey: ['alerts'] }),
  })
}
