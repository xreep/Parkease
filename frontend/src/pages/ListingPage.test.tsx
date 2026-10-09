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

  describe('reserving as a driver', () => {
    const car = { id: 11, type: 'FOUR_WHEELER', plateNumber: 'MH12AB1234', makeModel: 'Honda City', isDefault: true }
    const car2 = { id: 13, type: 'FOUR_WHEELER', plateNumber: 'MH14CD5678', makeModel: null, isDefault: false }
    const bike = { id: 12, type: 'TWO_WHEELER', plateNumber: 'MH12XY9876', makeModel: null, isDefault: false }

    const checkoutDto = {
      booking: { id: 91, listingId: 7, holdExpiresAt: new Date(Date.now() + 10 * 60_000).toISOString() },
      payment: { provider: 'MOCK', orderId: 'order_1', amount: 8944, currency: 'INR', keyId: null, name: 'ParkEase', description: 'Parking', prefill: { name: 'R', email: 'r@x.in', contact: null } },
    }

    beforeEach(() => {
      tokenStore.set('a', 'r')
      mock.onGet('/me').reply(200, driver)
      // The checkout page renders after a successful reservation; its content is not under test here.
      mock.onGet('/bookings/91/checkout').reply(410, { code: 'HOLD_EXPIRED', detail: 'Your reservation expired' })
      mock.onGet('/bookings/91').reply(404, { code: 'NOT_FOUND', detail: 'Booking not found' })
    })

    const bookingPosts = () => mock.history.post.filter((r) => r.url === '/bookings')

    it('reserves with the default vehicle and goes to checkout', async () => {
      mock.onGet('/me/vehicles').reply(200, [bike, car, car2])
      mock.onPost('/bookings').reply(201, checkoutDto)
      renderApp(URL_7, undefined, <LocationProbe />)
      await screen.findByText('3 slots free')

      const select = await screen.findByLabelText('Vehicle')
      expect(select).toHaveValue('11')
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['MH12AB1234 · Honda City', 'MH14CD5678'])
      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/checkout/91'))
      expect(JSON.parse(bookingPosts()[0].data)).toEqual({ listingId: 7, vehicleId: 11, start, end })
    })

    it('reserves the vehicle the driver picks', async () => {
      mock.onGet('/me/vehicles').reply(200, [car, car2])
      mock.onPost('/bookings').reply(201, checkoutDto)
      renderApp(URL_7)
      await screen.findByText('3 slots free')

      await userEvent.selectOptions(await screen.findByLabelText('Vehicle'), '13')
      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      await waitFor(() => expect(bookingPosts()).toHaveLength(1))
      expect(JSON.parse(bookingPosts()[0].data).vehicleId).toBe(13)
    })

    it('lists the vehicles of the chosen type and re-selects its default when the type changes', async () => {
      mock.onGet('/me/vehicles').reply(200, [car, bike])
      mock.onPost('/bookings').reply(201, checkoutDto)
      renderApp(URL_7)
      await screen.findByText('3 slots free')
      expect(await screen.findByLabelText('Vehicle')).toHaveValue('11')

      await userEvent.selectOptions(screen.getByLabelText('Vehicle type'), 'TWO_WHEELER')

      await waitFor(() => expect(screen.getByLabelText('Vehicle')).toHaveValue('12'))
      expect(screen.getAllByRole('option', { name: /MH12/ })).toHaveLength(1)
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))
      await waitFor(() => expect(bookingPosts()).toHaveLength(1))
      expect(JSON.parse(bookingPosts()[0].data).vehicleId).toBe(12)
    })

    it('asks a driver without vehicles to add one, then continues to checkout', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      mock.onPost('/me/vehicles').reply(201, car)
      mock.onPost('/bookings').reply(201, checkoutDto)
      renderApp(URL_7, undefined, <LocationProbe />)
      await screen.findByText('3 slots free')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())

      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      const dialog = within(screen.getByRole('dialog', { name: 'Add your vehicle' }))
      expect(bookingPosts()).toHaveLength(0)
      expect(dialog.getByLabelText('Vehicle type')).toHaveValue('FOUR_WHEELER')
      expect(dialog.queryByLabelText('Use as default')).not.toBeInTheDocument()
      await userEvent.type(dialog.getByLabelText('Number plate'), 'mh 12 ab 1234')
      await userEvent.click(dialog.getByRole('button', { name: 'Add vehicle' }))

      await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/checkout/91'))
      expect(JSON.parse(mock.history.post.find((r) => r.url === '/me/vehicles')!.data)).toMatchObject({ type: 'FOUR_WHEELER', plateNumber: 'MH12AB1234' })
      expect(JSON.parse(bookingPosts()[0].data).vehicleId).toBe(11)
    })

    it('hints to add a vehicle of the chosen type, instead of opening the dialog on Reserve', async () => {
      mock.onGet('/me/vehicles').reply(200, [bike])
      renderApp(URL_7)
      await screen.findByText('3 slots free')

      expect(await screen.findByText('You have no car saved — add one or switch vehicle type')).toBeInTheDocument()
      expect(screen.queryByLabelText('Vehicle')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Add a car' }))

      expect(within(screen.getByRole('dialog', { name: 'Add your vehicle' })).getByLabelText('Vehicle type')).toHaveValue('FOUR_WHEELER')
      expect(bookingPosts()).toHaveLength(0)
    })

    it('adding a vehicle from the hint does not reserve, and enables Reserve', async () => {
      mock.onGet('/me/vehicles').replyOnce(200, [bike]).onGet('/me/vehicles').reply(200, [bike, car])
      mock.onPost('/me/vehicles').reply(201, car)
      renderApp(URL_7)
      await userEvent.click(await screen.findByRole('button', { name: 'Add a car' }))

      const dialog = within(screen.getByRole('dialog'))
      await userEvent.type(dialog.getByLabelText('Number plate'), 'MH12AB1234')
      await userEvent.click(dialog.getByRole('button', { name: 'Add vehicle' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(bookingPosts()).toHaveLength(0)
      expect(await screen.findByLabelText('Vehicle')).toHaveValue('11')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())
    })

    it('starts from the type of the default vehicle when the URL names none (a driver with only a bike)', async () => {
      mock.onGet('/me/vehicles').reply(200, [bike])
      mock.onPost('/bookings').reply(201, checkoutDto)
      renderApp('/listings/7')

      await waitFor(() => expect(screen.getByLabelText('Vehicle type')).toHaveValue('TWO_WHEELER'))
      expect(await screen.findByLabelText('Vehicle')).toHaveValue('12')
      expect(screen.queryByText(/You have no/)).not.toBeInTheDocument()
      await waitFor(() => expect(quoteCalls().at(-1)!.params.vehicleType).toBe('TWO_WHEELER'))
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      await waitFor(() => expect(bookingPosts()).toHaveLength(1))
      expect(JSON.parse(bookingPosts()[0].data).vehicleId).toBe(12)
    })

    it("starts from the default vehicle's type even when it isn't the first vehicle", async () => {
      mock.onGet('/me/vehicles').reply(200, [{ ...car, isDefault: false }, { ...bike, isDefault: true }])
      renderApp('/listings/7')

      await waitFor(() => expect(screen.getByLabelText('Vehicle type')).toHaveValue('TWO_WHEELER'))
    })

    it('keeps the type from the URL even when the default vehicle is of another type', async () => {
      mock.onGet('/me/vehicles').reply(200, [{ ...bike, isDefault: true }])
      renderApp(URL_7)

      await screen.findByText('You have no car saved — add one or switch vehicle type')
      expect(screen.getByLabelText('Vehicle type')).toHaveValue('FOUR_WHEELER')
    })

    it('keeps the listing-based default type when the driver has no vehicles', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp('/listings/7')
      await screen.findByText('3 slots free')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())

      expect(screen.getByLabelText('Vehicle type')).toHaveValue('FOUR_WHEELER')
    })

    it('sends one booking request when Reserve is double-clicked', async () => {
      mock.onGet('/me/vehicles').reply(200, [car])
      mock.onPost('/bookings').reply(() => new Promise((resolve) => setTimeout(() => resolve([201, checkoutDto]), 50)))
      renderApp(URL_7)
      await screen.findByLabelText('Vehicle')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())

      await userEvent.dblClick(screen.getByRole('button', { name: 'Reserve' }))

      await waitFor(() => expect(bookingPosts()).toHaveLength(1))
      await new Promise((r) => setTimeout(r, 100))
      expect(bookingPosts()).toHaveLength(1)
    })

    it('closes the add-vehicle dialog without reserving when cancelled', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp(URL_7)
      await screen.findByText('3 slots free')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled())
      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(bookingPosts()).toHaveLength(0)
    })

    it.each([
      ['SLOT_UNAVAILABLE', 409, 'Sorry, that slot was just taken. Try different times.'],
      ['TOO_MANY_HOLDS', 409, 'You have unpaid reservations. Complete or wait for them to expire.'],
      ['LISTING_UNAVAILABLE', 409, 'This listing is not available for booking'],
    ])('explains %s', async (code, status, message) => {
      mock.onGet('/me/vehicles').reply(200, [car])
      mock.onPost('/bookings').reply(status, { code, detail: 'This listing is not available for booking' })
      renderApp(URL_7, undefined, <LocationProbe />)
      await screen.findByText('3 slots free')
      await screen.findByLabelText('Vehicle')

      await userEvent.click(screen.getByRole('button', { name: 'Reserve' }))

      expect(await screen.findByText(message)).toBeInTheDocument()
      expect(screen.getByTestId('loc')).not.toHaveTextContent('/checkout')
      expect(screen.getByRole('button', { name: 'Reserve' })).toBeEnabled()
    })

    it('does not reserve while the times are still being re-quoted', async () => {
      mock.onGet('/me/vehicles').reply(200, [car])
      renderApp(URL_7)
      await screen.findByText('3 slots free')
      await screen.findByLabelText('Vehicle')

      const from = screen.getByLabelText('From')
      await userEvent.clear(from)

      expect(screen.getByRole('button', { name: 'Reserve' })).toBeDisabled()
    })
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
