import { render, screen } from '@testing-library/react'
import { Suspense } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { lazyPage } from './lazyPage'

// The real implementation: the test setup swaps in an eager one for every other test file.
vi.unmock('./lazyPage')

describe('lazyPage', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('suspends until the module has loaded, then renders the named export with its props', async () => {
    let finish!: () => void
    const gate = new Promise<void>((resolve) => { finish = resolve })
    const Page = lazyPage(async () => { await gate; return { Hello: ({ name }: { name: string }) => <p>{`Hello ${name}`}</p> } }, 'Hello')

    render(<Suspense fallback={<p>Loading…</p>}><Page name="Ada" /></Suspense>)
    expect(screen.getByText('Loading…')).toBeInTheDocument()

    finish()
    expect(await screen.findByText('Hello Ada')).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('does not cache a failed import: the next navigation tries again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.useFakeTimers({ toFake: ['Date'] })
    const load = vi
      .fn<() => Promise<{ Flaky: () => React.ReactElement }>>()
      .mockRejectedValueOnce(new TypeError('Failed to fetch dynamically imported module: /assets/Flaky.js'))
      .mockResolvedValue({ Flaky: () => <p>Loaded on retry</p> })
    const Page = lazyPage(load, 'Flaky')
    const tree = (key: string) => (
      <ErrorBoundary resetKey={key}>
        <Suspense fallback={<p>Loading…</p>}><Page /></Suspense>
      </ErrorBoundary>
    )

    // First visit: the chunk fails to load. (A stale-chunk error reloads the page once; keep that out of this test.)
    sessionStorage.setItem('pe_chunk_reload', String(Date.now()))
    const { rerender } = render(tree('/flaky'))
    expect(await screen.findByRole('heading', { name: /new version of ParkEase/ })).toBeInTheDocument()

    // The failure was not retried in a loop behind the boundary's back.
    expect(load).toHaveBeenCalledOnce()

    // A while later the user navigates again (the boundary resets): the chunk is asked for afresh, not replayed.
    vi.setSystemTime(Date.now() + 10_000)
    rerender(tree('/elsewhere'))
    expect(await screen.findByText('Loaded on retry')).toBeInTheDocument()
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('preload() fetches the chunk ahead of time, once, and never throws', async () => {
    const load = vi.fn(async () => ({ Ahead: () => <p>Ahead</p> }))
    const Page = lazyPage(load, 'Ahead')

    await Page.preload()
    await Page.preload()
    expect(load).toHaveBeenCalledOnce()

    const failing = lazyPage(async () => { throw new TypeError('Failed to fetch dynamically imported module') }, 'Nope' as never)
    await expect(failing.preload()).resolves.toBeUndefined()
  })
})
