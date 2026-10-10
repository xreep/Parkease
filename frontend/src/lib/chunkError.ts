const CHUNK_ERROR = /dynamically imported module|Importing a module script failed|Unable to preload CSS|ChunkLoadError/i
const GUARD_KEY = 'pe_chunk_reload'
/** One automatic reload per this long: enough to pick up a new deploy, too short a window to ever loop. */
const GUARD_MS = 30_000

/**
 * A route chunk that cannot be fetched is almost always a new deploy: the old build's file names are gone from the
 * server while this tab still runs the old build. (A flaky network produces the same error.)
 */
export function isChunkLoadError(error: unknown): boolean {
  return error instanceof Error && (CHUNK_ERROR.test(error.message) || error.name === 'ChunkLoadError')
}

/**
 * Reloads the page to pick up the new build, unless it already did that moments ago (or cannot remember, with
 * sessionStorage blocked): then it returns false and the caller offers a manual "Reload" instead of looping.
 */
export function reloadForNewVersion(): boolean {
  try {
    const last = Number(sessionStorage.getItem(GUARD_KEY))
    if (last && Date.now() - last < GUARD_MS) return false
    sessionStorage.setItem(GUARD_KEY, String(Date.now()))
  } catch {
    return false
  }
  window.location.reload()
  return true
}

/**
 * Vite fires `vite:preloadError` on `window` when a lazy chunk (or its CSS) fails to load. Reload once and swallow the
 * error; if that already happened, let it propagate to the error boundary. Returns an uninstall function.
 */
export function installPreloadErrorHandler(): () => void {
  const onError = (event: Event) => {
    if (reloadForNewVersion()) event.preventDefault()
  }
  window.addEventListener('vite:preloadError', onError)
  return () => window.removeEventListener('vite:preloadError', onError)
}
