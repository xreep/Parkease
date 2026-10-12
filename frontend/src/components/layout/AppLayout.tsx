import { Suspense, useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { ErrorBoundary } from '../ErrorBoundary'
import { RolePrefetch } from '../RolePrefetch'
import { EmailVerificationBanner } from '../EmailVerificationBanner'
import { PageFallback } from '../PageFallback'
import { Footer } from './Footer'
import { Navbar } from './Navbar'

function ScrollToHash() {
  const { pathname, hash } = useLocation()
  useEffect(() => {
    try {
      if (hash) document.getElementById(hash.slice(1))?.scrollIntoView?.()
      else if (typeof window.scrollTo === 'function') window.scrollTo(0, 0)
    } catch {
      // scrolling is best-effort (e.g. jsdom)
    }
  }, [pathname, hash])
  return null
}

export function AppLayout() {
  const { pathname } = useLocation()
  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-white focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-brand-700 focus:shadow-lg dark:focus:bg-slate-900 dark:focus:text-brand-400"
      >
        Skip to main content
      </a>
      <ScrollToHash />
      <RolePrefetch />
      <Navbar />
      <EmailVerificationBanner />
      <main id="main" tabIndex={-1} className="flex-1 outline-none">
        <ErrorBoundary resetKey={pathname}>
          <Suspense fallback={<PageFallback />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
      <Footer />
    </div>
  )
}
