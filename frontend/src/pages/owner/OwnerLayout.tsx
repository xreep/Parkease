import { Suspense } from 'react'
import { Outlet } from 'react-router-dom'
import { PageFallback } from '../../components/PageFallback'
import { Tabs } from '../../components/ui/Tabs'

const tabs = [
  { to: '/owner', label: 'Overview', end: true },
  { to: '/owner/listings', label: 'Listings' },
  { to: '/owner/bookings', label: 'Bookings' },
  { to: '/owner/earnings', label: 'Earnings' },
  { to: '/owner/calendar', label: 'Calendar' },
  { to: '/owner/reviews', label: 'Reviews' },
  { to: '/owner/disputes', label: 'Disputes' },
  { to: '/owner/verification', label: 'Verification' },
]

export function OwnerLayout() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6 sm:py-10">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Owner dashboard</h1>
      <div className="mt-4">
        <Tabs items={tabs} />
      </div>
      <div className="mt-6">
        <Suspense fallback={<PageFallback />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  )
}
