import { QueryClient } from '@tanstack/react-query'
import { toProblem } from './errors'

/** Never retry client errors (4xx); retry network/5xx failures at most twice. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const { status } = toProblem(error)
  return !(status >= 400 && status < 500) && failureCount < 2
}

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false, retry: shouldRetry } },
  })
}
