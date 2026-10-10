import { lazy, useState, type ComponentType } from 'react'

const loaders = new Set<() => Promise<unknown>>()

/**
 * A route page loaded on demand (its own bundle chunk), taken from a module's named export. Render it inside a
 * `<Suspense>`: the nearest boundary shows its fallback until the chunk has arrived.
 *
 * Once the chunk is in, new mounts render the page directly instead of suspending again. Tests rely on that: they
 * call `preloadPages()` once so ordinary `render` + `getBy…` keeps working without every page waiting a tick.
 */
export function lazyPage<M extends Record<string, unknown>, K extends keyof M>(load: () => Promise<M>, name: K) {
  type Props = M[K] extends ComponentType<infer P> ? P : never
  let loaded: ComponentType<Props> | undefined
  const fetchPage = async () => {
    const mod = await load()
    loaded = mod[name] as ComponentType<Props>
    return { default: loaded }
  }
  loaders.add(fetchPage)
  const Lazy = lazy(fetchPage)

  return function LazyPage(props: Props) {
    // Decided once per mounted instance, so a page that was showing through `Lazy` is never swapped (and its state
    // lost) just because the chunk finished loading in the meantime.
    const [Ready] = useState(() => loaded)
    const Page = (Ready ?? Lazy) as ComponentType<Props>
    return <Page {...(props as Props & object)} />
  }
}

/** Loads every page registered so far. For tests (see `src/test/renderApp.tsx`) and for warming chunks. */
export async function preloadPages(): Promise<void> {
  await Promise.all([...loaders].map((loader) => loader()))
}
