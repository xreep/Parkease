import { screen } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { AdminStatsDto } from '../lib/admin'
import type { BookingDetailDto } from '../lib/bookings'
import type { OwnerStatsDto } from '../lib/ownerDashboard'
import type { PublicListingDto, SearchResponse, SearchResultDto } from '../lib/search'
import { tokenStore } from '../lib/tokenStore'
import { describeViolations, seriousViolations } from './axe'
import { renderApp } from './renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))
vi.mock('../components/search/ResultsMap', () => ({ ResultsMap: () => <div role="img" aria-label="Map of results" /> }))
vi.mock('../components/owner/LocationPicker', () => ({ LocationPicker: () => <div role="img" aria-label="Location on map" /> }))

const users = {
  driver: { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null },
  owner: { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null },
  admin: { id: 4, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null },
}

const states = [
  { id: 14, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cityCount: 7 },
  { id: 32, name: 'Delhi', code: 'DL', slug: 'delhi', type: 'UT', capitalName: 'New Delhi', cityCount: 1 },
]

const result: SearchResultDto = {
  id: 7, title: 'Metro Hub Parking', listingType: 'METRO', address: 'FC Road', cityName: 'Pune', stateName: 'Maharashtra',
  lat: 18.52, lng: 73.85, distanceKm: 0.8, coverPhotoUrl: '/files/a.jpg', pricePerHour: 40, pricePerDay: 250, pricePerMonth: 4500,
  amenities: ['CCTV', 'COVERED'], open24x7: false, avgRating: 4.5, reviewCount: 12, totalSlots: 5, freeSlots: 3,
  quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 89.44, breakdown: '2 hours' },
}
const search: SearchResponse = {
  content: [result], page: 0, size: 20, totalElements: 1, totalPages: 1, center: { lat: 18.5204, lng: 73.8567 }, radiusKm: 5,
  window: { start: '2026-10-12T04:30:00Z', end: '2026-10-12T06:30:00Z' },
}

const listing: PublicListingDto = {
  id: 7, title: 'Metro Hub Parking', description: 'Covered spot next to the metro gate.', listingType: 'METRO',
  address: 'FC Road, Shivajinagar', pincode: '411005', lat: 18.5204, lng: 73.8567, cityName: 'Pune', citySlug: 'pune',
  stateName: 'Maharashtra', stateSlug: 'maharashtra',
  photos: [{ id: 1, url: '/files/a.jpg' }, { id: 2, url: '/files/b.jpg' }],
  amenities: ['COVERED', 'CCTV'], rules: 'No overnight parking.', cancellationPolicy: 'MODERATE', autoApprove: true, open24x7: false,
  hours: [{ dayOfWeek: 1, openTime: '08:00', closeTime: '20:00' }],
  pricePerHour: 40, pricePerDay: 250, pricePerMonth: 4500,
  slotSummary: { twoWheeler: 2, fourWheeler: 3, small: 1, medium: 3, large: 1 },
  avgRating: 4.5, reviewCount: 12, ownerFirstName: 'Priya',
}

