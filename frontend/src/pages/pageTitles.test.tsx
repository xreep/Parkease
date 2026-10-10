import { screen, waitFor } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import { renderApp } from '../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))
vi.mock('../components/search/ResultsMap', () => ({ ResultsMap: () => <div>Map</div> }))
vi.mock('../components/owner/LocationPicker', () => ({ LocationPicker: () => <div>Picker</div> }))

const users = {
  DRIVER: { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null },
  OWNER: { id: 2, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null },
  ADMIN: { id: 3, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null },
}

type Role = keyof typeof users | null

// [path, signed-in role, expected title]. Pages set their title as soon as they mount, before any data arrives.
const routes: [string, Role, string][] = [
  ['/', null, 'Reserve parking across India'],
  ['/search', null, 'Find parking'],
  ['/login', null, 'Log in'],
  ['/register', null, 'Create your account'],
  ['/forgot-password', null, 'Forgot your password?'],
  ['/reset-password', null, 'Choose a new password'],
  ['/verify-email', null, 'Verify your email'],
  ['/no/such/page', null, 'Page not found'],
  ['/account', 'DRIVER', 'Your account'],
  ['/notifications', 'DRIVER', 'Notifications'],
  ['/driver', 'DRIVER', 'My parking'],
  ['/driver/bookings', 'DRIVER', 'My bookings'],
  ['/driver/bookings/91', 'DRIVER', 'Booking details'],
  ['/driver/payments', 'DRIVER', 'Payments'],
  ['/driver/vehicles', 'DRIVER', 'My vehicles'],
  ['/driver/disputes', 'DRIVER', 'Help'],
  ['/driver/disputes/4', 'DRIVER', 'Problem report'],
  ['/checkout/91', 'DRIVER', 'Review and pay'],
  ['/owner', 'OWNER', 'Owner dashboard'],
  ['/owner/bookings', 'OWNER', 'Owner bookings'],
  ['/owner/earnings', 'OWNER', 'Owner earnings'],
  ['/owner/calendar', 'OWNER', 'Owner calendar'],
  ['/owner/reviews', 'OWNER', 'Owner reviews'],
  ['/owner/disputes', 'OWNER', 'Owner disputes'],
  ['/owner/disputes/4', 'OWNER', 'Dispute details'],
  ['/owner/verification', 'OWNER', 'Owner verification'],
  ['/owner/listings', 'OWNER', 'My listings'],
  ['/owner/listings/new', 'OWNER', 'New listing'],
  ['/owner/listings/7/edit', 'OWNER', 'Edit listing'],
  ['/owner/listings/7/blocks', 'OWNER', 'Block dates'],
  ['/admin', 'ADMIN', 'Admin · Overview'],
  ['/admin/owners', 'ADMIN', 'Admin · Owner queue'],
  ['/admin/listings', 'ADMIN', 'Admin · Listing queue'],
  ['/admin/listings/7', 'ADMIN', 'Admin · Review listing'],
  ['/admin/disputes', 'ADMIN', 'Admin · Disputes'],
  ['/admin/disputes/4', 'ADMIN', 'Admin · Dispute details'],
  ['/admin/bookings', 'ADMIN', 'Admin · Bookings'],
  ['/admin/bookings/91', 'ADMIN', 'Admin · Booking details'],
  ['/admin/payments', 'ADMIN', 'Admin · Payments'],
  ['/admin/payouts', 'ADMIN', 'Admin · Payouts'],
  ['/admin/users', 'ADMIN', 'Admin · Users'],
  ['/admin/reviews', 'ADMIN', 'Admin · Reviews'],
  ['/admin/locations', 'ADMIN', 'Admin · Locations'],
  ['/admin/reports', 'ADMIN', 'Admin · Reports and exports'],
  ['/admin/settings', 'ADMIN', 'Admin · Settings'],
  ['/admin/audit', 'ADMIN', 'Admin · Audit log'],
]

describe('page titles', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    document.title = ''
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it.each(routes)('%s is titled "ParkEase — %s"', async (path, role, title) => {
    if (role) {
      tokenStore.set('a', 'r')
      mock.onGet('/me').reply(200, users[role])
    }
    renderApp(path)

    await waitFor(() => expect(document.title).toBe(`ParkEase — ${title}`))
  })

  it('titles a listing with its name once loaded', async () => {
    mock.onGet('/listings/7').reply(200, {
      id: 7, title: 'Metro Hub Parking', description: 'Covered.', listingType: 'METRO', address: 'FC Road', pincode: '411005',
      lat: 18.5, lng: 73.8, cityName: 'Pune', citySlug: 'pune', stateName: 'Maharashtra', stateSlug: 'maharashtra', photos: [],
      amenities: [], rules: null, cancellationPolicy: 'MODERATE', autoApprove: true, open24x7: true, hours: [],
      pricePerHour: 40, pricePerDay: null, pricePerMonth: null, slotSummary: { twoWheeler: 0, fourWheeler: 1, small: 0, medium: 1, large: 0 },
      avgRating: 0, reviewCount: 0, ownerFirstName: 'Priya',
    })
    mock.onGet('/listings/7/quote').reply(200, { available: false, reason: 'NO_WINDOW', freeSlots: 0, totalSlots: 1, quote: null })
    renderApp('/listings/7')

    await screen.findByRole('heading', { name: 'Metro Hub Parking' })
    expect(document.title).toBe('ParkEase — Metro Hub Parking')
  })

  it('titles a state and a city with their names once loaded', async () => {
    mock.onGet('/states/maharashtra').reply(200, {
      id: 14, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cities: [],
    })
    renderApp('/in/maharashtra')

    await screen.findByRole('heading', { name: 'Maharashtra' })
    expect(document.title).toBe('ParkEase — Parking in Maharashtra')
  })
})
