import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { DayAvailabilityDto, DayLevel } from '../lib/availability'
import type { PublicListingDto } from '../lib/search'
import { toLocalInputValue } from '../lib/time'
import { renderApp } from '../test/renderApp'

vi.mock('../components/owner/LocationPicker', () => ({
  LocationPicker: () => <div data-testid="picker" />,
}))

const listing: PublicListingDto = {
  id: 7, title: 'Metro Hub Parking', description: null, listingType: 'METRO',
  address: 'FC Road, Shivajinagar', pincode: '411005', lat: 18.5204, lng: 73.8567, cityName: 'Pune', citySlug: 'pune',
  stateName: 'Maharashtra', stateSlug: 'maharashtra', photos: [], amenities: [], rules: null,
  cancellationPolicy: 'MODERATE', autoApprove: true, open24x7: false, hours: [],
  pricePerHour: 40, pricePerDay: null, pricePerMonth: null,
  slotSummary: { twoWheeler: 2, fourWheeler: 3, small: 1, medium: 3, large: 1 },
  avgRating: 0, reviewCount: 0, ownerFirstName: 'Priya',
}

const emptyReviews = {
  summary: { avgRating: 0, reviewCount: 0, distribution: { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 } },
  reviews: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
}

// Friday 9 Oct 2026, 11:30 in IST. The last bookable day is today + 90 = 7 Jan 2027.
const NOW = new Date('2026-10-09T06:00:00Z')

const BASE_OVERRIDES: Record<string, Partial<DayAvailabilityDto>> = {
  '2026-10-12': { level: 'AVAILABLE', openTime: '08:00', closeTime: '20:00', bookedPercent: 10 },
  '2026-10-13': { level: 'LIMITED', openTime: '08:00', closeTime: '20:00', bookedPercent: 80 },
  '2026-10-14': { level: 'FULL', openTime: '08:00', closeTime: '20:00', bookedPercent: 100 },
  '2026-10-15': { level: 'CLOSED', openTime: null, closeTime: null, bookedPercent: 0 },
}

/** Per-test copy of BASE_OVERRIDES, reset before every test. */
let OVERRIDES: Record<string, Partial<DayAvailabilityDto>> = {}

function days(from: string, to: string): DayAvailabilityDto[] {
  const out: DayAvailabilityDto[] = []
  for (let d = new Date(`${from}T00:00:00Z`); d <= new Date(`${to}T00:00:00Z`); d = new Date(d.getTime() + 86_400_000)) {
    const date = d.toISOString().slice(0, 10)
    out.push({ date, level: 'AVAILABLE' as DayLevel, openTime: '00:00', closeTime: '23:59', bookedPercent: 0, ...OVERRIDES[date] })
  }
  return out
}

