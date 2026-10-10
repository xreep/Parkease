import { QueryCache, QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { toProblem } from './errors'

/** Never retry client errors (4xx); retry network/5xx failures at most twice. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const { status } = toProblem(error)
  return !(status >= 400 && status < 500) && failureCount < 2
}

const SERVER_ERROR_MESSAGE = 'Something went wrong on our side. Please try again in a moment.'
/** A backend that is down fails every poll and page load: say so once in a while, not on each of them. */
const SERVER_ERROR_TOAST_EVERY_MS = 60_000
let lastServerErrorToast = 0

/**
 * Tells the user once (at most per minute) that the server failed. Called only when a query has finally failed, after
 * its retries. A query opts out with `meta: { silent: true }`: background polls and anything whose page already shows
 * the problem inline. Nothing technical is shown.
 */
function notifyServerError(error: unknown, meta: Record<string, unknown> | undefined) {
  if (meta?.silent || toProblem(error).status < 500) return
  const now = Date.now()
  if (now - lastServerErrorToast < SERVER_ERROR_TOAST_EVERY_MS) return
  lastServerErrorToast = now
  toast.error(SERVER_ERROR_MESSAGE, { id: 'server-error' })
}

export function createQueryClient() {
  return new QueryClient({
    queryCache: new QueryCache({ onError: (error, query) => notifyServerError(error, query.meta) }),
    defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false, retry: shouldRetry } },
  })
}
