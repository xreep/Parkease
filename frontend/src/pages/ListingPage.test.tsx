import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { tokenStore } from '../lib/tokenStore'
import type { ListingQuoteResponse, PublicListingDto } from '../lib/search'
import { nextQuarter } from '../lib/time'
import { renderApp } from '../test/renderApp'

vi.mock('../components/owner/LocationPicker', () => ({
  LocationPicker: ({ value, readOnly }: { value: { lat: number; lng: number } | null; readOnly?: boolean }) => (
    <div data-testid="picker">{`${readOnly ? 'locked' : 'editable'} ${value?.lat},${value?.lng}`}</div>
  ),
}))

const DAY = 24 * 60 * 60 * 1000
const start = new Date(nextQuarter().getTime() + DAY).toISOString()
const end = new Date(nextQuarter().getTime() + DAY + 2 * 60 * 60 * 1000).toISOString()
const URL_7 = `/listings/7?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}&vehicle=FOUR_WHEELER`

const listing: PublicListingDto = {
  id: 7, title: 'Metro Hub Parking', description: 'Covered spot next to the metro gate.', listingType: 'METRO',
  address: 'FC Road, Shivajinagar', pincode: '411005', lat: 18.5204, lng: 73.8567, cityName: 'Pune', citySlug: 'pune',
  stateName: 'Maharashtra', stateSlug: 'maharashtra',
  photos: [
    { id: 1, url: '/files/a.jpg' },
    { id: 2, url: '/files/b.jpg' },
    { id: 3, url: '/files/c.jpg' },
  ],
  amenities: ['COVERED', 'CCTV'], rules: 'No overnight parking.', cancellationPolicy: 'MODERATE', autoApprove: true,
  open24x7: false,
  hours: [
    { dayOfWeek: 1, openTime: '08:00', closeTime: '20:00' },
    { dayOfWeek: 7, openTime: '09:00', closeTime: '21:00' },
  ],
  pricePerHour: 40, pricePerDay: 250, pricePerMonth: 4500,
  slotSummary: { twoWheeler: 2, fourWheeler: 3, small: 1, medium: 3, large: 1 },
  avgRating: 4.5, reviewCount: 12, ownerFirstName: 'Priya',
}

const available: ListingQuoteResponse = {
  available: true, reason: null, freeSlots: 3, totalSlots: 5,
  quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 89.44, breakdown: '2 hours' },
}

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }
const owner = { ...driver, id: 2, name: 'Ravi Kumar', role: 'OWNER' }

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