describe('listing availability calendar', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    OVERRIDES = { ...BASE_OVERRIDES }
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    mock = new MockAdapter(api)
    mock.onGet('/listings/7').reply(200, listing)
    mock.onGet('/listings/7/quote').reply(200, { available: true, reason: null, freeSlots: 3, totalSlots: 5, quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 89.44, breakdown: '2 hours' } })
    mock.onGet('/listings/7/reviews').reply(200, emptyReviews)
    mock.onGet('/listings/7/availability').reply((config) => [200, { listingId: 7, days: days(config.params.from, config.params.to) }])
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
  })

  const availabilityCalls = () => mock.history.get.filter((r) => r.url === '/listings/7/availability')

  it('asks for the current month from today and shows each level with a text label', async () => {
    renderApp('/listings/7')

    const calendar = await screen.findByRole('region', { name: 'Availability' })
    expect(within(calendar).getByText('October 2026')).toBeInTheDocument()
    await within(calendar).findByRole('button', { name: '12 October 2026, Available' })
    expect(availabilityCalls()[0].params).toEqual({ from: '2026-10-09', to: '2026-10-31' })

    expect(within(calendar).getByRole('button', { name: '13 October 2026, Limited' })).toBeEnabled()
    expect(within(calendar).getByRole('button', { name: '14 October 2026, Full' })).toBeEnabled()
    // A closed day cannot be picked.
    expect(within(calendar).getByRole('button', { name: '15 October 2026, Closed' })).toBeDisabled()
    // Days before today cannot be picked either.
    expect(within(calendar).getByRole('button', { name: '8 October 2026, Past' })).toBeDisabled()
    expect(within(calendar).getByRole('button', { name: '9 October 2026, Available' })).toBeEnabled()
  })

  it('has a legend that names every level, so colour is not the only cue', async () => {
    renderApp('/listings/7')

    const legend = await screen.findByRole('list', { name: 'Availability legend' })
    const items = within(legend).getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toEqual([expect.stringContaining('Available'), expect.stringContaining('Limited'), expect.stringContaining('Full'), expect.stringContaining('Closed')])
  })

  it('moves between months and stops at the previous month and at today + 90 days', async () => {
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })
    await within(calendar).findByRole('button', { name: '12 October 2026, Available' })

    expect(within(calendar).getByRole('button', { name: 'Previous month' })).toBeDisabled()

    await user.click(within(calendar).getByRole('button', { name: 'Next month' }))
    expect(await within(calendar).findByText('November 2026')).toBeInTheDocument()
    await within(calendar).findByRole('button', { name: '20 November 2026, Available' })
    expect(availabilityCalls().map((r) => r.params)).toContainEqual({ from: '2026-11-01', to: '2026-11-30' })
    expect(within(calendar).getByRole('button', { name: 'Previous month' })).toBeEnabled()

    await user.click(within(calendar).getByRole('button', { name: 'Next month' }))
    await user.click(within(calendar).getByRole('button', { name: 'Next month' }))
    expect(await within(calendar).findByText('January 2027')).toBeInTheDocument()
    // The range is cut at today + 90 days.
    await waitFor(() => expect(availabilityCalls().map((r) => r.params)).toContainEqual({ from: '2027-01-01', to: '2027-01-07' }))
    expect(within(calendar).getByRole('button', { name: 'Next month' })).toBeDisabled()
    expect(await within(calendar).findByRole('button', { name: '7 January 2027, Available' })).toBeEnabled()
    expect(within(calendar).queryByRole('button', { name: /^8 January 2027/ })).toBeDisabled()

    await user.click(within(calendar).getByRole('button', { name: 'Previous month' }))
    expect(await within(calendar).findByText('December 2026')).toBeInTheDocument()
  })

  it('fills the booking card with the picked day, from its opening time for two hours', async () => {
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })

    await user.click(await within(calendar).findByRole('button', { name: '12 October 2026, Available' }))

    const start = new Date('2026-10-12T08:00:00+05:30')
    expect(screen.getByLabelText('From')).toHaveValue(toLocalInputValue(start))
    expect(screen.getByLabelText('Until')).toHaveValue(toLocalInputValue(new Date(start.getTime() + 2 * 3_600_000)))
  })

  it('never ends the picked window after the closing time', async () => {
    OVERRIDES['2026-10-13'] = { level: 'LIMITED', openTime: '08:00', closeTime: '09:00', bookedPercent: 80 }
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })

    await user.click(await within(calendar).findByRole('button', { name: '13 October 2026, Limited' }))

    expect(screen.getByLabelText('Until')).toHaveValue(toLocalInputValue(new Date('2026-10-13T09:00:00+05:30')))
  })

  it('starts today from the next quarter hour when the opening time has passed', async () => {
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })

    await user.click(await within(calendar).findByRole('button', { name: '9 October 2026, Available' }))

    // 11:30 IST now, so the window starts at 11:45 IST.
    expect(screen.getByLabelText('From')).toHaveValue(toLocalInputValue(new Date('2026-10-09T11:45:00+05:30')))
  })

  it('does not offer today once the next quarter hour is at or past closing time', async () => {
    // 11:30 IST now: the next quarter is 11:45.
    OVERRIDES['2026-10-09'] = { level: 'AVAILABLE', openTime: '08:00', closeTime: '11:40', bookedPercent: 0 }
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })

    const today = await within(calendar).findByRole('button', { name: '9 October 2026, Closed for today' })
    expect(today).toBeDisabled()
    const before = (screen.getByLabelText('From') as HTMLInputElement).value
    await user.click(today)
    expect(screen.getByLabelText('From')).toHaveValue(before)
    // Tomorrow is untouched.
    expect(within(calendar).getByRole('button', { name: '10 October 2026, Available' })).toBeEnabled()
  })

  it('still offers today while there is time before closing', async () => {
    OVERRIDES['2026-10-09'] = { level: 'AVAILABLE', openTime: '08:00', closeTime: '11:50', bookedPercent: 0 }
    renderApp('/listings/7')

    expect(await screen.findByRole('button', { name: '9 October 2026, Available' })).toBeEnabled()
  })

  it('does not change the booking card when a closed day is clicked', async () => {
    const user = userEvent.setup()
    renderApp('/listings/7')
    const calendar = await screen.findByRole('region', { name: 'Availability' })
    const before = (screen.getByLabelText('From') as HTMLInputElement).value

    await user.click(await within(calendar).findByRole('button', { name: '15 October 2026, Closed' }))

    expect(screen.getByLabelText('From')).toHaveValue(before)
  })

  it('says so when availability cannot be loaded', async () => {
    mock.onGet('/listings/7/availability').reply(500, { code: 'INTERNAL', detail: 'boom' })
    renderApp('/listings/7')

    expect(await screen.findByText('Could not load availability.')).toBeInTheDocument()
  })
})
