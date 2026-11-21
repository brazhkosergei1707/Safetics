import { useQuery } from '@tanstack/react-query'
import { apiGet } from './client'

export interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

export function useBackendHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => apiGet<HealthResponse>('/actuator/health'),
    refetchInterval: 30_000,
  })
}
