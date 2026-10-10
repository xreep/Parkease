import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import App from '../App'
import { AuthProvider } from '../auth/AuthProvider'
import { preloadPages } from '../lib/lazyPage'
import { ThemeProvider } from '../theme/ThemeProvider'

// Route pages load lazily in the app. Loading them all once up front lets `renderApp` keep rendering a page
// synchronously, so tests can use plain queries instead of waiting out a Suspense fallback on every render.
// (`App.lazy.test.tsx` renders without this helper to cover the lazy path itself.)
await preloadPages()

export function renderApp(
  initialPath = '/',
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  /** Rendered next to the app inside the router, e.g. to probe the location. */
  extra?: ReactNode,
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={[initialPath]}>
            <App />
            {extra}
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}
