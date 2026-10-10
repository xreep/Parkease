import { render, screen } from '@testing-library/react'
import { Suspense } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { lazyPage, preloadPages } from './lazyPage'

describe('lazyPage', () => {
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

  it('renders without suspending once preloaded', async () => {
    const load = vi.fn(async () => ({ Ready: () => <p>Ready now</p> }))
    const Page = lazyPage(load, 'Ready')

    await preloadPages()
    render(<Suspense fallback={<p>Loading…</p>}><Page /></Suspense>)

    expect(screen.getByText('Ready now')).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })
})
