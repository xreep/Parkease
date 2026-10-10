import { createElement, lazy, type ComponentType } from 'react'

/** How long a failed import's rejection is replayed before the next render tries the import again. */
const RETRY_AFTER_MS = 2_000

/** A lazily loaded route page: render it like the component it wraps (inside a `<Suspense>`), or `preload()` it. */
export type LazyPage<P> = ComponentType<P> & { preload: () => Promise<void> }

/**
 * A route page loaded on demand (its own bundle chunk), taken from a module's named export. The nearest `<Suspense>`
 * shows its fallback until the chunk has arrived.
 *
 * A failed import is not remembered for good: `React.lazy` would replay the rejection for the rest of the session.
 * For a moment after a failure the rejection is replayed (so the error reaches the error boundary instead of React
 * retrying the import in a loop); after that the next render, a fresh navigation, starts a new `lazy` and asks again.
 */
export function lazyPage<M extends Record<string, unknown>, K extends keyof M>(load: () => Promise<M>, name: K): LazyPage<
  M[K] extends ComponentType<infer P> ? P : never
> {
  type Props = M[K] extends ComponentType<infer P> ? P : never
  let pending: Promise<{ default: ComponentType<Props> }> | undefined
  let failedAt = 0

  const fetchPage = () => {
    pending ??= load().then(
      (mod) => ({ default: mod[name] as ComponentType<Props> }),
      (error: unknown) => {
        pending = undefined
        failedAt = Date.now()
        throw error
      },
    )
    return pending
  }
  const makeLazy = () => lazy(fetchPage)
  let Lazy = makeLazy()

  /** The page element to render now: from the current `lazy`, or a fresh one once the last failure is old enough to retry. */
  function element(props: Props) {
    if (failedAt && Date.now() - failedAt > RETRY_AFTER_MS) {
      failedAt = 0
      Lazy = makeLazy()
    }
    return createElement(Lazy as unknown as ComponentType<Record<string, unknown>>, props as Record<string, unknown>)
  }

  function LazyPage(props: Props) {
    return element(props)
  }
  LazyPage.preload = async () => {
    try {
      await fetchPage()
    } catch {
      // A warm-up that fails is harmless: the real navigation retries and reports.
    }
  }
  return LazyPage
}
