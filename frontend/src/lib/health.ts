import { useQuery } from '@tanstack/react-query'
import { api } from './api'

type Health = { status?: string; demoMode?: boolean }

/**
 * Whether the backend runs the hosted demo (sample data, test payments). Asked once per page load; an older backend,
 * a failure or a missing endpoint all read as "not a demo", and a failure is never worth a toast.
 */
export function useDemoMode(): boolean {
  const { data } = useQuery({
    queryKey: ['health'],
    queryFn: async () => (await api.get<Health>('/health')).data,
    staleTime: Infinity,
    retry: false,
    meta: { silent: true },
  })
  return data?.demoMode === true
}
