import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type Customer = Schemas['CustomerView']
export type CustomerRequest = Schemas['CustomerRequest']
export type Contact = Schemas['ContactView']
export type ContactRequest = Schemas['ContactRequest']
export type PortalRole = Schemas['PortalAccessRequest']['role']

export function useCustomers(filter: { q?: string; includeArchived?: boolean }, enabled = true) {
  return useQuery({
    queryKey: ['customers', filter],
    queryFn: () =>
      unwrap(api.GET('/api/v1/customers', { params: { query: { ...filter, q: filter.q || undefined } } })),
    enabled,
  })
}

export function useContacts(customerId: string | undefined) {
  return useQuery({
    queryKey: ['customer-contacts', customerId],
    queryFn: () =>
      unwrap(api.GET('/api/v1/customers/{id}/contacts', { params: { path: { id: customerId! } } })),
    enabled: !!customerId,
  })
}

function useInvalidate(...keys: string[]) {
  const client = useQueryClient()
  return () => Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: [key] })))
}

export function useSaveCustomer() {
  const invalidate = useInvalidate('customers')
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: CustomerRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/customers/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/customers', { body })),
    onSuccess: invalidate,
  })
}

export function useArchiveCustomer() {
  const invalidate = useInvalidate('customers')
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore?: boolean }) =>
      restore
        ? unwrap(api.POST('/api/v1/customers/{id}/restore', { params: { path: { id } } }))
        : unwrap(api.POST('/api/v1/customers/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useSaveContact() {
  const invalidate = useInvalidate('customer-contacts')
  return useMutation({
    mutationFn: ({ customerId, id, body }: { customerId: string; id?: string; body: ContactRequest }) =>
      id
        ? unwrap(api.PUT('/api/v1/customer-contacts/{id}', { params: { path: { id } }, body }))
        : unwrap(api.POST('/api/v1/customers/{id}/contacts', { params: { path: { id: customerId } }, body })),
    onSuccess: invalidate,
  })
}

export function useArchiveContact() {
  const invalidate = useInvalidate('customer-contacts')
  return useMutation({
    mutationFn: (id: string) => unwrap(api.POST('/api/v1/customer-contacts/{id}/archive', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}

export function useGrantPortalAccess() {
  const invalidate = useInvalidate('customer-contacts', 'members')
  return useMutation({
    mutationFn: ({ id, role, password }: { id: string; role: PortalRole; password?: string }) =>
      unwrap(
        api.POST('/api/v1/customer-contacts/{id}/portal-access', {
          params: { path: { id } },
          body: { role, password: password || undefined },
        }),
      ),
    onSuccess: invalidate,
  })
}

export function useRevokePortalAccess() {
  const invalidate = useInvalidate('customer-contacts', 'members')
  return useMutation({
    mutationFn: (id: string) =>
      unwrap(api.DELETE('/api/v1/customer-contacts/{id}/portal-access', { params: { path: { id } } })),
    onSuccess: invalidate,
  })
}
