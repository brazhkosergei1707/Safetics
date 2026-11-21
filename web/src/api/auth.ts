import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api, type Schemas, unwrap } from './client'

export type SessionView = Schemas['SessionView']
export type CompanyView = Schemas['CompanyView']

const SESSION_KEY = ['session'] as const

/**
 * Нова сесія: записуємо її в кеш і прибираємо всі інші дані (вони могли належати іншій компанії).
 * Не queryClient.clear(): він від'єднує вже змонтовані компоненти від кешу, і екран не оновлюється.
 */
function replaceSession(client: QueryClient, session: SessionView | null) {
  client.setQueryData(SESSION_KEY, session)
  client.removeQueries({ predicate: (query) => query.queryKey[0] !== SESSION_KEY[0] })
}

export function useSession() {
  return useQuery({
    queryKey: SESSION_KEY,
    queryFn: () => unwrap(api.GET('/api/v1/auth/me')),
    retry: (count, error) => !(error instanceof ApiError && error.status === 401) && count < 1,
    staleTime: 60_000,
  })
}

export function useLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['LoginRequest']) => unwrap(api.POST('/api/v1/auth/login', { body })),
    onSuccess: (session) => replaceSession(client, session),
  })
}

export function useSwitchCompany() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (companyId: string) =>
      unwrap(api.POST('/api/v1/auth/switch-company', { body: { companyId } })),
    onSuccess: (session) => replaceSession(client, session),
  })
}

export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/auth/logout')),
    onSettled: () => replaceSession(client, null),
  })
}

export function hasPermission(session: SessionView | null | undefined, permission: string): boolean {
  return !!session?.permissions.includes(permission)
}
