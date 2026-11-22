import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type PlatformCompany = Schemas['PlatformCompanyView']
export type CreateCompanyRequest = Schemas['CreateCompanyRequest']
export type TemplateSummary = Schemas['TemplateSummary']

export function usePlatformCompanies() {
  return useQuery({
    queryKey: ['platform-companies'],
    queryFn: () => unwrap(api.GET('/api/v1/platform/companies')),
  })
}

export function useTemplates() {
  return useQuery({
    queryKey: ['platform-templates'],
    queryFn: () => unwrap(api.GET('/api/v1/platform/templates')),
    staleTime: 10 * 60_000,
  })
}

export function useCreateCompany() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCompanyRequest) => unwrap(api.POST('/api/v1/platform/companies', { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform-companies'] }),
  })
}

export function useSetCompanyStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, suspend }: { id: string; suspend: boolean }) =>
      suspend
        ? unwrap(api.POST('/api/v1/platform/companies/{id}/suspend', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/platform/companies/{id}/activate', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['platform-companies'] }),
  })
}
