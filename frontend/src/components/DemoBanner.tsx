import { X } from 'lucide-react'
import { useState } from 'react'
import { useDemoMode } from '../lib/health'

const DISMISSED_KEY = 'pe_demo_banner_dismissed'

function wasDismissed(): boolean {
  try {
    return sessionStorage.getItem(DISMISSED_KEY) === '1'
  } catch {
    return false
  }
}

/** A slim notice across the top of a hosted demo; it can be dismissed for the rest of the browser session. */
export function DemoBanner() {
  const demo = useDemoMode()
  const [dismissed, setDismissed] = useState(wasDismissed)
  if (!demo || dismissed) return null

  function dismiss() {
    setDismissed(true)
    try {
      sessionStorage.setItem(DISMISSED_KEY, '1')
    } catch {
      // Not remembered across page loads; fine.
    }
  }

  return (
    <section
      aria-label="Demo site notice"
      className="border-b border-sky-200 bg-sky-50 text-sky-950 dark:border-sky-900 dark:bg-sky-950/60 dark:text-sky-100"
    >
      <div className="mx-auto flex max-w-7xl items-start gap-2 px-4 py-1.5 text-sm sm:px-6">
        <p className="flex-1">
          <strong className="font-semibold">Demo site — sample data, test payments only.</strong> Don't enter real personal
          details. Demo logins (admin, owner and driver accounts) are listed in the project README.
        </p>
        <button
          type="button"
          aria-label="Dismiss demo notice"
          onClick={dismiss}
          className="mt-0.5 shrink-0 rounded p-1 hover:bg-sky-100 dark:hover:bg-sky-900"
        >
          <X aria-hidden className="h-4 w-4" />
        </button>
      </div>
    </section>
  )
}
