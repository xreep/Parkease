import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'
import { api } from './lib/api'
import { ThemeProvider } from './theme/ThemeProvider'

// The real lazy loading: the test setup swaps in an eager stand-in for every other test file.
vi.unmock('./lib/lazyPage')

// Deliberately not `renderApp`: that helper preloads every route so ordinary page tests can use plain queries.
// Here the route chunks are still unloaded, as on a first visit.
function renderUnloaded(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ThemeProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <App />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

describe('route code splitting', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })
  afterEach(() => mock.restore())

  it('shows a loading indicator inside the layout, then the lazily loaded page', async () => {
    renderUnloaded('/login')

    // The shell is there immediately, with a spinner where the page will be: no blank screen.
    expect(screen.getByRole('navigation', { name: /main/i })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Loading page' })).toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.queryByRole('status', { name: 'Loading page' })).not.toBeInTheDocument()
  })

  it('catches a render error in a page and offers a way out, keeping the navbar', async () => {
    // A listing payload without its photos array makes the page throw while rendering.
    mock.onGet('/listings/7').reply(200, { id: 7, title: 'Broken' })
    mock.onGet('/listings/7/quote').reply(200, { available: false, reason: null, freeSlots: 0, totalSlots: 0, quote: null })
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      renderUnloaded('/listings/7')

      expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Go home' })).toHaveAttribute('href', '/')
      expect(screen.getByRole('navigation', { name: /main/i })).toBeInTheDocument()
    } finally {
      spy.mockRestore()
    }
  })
})
