import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { EmailVerificationBanner } from '../EmailVerificationBanner'
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
  return (
    <div className="flex min-h-screen flex-col">
      <ScrollToHash />
      <Navbar />
      <EmailVerificationBanner />
      <main className="flex-1">
        <Outlet />
      </main>
      <Footer />
    </div>
  )
}
