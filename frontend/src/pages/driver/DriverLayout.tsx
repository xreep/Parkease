import { Outlet } from 'react-router-dom'
import { Tabs } from '../../components/ui/Tabs'

const tabs = [
  { to: '/driver', label: 'Overview', end: true },
  { to: '/driver/bookings', label: 'Bookings' },
  { to: '/driver/payments', label: 'Payments' },
  { to: '/driver/vehicles', label: 'Vehicles' },
]

export function DriverLayout() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6 sm:py-10">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">My parking</h1>
      <div className="mt-4">
        <Tabs items={tabs} />
      </div>
      <div className="mt-6">
        <Outlet />
      </div>
    </div>
  )
}
