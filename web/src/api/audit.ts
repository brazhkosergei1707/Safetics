import { useQuery } from '@tanstack/react-query'
import { apiGet } from './client'

export interface AuditEntry {
  id: number
  userId: string | null
  entity: string
  entityId: string
  action: string
  createdAt: string
}

export function useAuditLog() {
  return useQuery({
    queryKey: ['audit-log'],
    queryFn: () => apiGet<AuditEntry[]>('/api/v1/audit-log?limit=100'),
  })
}
