import { Outlet } from 'react-router-dom'
import { Tabs } from '../../components/ui/Tabs'

const tabs = [
  { to: '/owner', label: 'Overview', end: true },
  { to: '/owner/listings', label: 'Listings' },
  { to: '/owner/bookings', label: 'Bookings' },
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
        <Outlet />
      </div>
    </div>
  )
}
