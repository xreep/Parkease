import { useEffect } from 'react'
import { useAuth } from '../auth/AuthProvider'
import type { Role } from '../auth/types'
import * as pages from '../pages/lazyPages'

/** The layout and landing page of each role's area: what that user opens next after the first page. */
const ROLE_PAGES: Record<Role, { preload: () => Promise<void> }[]> = {
  DRIVER: [pages.DriverLayout, pages.DriverHomePage],
  OWNER: [pages.OwnerLayout, pages.OwnerHomePage],
  ADMIN: [pages.AdminLayout, pages.AdminHomePage],
}

type IdleWindow = { requestIdleCallback?: (cb: () => void) => number; cancelIdleCallback?: (id: number) => void }

/** Renders nothing: once the signed-in user's role is known and the browser is idle, warms that role's chunks. */
export function RolePrefetch() {
  const role = useAuth().user?.role
  useEffect(() => {
    if (!role) return
    const idle = window as unknown as IdleWindow
    const warm = () => ROLE_PAGES[role].forEach((page) => void page.preload())
    if (idle.requestIdleCallback) {
      const id = idle.requestIdleCallback(warm)
      return () => idle.cancelIdleCallback?.(id)
    }
    const id = setTimeout(warm, 200)
    return () => clearTimeout(id)
  }, [role])
  return null
}
