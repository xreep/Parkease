import clsx from 'clsx'
import { useEffect, useRef } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'

type NavItem = { to: string; label: string; end?: boolean }

/** Grouped by what an admin is doing; the group names show from `sm` up, on a phone the whole strip scrolls sideways. */
const sections: { label: string; items: NavItem[] }[] = [
  { label: 'Overview', items: [{ to: '/admin', label: 'Overview', end: true }] },
  {
    label: 'Review',
    items: [
      { to: '/admin/owners', label: 'Owners' },
      { to: '/admin/listings', label: 'Listings' },
    ],
  },
  {
    label: 'Operations',
    items: [
      { to: '/admin/bookings', label: 'Bookings' },
      { to: '/admin/disputes', label: 'Disputes' },
      { to: '/admin/payments', label: 'Payments' },
      { to: '/admin/payouts', label: 'Payouts' },
    ],
  },
  {
    label: 'Platform',
    items: [
      { to: '/admin/users', label: 'Users' },
      { to: '/admin/reviews', label: 'Reviews' },
      { to: '/admin/locations', label: 'Locations' },
    ],
  },
  {
    label: 'Insights',
    items: [
      { to: '/admin/reports', label: 'Reports' },
      { to: '/admin/settings', label: 'Settings' },
      { to: '/admin/audit', label: 'Audit' },
    ],
  },
]

function AdminNav() {
  const ref = useRef<HTMLElement>(null)
  const { pathname } = useLocation()
  // On a phone the strip scrolls sideways: keep the current section's tab in view.
  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('[aria-current="page"]')?.scrollIntoView?.({ inline: 'center', block: 'nearest' })
  }, [pathname])
  return (
    <nav ref={ref} aria-label="Sections" className="-mx-4 overflow-x-auto border-b border-slate-200 px-4 dark:border-slate-800 sm:mx-0 sm:px-0">
      <div className="flex min-w-max gap-4">
        {sections.map((section) => (
          <div key={section.label}>
            <p aria-hidden className="hidden px-3 pb-0.5 text-[11px] font-semibold uppercase tracking-wide text-slate-400 sm:block">
              {section.label}
            </p>
            <ul className="flex gap-1">
              {section.items.map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) =>
                      clsx(
                        'block whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition',
                        isActive
                          ? 'border-brand-600 text-brand-700 dark:border-brand-400 dark:text-brand-400'
                          : 'border-transparent text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white',
                      )
                    }
                  >
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </nav>
  )
}

export function AdminLayout() {
  return (
    <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Admin</h1>
      <div className="mt-4">
        <AdminNav />
      </div>
      <div className="mt-6">
        <Outlet />
      </div>
    </div>
  )
}
