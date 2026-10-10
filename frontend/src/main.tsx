import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'
import './index.css'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'
import { installPreloadErrorHandler } from './lib/chunkError'
import { createQueryClient } from './lib/queryClient'
import { ThemeProvider } from './theme/ThemeProvider'

// A lazy chunk that is gone after a new deploy: reload once to pick up the new build (see lib/chunkError.ts).
installPreloadErrorHandler()

const queryClient = createQueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <BrowserRouter>
            <App />
            <Toaster richColors position="top-center" />
          </BrowserRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </StrictMode>,
)
