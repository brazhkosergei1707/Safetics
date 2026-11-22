import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type CompanyUser = Schemas['CompanyUserView']

export function useUsers() {
  return useQuery({ queryKey: ['users'], queryFn: () => unwrap(api.GET('/api/v1/users')) })
}

function useInvalidate() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['users'] }),
      client.invalidateQueries({ queryKey: ['members'] }),
      client.invalidateQueries({ queryKey: ['personnel'] }),
    ])
}

export function useAddUser() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: Schemas['AddUserRequest']) => unwrap(api.POST('/api/v1/users', { body })),
    onSuccess: invalidate,
  })
}

export function useChangeRole() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: string }) =>
      unwrap(api.PUT('/api/v1/users/{userId}/role', { params: { path: { userId } }, body: { role } })),
    onSuccess: invalidate,
  })
}

export function useSetUserEnabled() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ userId, enabled }: { userId: string; enabled: boolean }) =>
      enabled
        ? unwrap(api.POST('/api/v1/users/{userId}/enable', { params: { path: { userId } } }))
        : unwrap(api.POST('/api/v1/users/{userId}/disable', { params: { path: { userId } } })),
    onSuccess: invalidate,
  })
}
