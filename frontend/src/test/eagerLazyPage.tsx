import { createElement, type ComponentType } from 'react'

/**
 * Test stand-in for `lib/lazyPage` (swapped in by `setup.ts`): it starts every page's import straight away, and
 * `preloadPages()` waits for all of them. After that a page renders synchronously, so ordinary tests keep using plain
 * `render` + `getBy…` without waiting out a Suspense fallback. The real implementation is covered by
 * `lazyPage.test.tsx` and `App.lazy.test.tsx`, which opt out of this with `vi.unmock`.
 */
const loading: Promise<unknown>[] = []

export function lazyPage<M extends Record<string, unknown>>(load: () => Promise<M>, name: keyof M) {
  let Page: ComponentType<Record<string, unknown>> | undefined
  const ready = load().then((mod) => {
    Page = mod[name] as ComponentType<Record<string, unknown>>
  })
  loading.push(ready)
  function EagerPage(props: Record<string, unknown>) {
    if (!Page) throw ready // not preloaded: behave like React.lazy and suspend
    return createElement(Page, props)
  }
  EagerPage.preload = () => ready.then(() => undefined, () => undefined)
  return EagerPage
}

export const preloadPages = () => Promise.all(loading).then(() => undefined)
