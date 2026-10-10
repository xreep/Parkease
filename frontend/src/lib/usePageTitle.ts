import { useEffect } from 'react'

const SITE = 'ParkEase'

/**
 * Sets the tab title to "ParkEase — <page>". A page whose name depends on data passes a fallback until it arrives.
 * With no title it leaves the current one alone, so a page that renders another page inside it (a 404 for an unknown
 * listing) doesn't have its own title overwritten by the outer one.
 */
export function usePageTitle(title?: string | null) {
  useEffect(() => {
    if (title) document.title = `${SITE} — ${title}`
  }, [title])
}
