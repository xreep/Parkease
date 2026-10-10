import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installPreloadErrorHandler, isChunkLoadError, reloadForNewVersion } from './chunkError'

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://x.test/assets/Page-abc.js',
    'error loading dynamically imported module: https://x.test/assets/Page-abc.js',
    'Importing a module script failed.',
    'Unable to preload CSS for /assets/index-abc.css',
  ])('recognises "%s"', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('ignores other errors and non-errors', () => {
    expect(isChunkLoadError(new Error('boom'))).toBe(false)
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(false)
    expect(isChunkLoadError(undefined)).toBe(false)
  })
})

describe('reloadForNewVersion', () => {
  const reload = vi.fn()
  const original = window.location

  beforeEach(() => {
    sessionStorage.clear()
    reload.mockClear()
    Object.defineProperty(window, 'location', { configurable: true, value: { ...original, reload } })
  })
  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: original })
    vi.useRealTimers()
  })

  it('reloads once, then refuses until enough time has passed (no reload loop)', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-10T10:00:00Z'))

    expect(reloadForNewVersion()).toBe(true)
    expect(reload).toHaveBeenCalledOnce()

    vi.setSystemTime(new Date('2026-10-10T10:00:05Z'))
    expect(reloadForNewVersion()).toBe(false)
    expect(reload).toHaveBeenCalledOnce()

    // A later deploy in the same long-lived tab may reload again.
    vi.setSystemTime(new Date('2026-10-10T10:05:00Z'))
    expect(reloadForNewVersion()).toBe(true)
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('still refuses to loop when sessionStorage is unavailable', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied') })
    try {
      expect(reloadForNewVersion()).toBe(false)
      expect(reload).not.toHaveBeenCalled()
    } finally {
      get.mockRestore()
      vi.restoreAllMocks()
    }
  })
})

describe('installPreloadErrorHandler', () => {
  const reload = vi.fn()
  const original = window.location
  let uninstall: () => void

  beforeEach(() => {
    sessionStorage.clear()
    reload.mockClear()
    Object.defineProperty(window, 'location', { configurable: true, value: { ...original, reload } })
    uninstall = installPreloadErrorHandler()
  })
  afterEach(() => {
    uninstall()
    Object.defineProperty(window, 'location', { configurable: true, value: original })
  })

  it('reloads on Vite\'s vite:preloadError (a chunk of an old build is gone) and swallows that error', () => {
    const event = new Event('vite:preloadError', { cancelable: true })
    window.dispatchEvent(event)

    expect(reload).toHaveBeenCalledOnce()
    expect(event.defaultPrevented).toBe(true)
  })

  it('lets the error through when it already reloaded, so the boundary can offer a manual reload', () => {
    window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))
    const second = new Event('vite:preloadError', { cancelable: true })
    window.dispatchEvent(second)

    expect(reload).toHaveBeenCalledOnce()
    expect(second.defaultPrevented).toBe(false)
  })
})
