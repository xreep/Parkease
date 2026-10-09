import '@testing-library/jest-dom/vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto, BookingSummaryDto } from '../../lib/bookings'
import { formatDateTime } from '../../lib/format'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }

const START = '2026-10-12T04:30:00Z'
const END = '2026-10-12T06:30:00Z'

function booking(overrides: Partial<BookingDetailDto> = {}): BookingDetailDto {
  return {
    id: 91, bookingCode: 'PE-8KQ2M4', status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
    coverPhotoUrl: '/files/a.jpg', startTime: START, endTime: END, vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
    totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z', address: 'FC Road, Shivajinagar', lat: 18.5, lng: 73.8, slotLabel: 'A-3',
    pricingMode: 'HOURLY', pricingBreakdown: '2 hours at ₹40/hr', baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 0,
    holdExpiresAt: null, approvalDeadline: null, confirmedAt: '2026-10-09T08:02:00Z', cancelReason: null, cancelledBy: null,
    paymentStatus: 'CAPTURED', invoiceNumber: 'PE-INV-0042', autoApprove: true, ownerFirstName: 'Priya',
    events: [
      { fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-09T08:00:00Z' },
      { fromStatus: 'PENDING_PAYMENT', toStatus: 'CONFIRMED', actor: 'SYSTEM', note: null, at: '2026-10-09T08:02:00Z' },
    ],
    ...overrides,
  }
}

function summary(overrides: Partial<BookingSummaryDto> = {}): BookingSummaryDto {
  const { id, bookingCode, status, listingId, listingTitle, cityName, coverPhotoUrl, startTime, endTime, vehicleType, plateNumber, totalAmount, createdAt } =
    booking()
  return { id, bookingCode, status, listingId, listingTitle, cityName, coverPhotoUrl, startTime, endTime, vehicleType, plateNumber, totalAmount, createdAt, ...overrides }
}

const page = (content: BookingSummaryDto[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('booking detail', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('shows the confirmation banner, booking code and a QR code for a confirmed booking', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91?new=1')

    expect(await screen.findByText("You're all set! Show this QR code at the parking entrance.")).toBeInTheDocument()
    expect(screen.getByText('PE-8KQ2M4')).toBeInTheDocument()
    expect(screen.getByText('Confirmed', { selector: 'span' })).toBeInTheDocument()
    const qr = screen.getByRole('region', { name: 'Booking QR code' })
    expect(qr.querySelector('svg')).not.toBeNull()
    expect(screen.getByRole('link', { name: 'Metro Hub Parking' })).toHaveAttribute('href', '/listings/7')
    expect(screen.getByText('MH12AB1234')).toBeInTheDocument()
    expect(screen.getByText('A-3')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Get directions/ })).toHaveAttribute(
      'href',
      'https://www.google.com/maps/dir/?api=1&destination=18.5,73.8',
    )
    expect(screen.getByText('₹89.44')).toBeInTheDocument()
  })

  it('has no banner without ?new=1', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91')

    expect(await screen.findByText('PE-8KQ2M4')).toBeInTheDocument()
    expect(screen.queryByText(/You're all set/)).not.toBeInTheDocument()
  })

  it('tells the driver when the owner has to approve, with the deadline and no QR code', async () => {
    const deadline = '2026-10-10T08:00:00Z'
    mock.onGet('/bookings/91').reply(200, booking({
      status: 'AWAITING_APPROVAL', approvalDeadline: deadline, invoiceNumber: null, autoApprove: false,
    }))
    renderApp('/driver/bookings/91?new=1')

    expect(
      await screen.findByText(
        `Request sent. The owner has until ${formatDateTime(deadline)} to approve. You'll be refunded in full if they don't.`,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Waiting for owner', { selector: 'span' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Booking QR code' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download receipt' })).not.toBeInTheDocument()
  })

  it('refreshes every 15 seconds while the booking is waiting on the owner', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking({ status: 'AWAITING_APPROVAL', approvalDeadline: '2026-10-10T08:00:00Z' }))
    mock.onGet('/bookings/91').reply(200, booking())
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderApp('/driver/bookings/91')

    expect(await screen.findByText('Waiting for owner', { selector: 'span' })).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000)
    })
    expect(await screen.findByText('Confirmed', { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Booking QR code' })).toBeInTheDocument()
  })

  it('stops polling once the booking reaches a final status', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderApp('/driver/bookings/91')

    expect(await screen.findByText('Confirmed', { selector: 'span' })).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(mock.history.get.filter((r) => r.url === '/bookings/91')).toHaveLength(1)
  })

  it('offers to complete payment while the hold is valid', async () => {
    mock.onGet('/bookings/91').reply(200, booking({
      status: 'PENDING_PAYMENT', holdExpiresAt: new Date(Date.now() + 5 * 60_000).toISOString(), invoiceNumber: null, paymentStatus: 'CREATED',
    }))
    renderApp('/driver/bookings/91')

    expect(await screen.findByRole('link', { name: 'Complete payment' })).toHaveAttribute('href', '/checkout/91')
  })

  it('does not offer payment once the hold has passed', async () => {
    mock.onGet('/bookings/91').reply(200, booking({
      status: 'PENDING_PAYMENT', holdExpiresAt: new Date(Date.now() - 60_000).toISOString(), invoiceNumber: null,
    }))
    renderApp('/driver/bookings/91')

    expect(await screen.findByText('Awaiting payment', { selector: 'span' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Complete payment' })).not.toBeInTheDocument()
  })

  it('lists the status timeline, a refund and the cancel reason', async () => {
    mock.onGet('/bookings/91').reply(200, booking({
      status: 'CANCELLED', refundAmount: 89.44, cancelReason: 'Owner unavailable', cancelledBy: 'OWNER',
      events: [
        { fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-09T08:00:00Z' },
        { fromStatus: 'PENDING_PAYMENT', toStatus: 'CONFIRMED', actor: 'SYSTEM', note: null, at: '2026-10-09T08:02:00Z' },
        { fromStatus: 'CONFIRMED', toStatus: 'CANCELLED', actor: 'OWNER', note: 'Owner unavailable', at: '2026-10-09T09:00:00Z' },
      ],
    }))
    renderApp('/driver/bookings/91')

    const timeline = await screen.findByRole('list', { name: 'Booking timeline' })
    const items = within(timeline).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent(`Awaiting payment · ${formatDateTime('2026-10-09T08:00:00Z')}`)
    expect(items[1]).toHaveTextContent(`Confirmed · ${formatDateTime('2026-10-09T08:02:00Z')}`)
    expect(items[2]).toHaveTextContent(`Cancelled · ${formatDateTime('2026-10-09T09:00:00Z')}`)
    expect(screen.getByText('Refunded ₹89.44')).toBeInTheDocument()
    expect(screen.getAllByText(/Owner unavailable/).length).toBeGreaterThan(0)
  })

  it('downloads the receipt as a PDF named after the invoice', async () => {
    const pdf = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/receipt').reply(200, pdf)
    const createObjectURL = vi.fn(() => 'blob:receipt')
    const revokeObjectURL = vi.fn()
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    onTestFinished(() => {
      Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
    })
    let downloaded: { download: string; href: string } | undefined
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = { download: this.download, href: this.href }
    })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('button', { name: 'Download receipt' }))

    await waitFor(() => expect(downloaded).toEqual({ download: 'ParkEase-PE-INV-0042.pdf', href: 'blob:receipt' }))
    const call = mock.history.get.find((r) => r.url === '/bookings/91/receipt')
    expect(call?.responseType).toBe('blob')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).not.toHaveBeenCalled() // revoked a moment after the click, not during it
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:receipt')
  })

  it('shows an error when the receipt cannot be downloaded', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/receipt').reply(409, new Blob([JSON.stringify({ code: 'NOT_PAID', detail: 'Receipt is only available for paid bookings' })]))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('button', { name: 'Download receipt' }))

    expect(await screen.findByText('Receipt is only available for paid bookings')).toBeInTheDocument()
  })

  it('copies the booking code', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()

    await user.click(await screen.findByRole('button', { name: 'Copy code' }))

    expect(writeText).toHaveBeenCalledWith('PE-8KQ2M4')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('reports a missing booking', async () => {
    mock.onGet('/bookings/404').reply(404, { code: 'NOT_FOUND', detail: 'Booking not found' })
    renderApp('/driver/bookings/404')

    expect(await screen.findByText('Booking not found')).toBeInTheDocument()
  })
})

