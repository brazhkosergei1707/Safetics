import { useQuery } from '@tanstack/react-query'
import { api, type Schemas, unwrap } from './client'

export type AuditEntry = Schemas['AuditEntry']

export function useAuditLog() {
  return useQuery({
    queryKey: ['audit-log'],
    queryFn: () => unwrap(api.GET('/api/v1/audit-log', { params: { query: { limit: 100 } } })),
  })
}
