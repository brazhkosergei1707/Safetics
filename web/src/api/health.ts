import { useQuery } from '@tanstack/react-query'

export interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

/** Actuator не входить в опис API, тому тут звичайний fetch. */
export function useBackendHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: async () => {
      const response = await fetch('/actuator/health', { headers: { Accept: 'application/json' } })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return (await response.json()) as HealthResponse
    },
    refetchInterval: 30_000,
  })
}