describe('my bookings', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
  })

  afterEach(() => mock.restore())

  const viewsRequested = () => mock.history.get.filter((r) => r.url === '/bookings').map((r) => r.params.view)

  it('lists upcoming bookings first, then past ones when the tab changes', async () => {
    mock.onGet('/bookings', { params: { view: 'upcoming', page: 0, size: 20 } }).reply(200, page([summary()]))
    mock.onGet('/bookings', { params: { view: 'past', page: 0, size: 20 } }).reply(
      200,
      page([summary({ id: 12, bookingCode: 'PE-PAST01', status: 'COMPLETED', listingTitle: 'Old Lot' })]),
    )
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    const card = await screen.findByRole('article', { name: 'PE-8KQ2M4' })
    expect(within(card).getByText('Confirmed')).toBeInTheDocument()
    expect(within(card).getByText('Metro Hub Parking')).toBeInTheDocument()
    expect(within(card).getByText('MH12AB1234')).toBeInTheDocument()
    expect(within(card).getByText('₹89.44')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'View' })).toHaveAttribute('href', '/driver/bookings/91')
    expect(screen.getByRole('tab', { name: 'Upcoming' })).toHaveAttribute('aria-selected', 'true')

    await user.click(screen.getByRole('tab', { name: 'Past' }))

    expect(await screen.findByRole('article', { name: 'PE-PAST01' })).toBeInTheDocument()
    expect(screen.getByRole('tabpanel', { name: 'Past' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'PE-8KQ2M4' })).not.toBeInTheDocument()
    expect(viewsRequested()).toEqual(['upcoming', 'past'])
  })

  it('does not show the previous tab\'s bookings while the next tab loads', async () => {
    mock.onGet('/bookings', { params: { view: 'upcoming', page: 0, size: 20 } }).reply(200, page([summary()]))
    mock.onGet('/bookings', { params: { view: 'past', page: 0, size: 20 } }).reply(
      () => new Promise(() => {}),
    )
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    await screen.findByRole('article', { name: 'PE-8KQ2M4' })
    await user.click(screen.getByRole('tab', { name: 'Past' }))

    await waitFor(() => expect(screen.queryByRole('article', { name: 'PE-8KQ2M4' })).not.toBeInTheDocument())
    expect(screen.queryByText('No past bookings yet.')).not.toBeInTheDocument()
  })

  it('wires tabs to their panel and moves between tabs with the keyboard', async () => {
    mock.onGet('/bookings').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    const upcoming = await screen.findByRole('tab', { name: 'Upcoming' })
    expect(upcoming.getAttribute('aria-controls')).toBe(screen.getByRole('tabpanel').id)
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', upcoming.id)

    upcoming.focus()
    await user.keyboard('{End}')
    expect(await screen.findByRole('tab', { name: 'Past' })).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Home}')
    expect(await screen.findByRole('tab', { name: 'Upcoming' })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows the empty states', async () => {
    mock.onGet('/bookings').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    expect(await screen.findByText('No upcoming bookings.')).toBeInTheDocument()
    expect(within(screen.getByRole('tabpanel')).getByRole('link', { name: 'Find parking' })).toHaveAttribute('href', '/search')

    await user.click(screen.getByRole('tab', { name: 'Past' }))
    expect(await screen.findByText('No past bookings yet.')).toBeInTheDocument()
  })

  it('pages through results', async () => {
    mock.onGet('/bookings', { params: { view: 'upcoming', page: 0, size: 20 } }).reply(200, page([summary()], 2, 0))
    mock.onGet('/bookings', { params: { view: 'upcoming', page: 1, size: 20 } }).reply(
      200,
      page([summary({ id: 92, bookingCode: 'PE-SECOND' })], 2, 1),
    )
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    await screen.findByRole('article', { name: 'PE-8KQ2M4' })
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('article', { name: 'PE-SECOND' })).toBeInTheDocument()
  })

  it('shows an error when the list cannot be loaded', async () => {
    mock.onGet('/bookings').reply(500, { code: 'INTERNAL', detail: 'Server exploded' })
    renderApp('/driver/bookings')

    expect(await screen.findByText('Server exploded')).toBeInTheDocument()
  })
})