describe('ListingPage', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
    mock.onGet('/listings/7').reply(200, listing)
    mock.onGet('/listings/7/quote').reply(200, available)
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  /** Pretends the browser is in the given IANA time zone. */
  function browserZone(timeZone: string) {
    const real = Intl.DateTimeFormat.prototype.resolvedOptions
    vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (this: Intl.DateTimeFormat) {
      return { ...real.call(this), timeZone }
    })
  }

  const quoteCalls = () => mock.history.get.filter((r) => r.url === '/listings/7/quote')

  it('shows the listing facts', async () => {
    renderApp(URL_7)

    expect(await screen.findByRole('heading', { level: 1, name: 'Metro Hub Parking' })).toBeInTheDocument()
    expect(screen.getByText('Metro / transit hub · Pune, Maharashtra')).toBeInTheDocument()
    expect(screen.getByText('★ 4.5 (12)')).toBeInTheDocument()
    expect(screen.getByText('Covered spot next to the metro gate.')).toBeInTheDocument()
    expect(screen.getByText(/FC Road, Shivajinagar/)).toHaveTextContent('411005')
    expect(screen.getByTestId('picker')).toHaveTextContent('locked 18.5204,73.8567')
    const directions = screen.getByRole('link', { name: /get directions/i })
    expect(directions).toHaveAttribute('href', 'https://www.google.com/maps/dir/?api=1&destination=18.5204,73.8567')
    expect(directions).toHaveAttribute('target', '_blank')
    expect(directions).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(screen.getByText('3 car · 2 two-wheeler')).toBeInTheDocument()
    expect(screen.getByText('Sunday')).toBeInTheDocument()
    expect(screen.getByText('09:00 – 21:00')).toBeInTheDocument()
    expect(screen.getByText('Tuesday').nextElementSibling).toHaveTextContent('Closed')
    expect(screen.getByText('Covered')).toBeInTheDocument()
    expect(screen.getByText('CCTV')).toBeInTheDocument()
    expect(screen.getByText('No overnight parking.')).toBeInTheDocument()
    expect(screen.getByText(/full refund up to 24 hours before start/i)).toBeInTheDocument()
    expect(screen.getByText('Hosted by Priya')).toBeInTheDocument()
  })

  it('labels the opening hours as IST', async () => {
    renderApp(URL_7)

    expect(await screen.findByRole('heading', { name: 'Opening hours (IST)' })).toBeInTheDocument()
  })

  it('hints that times are in IST when the browser is in another time zone', async () => {
    browserZone('Europe/London')
    renderApp(URL_7)
    await screen.findByText('3 slots free')

    expect(screen.getByText('Times use your device’s time zone. Opening hours are shown in IST.')).toBeInTheDocument()
  })

  it('shows no time zone hint when the browser is on IST', async () => {
    browserZone('Asia/Calcutta')
    renderApp(URL_7)
    await screen.findByText('3 slots free')

    expect(screen.queryByText(/Opening hours are shown in IST/)).not.toBeInTheDocument()
  })

  it('defaults to a car when the URL has no vehicle and the listing has car slots', async () => {
    renderApp('/listings/7')
    await screen.findByText('3 slots free')

    expect(screen.getByLabelText('Vehicle')).toHaveValue('FOUR_WHEELER')
    expect(quoteCalls()[0].params.vehicleType).toBe('FOUR_WHEELER')
  })

  it('defaults to a two-wheeler when the listing has no car slots', async () => {
    mock.onGet('/listings/7').reply(200, { ...listing, slotSummary: { ...listing.slotSummary, fourWheeler: 0 } })
    renderApp('/listings/7')
    await screen.findByText('3 slots free')

    expect(screen.getByLabelText('Vehicle')).toHaveValue('TWO_WHEELER')
    expect(quoteCalls()[0].params.vehicleType).toBe('TWO_WHEELER')
  })

  it('keeps the vehicle from the URL even when the listing has no such slots', async () => {
    mock.onGet('/listings/7').reply(200, { ...listing, slotSummary: { ...listing.slotSummary, fourWheeler: 0 } })
    renderApp(URL_7)
    await screen.findByText('3 slots free')

    expect(screen.getByLabelText('Vehicle')).toHaveValue('FOUR_WHEELER')
  })

  it('says Open 24 x 7 when the listing never closes', async () => {
    mock.onGet('/listings/7').reply(200, { ...listing, open24x7: true, hours: [] })
    renderApp(URL_7)

    expect(await screen.findByText('Open 24 × 7')).toBeInTheDocument()
    expect(screen.queryByText('Sunday')).not.toBeInTheDocument()
  })

  it('quotes the URL times and shows the price breakdown', async () => {
    renderApp(URL_7)

    expect(await screen.findByText('3 slots free')).toBeInTheDocument()
    expect(quoteCalls()).toHaveLength(1)
    expect(quoteCalls()[0].params).toEqual({ start, end, vehicleType: 'FOUR_WHEELER' })
    expect(screen.getByText('Parking (2 hours)')).toBeInTheDocument()
    expect(screen.getByText('Platform fee')).toBeInTheDocument()
    expect(screen.getByText('GST on fee')).toBeInTheDocument()
    expect(screen.getByText('Total').nextElementSibling).toHaveTextContent('₹89.44')
    expect(screen.getByText('₹40/hr')).toBeInTheDocument()
    expect(screen.getByText('₹250/day')).toBeInTheDocument()
    expect(screen.getByText('₹4,500/month')).toBeInTheDocument()
  })

  it('explains an unavailable vehicle type, disables Reserve and keeps the vehicle in the URL', async () => {
    mock.onGet('/listings/7/quote').reply((config) =>
      config.params.vehicleType === 'TWO_WHEELER'
        ? [200, { ...available, available: false, reason: 'NO_VEHICLE_SLOTS', freeSlots: 0 }]
        : [200, available],
    )
    renderApp(URL_7, undefined, <LocationProbe />)
    await screen.findByText('3 slots free')

    await userEvent.selectOptions(screen.getByLabelText('Vehicle'), 'TWO_WHEELER')

    expect(await screen.findByText('No slots for this vehicle type.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('vehicle=TWO_WHEELER'))
  })

  it.each([
    ['CLOSED', 'Closed at these times — check the opening hours.'],
    ['BLOCKED', 'Not available at these times.'],
    ['FULLY_BOOKED', 'All slots are taken for these times.'],
  ] as const)('shows the %s message', async (reason, message) => {
    mock.onGet('/listings/7/quote').reply(200, { ...available, available: false, reason, freeSlots: 0 })
    renderApp(URL_7)

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
  })

  it('shows INVALID_TIME_RANGE from the server inline', async () => {
    mock.onGet('/listings/7/quote').reply(400, { code: 'INVALID_TIME_RANGE', detail: 'Minimum booking is 1 hour' })
    renderApp(URL_7)

    expect(await screen.findByRole('alert')).toHaveTextContent('Minimum booking is 1 hour')
    expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
  })

  it('debounces edits to the times before asking for a new quote', async () => {
    renderApp(URL_7)
    await screen.findByText('3 slots free')

    const from = screen.getByLabelText('From')
    const next = new Date(new Date(start).getTime() + 30 * 60_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const local = `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}T${pad(next.getHours())}:${pad(next.getMinutes())}`
    await userEvent.clear(from)
    await userEvent.type(from, local)

    await waitFor(() => expect(quoteCalls()).toHaveLength(2))
    expect(quoteCalls()[1].params.start).toBe(next.toISOString())
  })

  it('sends a signed-out visitor to log in and back to this page', async () => {
    renderApp(URL_7)
    await screen.findByText('3 slots free')

    await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

    expect(await screen.findByRole('heading', { name: /log in/i })).toBeInTheDocument()
  })

  it('carries the chosen times in the next parameter', async () => {
    renderApp(URL_7, undefined, <LocationProbe />)
    await screen.findByText('3 slots free')

    await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/login?next=%2Flistings%2F7%3Fstart%3D'))
    expect(screen.getByTestId('loc')).toHaveTextContent('%26vehicle%3DFOUR_WHEELER')
  })

  it('tells a signed-in driver that online booking is coming', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, driver)
    renderApp(URL_7)

    expect(await screen.findByText('Online booking opens in the next update.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
  })

  it('asks owners to sign in as a driver', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, owner)
    renderApp(URL_7)

    expect(await screen.findByText('Sign in as a driver to book.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
  })

  it('shows not found for an unknown listing', async () => {
    mock.onGet('/listings/99').reply(404, { code: 'NOT_FOUND', detail: 'Listing not found' })
    renderApp('/listings/99')

    expect(await screen.findByText(/this spot is empty/i)).toBeInTheDocument()
  })

  it('opens the photo gallery lightbox, steps through the photos and closes on Escape', async () => {
    renderApp(URL_7)
    await screen.findByRole('heading', { level: 1, name: 'Metro Hub Parking' })

    await userEvent.click(screen.getByRole('button', { name: /open photo 1/i }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('img')).toHaveAttribute('alt', 'Metro Hub Parking photo 1')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Next' }))
    expect(within(dialog).getByRole('img')).toHaveAttribute('alt', 'Metro Hub Parking photo 2')
    expect(within(dialog).getByRole('img')).toHaveAttribute('src', '/files/b.jpg')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Previous' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Previous' }))
    expect(within(dialog).getByRole('img')).toHaveAttribute('alt', 'Metro Hub Parking photo 3')

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('opens the lightbox on the clicked thumbnail', async () => {
    renderApp(URL_7)
    await screen.findByRole('heading', { level: 1, name: 'Metro Hub Parking' })

    await userEvent.click(screen.getByRole('button', { name: /view photo 3/i }))

    expect(within(screen.getByRole('dialog')).getByRole('img')).toHaveAttribute('alt', 'Metro Hub Parking photo 3')
  })

  it('shows a placeholder when the listing has no photos', async () => {
    mock.onGet('/listings/7').reply(200, { ...listing, photos: [] })
    renderApp(URL_7)

    expect(await screen.findByText('No photos yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /open photo/i })).not.toBeInTheDocument()
  })
})