const booking: BookingDetailDto = {
  id: 91, bookingCode: 'PE-8KQ2M4', status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
  coverPhotoUrl: '/files/a.jpg', startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER',
  plateNumber: 'MH12AB1234', totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z', address: 'FC Road, Shivajinagar', lat: 18.5, lng: 73.8,
  slotLabel: 'A-3', pricingMode: 'HOURLY', pricingBreakdown: '2 hours at ₹40/hr', baseAmount: 80, platformFee: 8, gstAmount: 1.44,
  refundAmount: 0, holdExpiresAt: null, approvalDeadline: null, confirmedAt: '2026-10-09T08:02:00Z', cancelReason: null, cancelledBy: null,
  paymentStatus: 'CAPTURED', invoiceNumber: 'PE-INV-0042', autoApprove: true, ownerFirstName: 'Priya', reviewable: false, review: null,
  disputes: [], disputable: false,
  events: [
    { fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-09T08:00:00Z' },
    { fromStatus: 'PENDING_PAYMENT', toStatus: 'CONFIRMED', actor: 'SYSTEM', note: null, at: '2026-10-09T08:02:00Z' },
  ],
}

const ownerStats: OwnerStatsDto = {
  from: '2026-09-10', to: '2026-10-09',
  totals: { earningsNet: 12480, bookings: 42, cancellations: 3, occupancyPercent: 37.5, avgRating: 4.6, reviewCount: 18 },
  balances: { held: 1200, pendingPayout: 3400, paid: 7880 },
  pendingApprovals: 2,
  upcoming: [{
    id: 5, bookingCode: 'PE-UPC001', status: 'CONFIRMED', listingId: 7, listingTitle: 'FC Road Parking', slotLabel: 'A-3',
    startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
    driverFirstName: 'Rahul', baseAmount: 240, ownerNet: 216, approvalDeadline: null, createdAt: '2026-10-09T08:00:00Z',
  }],
  series: [
    { date: '2026-10-07', earningsNet: 100, bookings: 2 },
    { date: '2026-10-08', earningsNet: 250, bookings: 3 },
  ],
}

const adminStats: AdminStatsDto = {
  from: '2026-09-10', to: '2026-10-09',
  users: { drivers: 120, owners: 30, newDrivers: 12, newOwners: 3, suspended: 2 },
  listings: { approved: 40, pendingReview: 5, suspended: 1, paused: 2 },
  bookings: { created: 200, confirmed: 150, conversionPercent: 75, cancelled: 20, utilizationPercent: 42.5 },
  money: { gmv: 250000, platformRevenue: 25000, refunds: 12000, ownerEarnings: 200000 },
  topStates: [{ stateId: 1, name: 'Maharashtra', bookings: 90, gmv: 120000 }],
  topCities: [{ cityId: 10, name: 'Pune', stateName: 'Maharashtra', bookings: 50, gmv: 70000 }],
  series: [
    { date: '2026-10-07', bookings: 5, gmv: 5000, revenue: 500 },
    { date: '2026-10-08', bookings: 7, gmv: 8000, revenue: 800 },
  ],
}

describe('accessibility (axe: no serious or critical violations)', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  async function expectAccessible() {
    const violations = await seriousViolations()
    expect(describeViolations(violations)).toEqual([])
  }

  it('home page', async () => {
    mock.onGet('/states').reply(200, states)
    renderApp('/')
    await screen.findByRole('link', { name: /maharashtra/i })
    await expectAccessible()
  })

  it('search results', async () => {
    mock.onGet('/search').reply(200, search)
    renderApp('/search?place=Pune%2C+Maharashtra&lat=18.5204&lng=73.8567&start=2026-10-12T04%3A30%3A00Z&end=2026-10-12T06%3A30%3A00Z&vehicle=FOUR_WHEELER')
    await screen.findByText('Metro Hub Parking')
    await expectAccessible()
  })

  it('listing page', async () => {
    mock.onGet('/listings/7').reply(200, listing)
    mock.onGet('/listings/7/quote').reply(200, {
      available: true, reason: null, freeSlots: 3, totalSlots: 5,
      quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 89.44, breakdown: '2 hours' },
    })
    mock.onGet('/listings/7/reviews').reply(200, { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
    mock.onGet('/listings/7/availability').reply(200, [])
    renderApp('/listings/7?start=2026-10-12T04%3A30%3A00Z&end=2026-10-12T06%3A30%3A00Z&vehicle=FOUR_WHEELER')
    await screen.findByRole('heading', { name: 'Metro Hub Parking' })
    await expectAccessible()
  })

  it('login page', async () => {
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Log in' })
    await expectAccessible()
  })

  it('driver booking detail', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.driver)
    mock.onGet('/bookings/91').reply(200, booking)
    renderApp('/driver/bookings/91')
    await screen.findByRole('region', { name: 'Booking QR code' })
    await expectAccessible()
  })

  it('owner dashboard', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.owner)
    mock.onGet('/owner/profile').reply(200, { verificationStatus: 'VERIFIED', documentType: null, rejectionReason: null })
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    mock.onGet('/owner/stats').reply(200, ownerStats)
    renderApp('/owner')
    await screen.findByRole('group', { name: 'Earnings' })
    await expectAccessible()
  })

  it('admin overview', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, users.admin)
    mock.onGet('/admin/queues').reply(200, { pendingOwners: 2, pendingListings: 1 })
    mock.onGet('/admin/stats').reply(200, adminStats)
    renderApp('/admin')
    await screen.findByRole('navigation', { name: 'Sections' })
    await screen.findByText('Maharashtra')
    await expectAccessible()
  })
})
