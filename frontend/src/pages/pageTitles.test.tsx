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

  it('puts the booking code in the title of a booking once loaded (driver and admin)', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.DRIVER)
    mock.onGet('/bookings/91').reply(200, { ...bookingStub, bookingCode: 'PE-8KQ2M4' })
    renderApp('/driver/bookings/91')
    await screen.findByText('PE-8KQ2M4')
    expect(document.title).toBe('ParkEase — Booking PE-8KQ2M4')
  })

  it('puts the booking code in the title of the admin booking page', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.ADMIN)
    mock.onGet('/admin/bookings/91').reply(200, {
      ...bookingStub, bookingCode: 'PE-8KQ2M4', driverName: 'Rahul', driverEmail: 'r@example.com', ownerName: 'Priya',
      refundableRemaining: 0, payment: null, refunds: [],
    })
    renderApp('/admin/bookings/91')
    await waitFor(() => expect(document.title).toBe('ParkEase — Admin · Booking PE-8KQ2M4'))
  })

  it.each([
    ['/driver/disputes/4', 'DRIVER', '/me', '/disputes/4', 'ParkEase — Problem with PE-8KQ2M4'],
    ['/owner/disputes/4', 'OWNER', '/me', '/owner/disputes/4', 'ParkEase — Dispute on PE-8KQ2M4'],
    ['/admin/disputes/4', 'ADMIN', '/me', '/admin/disputes/4', 'ParkEase — Admin · Dispute on PE-8KQ2M4'],
  ] as const)('puts the booking code in the title of %s', async (path, role, _me, api_, title) => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users[role])
    mock.onGet(api_).reply(200, {
      id: 4, bookingId: 91, bookingCode: 'PE-8KQ2M4', listingTitle: 'Metro Hub Parking', category: 'NO_ACCESS', status: 'OPEN',
      createdAt: '2026-10-09T08:00:00Z', resolvedAt: null, description: 'The gate was locked', raisedByName: 'Rahul', ownerResponse: null,
      ownerRespondedAt: null, resolution: null, resolutionAmount: null, adminNotes: null, refundableRemaining: 0,
    })
    renderApp(path)
    await waitFor(() => expect(document.title).toBe(title))
  })
})

const bookingStub = {
  id: 91, status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune', coverPhotoUrl: null,
  startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
  totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z', address: 'FC Road', lat: 18.5, lng: 73.8, slotLabel: 'A-3',
  pricingMode: 'HOURLY', pricingBreakdown: '2 hours', baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 0,
  holdExpiresAt: null, approvalDeadline: null, confirmedAt: null, cancelReason: null, cancelledBy: null, paymentStatus: 'CAPTURED',
  invoiceNumber: null, autoApprove: true, ownerFirstName: 'Priya', reviewable: false, review: null, disputes: [], disputable: false, events: [],
}
