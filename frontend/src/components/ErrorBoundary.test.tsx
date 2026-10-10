import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from './ErrorBoundary'

function Bomb({ explode }: { explode: boolean }) {
  if (explode) throw new Error('kaboom secret detail')
  return <p>All fine</p>
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    // React logs caught render errors; keep the test output readable.
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => vi.restoreAllMocks())

  it('renders its children when nothing throws', () => {
    render(<ErrorBoundary><Bomb explode={false} /></ErrorBoundary>)
    expect(screen.getByText('All fine')).toBeInTheDocument()
  })

  it('shows a friendly error page with Reload and Go home, and no technical detail', () => {
    render(<ErrorBoundary><Bomb explode /></ErrorBoundary>)

    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go home' })).toHaveAttribute('href', '/')
    expect(screen.queryByText(/kaboom/)).not.toBeInTheDocument()
    expect(document.title).toBe('ParkEase — Something went wrong')
  })

  it('reloads the page from the Reload button', async () => {
    const reload = vi.fn()
    const original = window.location
    Object.defineProperty(window, 'location', { configurable: true, value: { ...original, reload } })
    try {
      render(<ErrorBoundary><Bomb explode /></ErrorBoundary>)
      await userEvent.click(screen.getByRole('button', { name: 'Reload' }))
      expect(reload).toHaveBeenCalledOnce()
    } finally {
      Object.defineProperty(window, 'location', { configurable: true, value: original })
    }
  })

  it('recovers when the reset key changes (navigating to another page)', () => {
    const { rerender } = render(<ErrorBoundary resetKey="/a"><Bomb explode /></ErrorBoundary>)
    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()

    rerender(<ErrorBoundary resetKey="/b"><Bomb explode={false} /></ErrorBoundary>)
    expect(screen.getByText('All fine')).toBeInTheDocument()
  })

  describe('after a new deploy (a chunk of the old build is gone)', () => {
    const reload = vi.fn()
    const original = window.location
    const ChunkBomb = () => {
      throw new TypeError('Failed to fetch dynamically imported module: https://x.test/assets/Page-abc.js')
    }

    beforeEach(() => {
      sessionStorage.clear()
      reload.mockClear()
      Object.defineProperty(window, 'location', { configurable: true, value: { ...original, reload } })
    })
    afterEach(() => Object.defineProperty(window, 'location', { configurable: true, value: original }))

    it('reloads once on its own and says a new version is available', () => {
      render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)

      expect(reload).toHaveBeenCalledOnce()
      expect(screen.getByRole('heading', { name: 'A new version of ParkEase is available' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
      expect(document.title).toBe('ParkEase — New version available')
    })

    it('does not loop: with a reload already done it only offers the Reload button', async () => {
      sessionStorage.setItem('pe_chunk_reload', String(Date.now()))
      render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)

      expect(reload).not.toHaveBeenCalled()
      await userEvent.click(screen.getByRole('button', { name: 'Reload' }))
      expect(reload).toHaveBeenCalledOnce()
    })

    it('does not treat an ordinary crash as a new version', () => {
      render(<ErrorBoundary><Bomb explode /></ErrorBoundary>)
      expect(reload).not.toHaveBeenCalled()
      expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    })
  })
})
