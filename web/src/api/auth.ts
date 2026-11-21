import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiGet, apiPost } from './client'

export interface UserView {
  id: string
  fullName: string
  phone: string
}

export interface CompanyView {
  id: string
  name: string
  role: string | null
}

/** company === null: користувач працює в кількох компаніях і ще не обрав одну. */
export interface SessionView {
  user: UserView
  company: CompanyView | null
  companies: CompanyView[]
  permissions: string[]
}

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
    queryFn: () => apiGet<SessionView>('/api/v1/auth/me'),
    retry: (count, error) => !(error instanceof ApiError && error.status === 401) && count < 1,
    staleTime: 60_000,
  })
}

export function useLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { phone: string; password: string }) =>
      apiPost<SessionView>('/api/v1/auth/login', input),
    onSuccess: (session) => replaceSession(client, session),
  })
}

export function useSwitchCompany() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (companyId: string) =>
      apiPost<SessionView>('/api/v1/auth/switch-company', { companyId }),
    onSuccess: (session) => replaceSession(client, session),
  })
}

export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => apiPost<void>('/api/v1/auth/logout'),
    onSettled: () => replaceSession(client, null),
  })
}

export function hasPermission(session: SessionView | null | undefined, permission: string): boolean {
  return !!session?.permissions.includes(permission)
}
