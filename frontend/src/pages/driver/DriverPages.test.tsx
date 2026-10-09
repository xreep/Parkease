import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto, BookingSummaryDto } from '../../lib/bookings'
import type { DriverPaymentDto, DriverStatsDto } from '../../lib/driver'
import { formatDateTime } from '../../lib/format'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }

function summary(overrides: Partial<BookingSummaryDto> = {}): BookingSummaryDto {
  return {
    id: 91, bookingCode: 'PE-8KQ2M4', status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
    coverPhotoUrl: null, startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER',
    plateNumber: 'MH12AB1234', totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z', ...overrides,
  }
}

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

describe('driver booking tabs', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
  })

  afterEach(() => mock.restore())

  const views = () => mock.history.get.filter((r) => r.url === '/bookings').map((r) => r.params.view)

  it('has Upcoming, Active, Past and Cancelled tabs, Upcoming first', async () => {
    mock.onGet('/bookings').reply(200, page([]))
    renderApp('/driver/bookings')

    const tabs = await screen.findAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['Upcoming', 'Active', 'Past', 'Cancelled'])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    // The request goes out a moment after the tab renders.
    await waitFor(() => expect(views()).toEqual(['upcoming']))
  })

  it('keeps the tab in the URL and loads that view', async () => {
    mock.onGet('/bookings').reply((config) => [200, page(config.params.view === 'active' ? [summary({ id: 12, bookingCode: 'PE-ACT001', status: 'ACTIVE' })] : [])])
    const user = userEvent.setup()
    renderApp('/driver/bookings', undefined, <LocationProbe />)
    await screen.findByRole('tab', { name: 'Active' })
    expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings')
    expect(screen.getByTestId('loc')).not.toHaveTextContent('view=')

    await user.click(screen.getByRole('tab', { name: 'Active' }))

    expect(await screen.findByRole('article', { name: 'PE-ACT001' })).toBeInTheDocument()
    expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings?view=active')
    await user.click(screen.getByRole('tab', { name: 'Cancelled' }))
    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('?view=cancelled'))
    expect(views()).toEqual(['upcoming', 'active', 'cancelled'])
    expect(screen.getByRole('tabpanel', { name: 'Cancelled' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Upcoming' }))
    await waitFor(() => expect(screen.getByTestId('loc')).not.toHaveTextContent('view='))
  })

  it('opens on the tab named in the URL and falls back to Upcoming for an unknown one', async () => {
    mock.onGet('/bookings').reply(200, page([]))
    const first = renderApp('/driver/bookings?view=past')
    expect(await screen.findByRole('tab', { name: 'Past' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(views()).toEqual(['past']))
    first.unmount()

    renderApp('/driver/bookings?view=bogus')
    expect(await screen.findByRole('tab', { name: 'Upcoming' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(views().at(-1)).toBe('upcoming'))
  })

  it('has an empty state for every tab', async () => {
    mock.onGet('/bookings').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/driver/bookings')

    expect(await screen.findByText('No upcoming bookings.')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Active' }))
    expect(await screen.findByText('No active bookings right now.')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Past' }))
    expect(await screen.findByText('No past bookings yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Cancelled' }))
    expect(await screen.findByText('No cancelled bookings.')).toBeInTheDocument()
  })

  it('goes back to the first page when the tab changes', async () => {
    mock.onGet('/bookings').reply((config) => [200, page([summary({ id: 20 + config.params.page, bookingCode: `PE-P${config.params.page}${config.params.view.slice(0, 2).toUpperCase()}` })], 2, config.params.page)])
    const user = userEvent.setup()
    renderApp('/driver/bookings')
    await screen.findByRole('article', { name: 'PE-P0UP' })
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByRole('article', { name: 'PE-P1UP' })

    await user.click(screen.getByRole('tab', { name: 'Past' }))

    expect(await screen.findByRole('article', { name: 'PE-P0PA' })).toBeInTheDocument()
  })
})

const payment = (id: number, overrides: Partial<DriverPaymentDto> = {}): DriverPaymentDto => ({
  id, bookingId: 90 + id, bookingCode: `PE-PAY00${id}`, listingTitle: 'Metro Hub Parking', amount: 89.44, status: 'CAPTURED',
  refundAmount: 0, paidAt: '2026-10-09T08:02:00Z', invoiceNumber: `PE-INV-004${id}`, receiptAvailable: true, ...overrides,
})

describe('driver payments', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  const calls = () => mock.history.get.filter((r) => r.url === '/me/payments')

  it('is reachable from the driver navigation', async () => {
    mock.onGet('/me/payments').reply(200, page([]))
    mock.onGet('/bookings').reply(200, page([]))
    mock.onGet('/me/vehicles').reply(200, [])
    const user = userEvent.setup()
    renderApp('/driver')

    await user.click(await screen.findByRole('link', { name: 'Payments' }))

    expect(await screen.findByRole('heading', { name: 'Payments' })).toBeInTheDocument()
  })

  it('lists payments with amount, status, refund and a link to the booking', async () => {
    mock.onGet('/me/payments').reply(200, page([
      payment(1),
      payment(2, { status: 'PARTIALLY_REFUNDED', refundAmount: 40 }),
      payment(3, { status: 'REFUNDED', refundAmount: 89.44 }),
      payment(4, { status: 'FAILED', paidAt: null, invoiceNumber: null, receiptAvailable: false }),
    ]))
    renderApp('/driver/payments')

    const first = await screen.findByRole('article', { name: 'Payment for PE-PAY001' })
    expect(within(first).getByText('₹89.44')).toBeInTheDocument()
    expect(within(first).getByText('Paid')).toBeInTheDocument()
    expect(within(first).getByText('Metro Hub Parking')).toBeInTheDocument()
    expect(within(first).getByText(formatDateTime('2026-10-09T08:02:00Z'))).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: 'PE-PAY001' })).toHaveAttribute('href', '/driver/bookings/91')
    expect(within(first).queryByText(/Refunded/)).not.toBeInTheDocument()

    const partial = screen.getByRole('article', { name: 'Payment for PE-PAY002' })
    expect(within(partial).getByText('Partially refunded')).toBeInTheDocument()
    expect(within(partial).getByText('Refunded ₹40')).toBeInTheDocument()
    const refunded = screen.getByRole('article', { name: 'Payment for PE-PAY003' })
    expect(within(refunded).getByText('Refunded', { selector: 'span' })).toBeInTheDocument()
    expect(within(refunded).getByText('Refunded ₹89.44')).toBeInTheDocument()
    const failed = screen.getByRole('article', { name: 'Payment for PE-PAY004' })
    expect(within(failed).getByText('Failed')).toBeInTheDocument()
    expect(within(failed).queryByRole('button', { name: 'Download receipt' })).not.toBeInTheDocument()
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
  })

  it('downloads the receipt from the existing endpoint, named after the invoice', async () => {
    mock.onGet('/me/payments').reply(200, page([payment(1)]))
    mock.onGet('/bookings/91/receipt').reply(200, new Blob(['%PDF-1.4'], { type: 'application/pdf' }))
    const createObjectURL = vi.fn(() => 'blob:receipt')
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    onTestFinished(() => {
      Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
    })
    let downloaded: { download: string; href: string } | undefined
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = { download: this.download, href: this.href }
    })
    const user = userEvent.setup()
    renderApp('/driver/payments')

    const card = await screen.findByRole('article', { name: 'Payment for PE-PAY001' })
    await user.click(within(card).getByRole('button', { name: 'Download receipt' }))

    await waitFor(() => expect(downloaded).toEqual({ download: 'ParkEase-PE-INV-0041.pdf', href: 'blob:receipt' }))
    expect(mock.history.get.find((r) => r.url === '/bookings/91/receipt')?.responseType).toBe('blob')
  })

  it('shows why a receipt could not be downloaded', async () => {
    mock.onGet('/me/payments').reply(200, page([payment(1)]))
    mock.onGet('/bookings/91/receipt').reply(409, new Blob([JSON.stringify({ code: 'NOT_PAID', detail: 'Receipt is only available for paid bookings' })]))
    const user = userEvent.setup()
    renderApp('/driver/payments')

    await user.click(await screen.findByRole('button', { name: 'Download receipt' }))

    expect(await screen.findByText('Receipt is only available for paid bookings')).toBeInTheDocument()
  })

  it('pages through payments', async () => {
    mock.onGet('/me/payments').reply((config) => [200, page([payment(config.params.page + 1)], 2, config.params.page)])
    const user = userEvent.setup()
    renderApp('/driver/payments')

    await screen.findByRole('article', { name: 'Payment for PE-PAY001' })
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByRole('article', { name: 'Payment for PE-PAY002' })).toBeInTheDocument()
    expect(calls().at(-1)!.params).toEqual({ page: 1, size: 20 })
  })

  it('has an empty state and reports a failed load', async () => {
    mock.onGet('/me/payments').replyOnce(200, page([]))
    const { unmount } = renderApp('/driver/payments')
    expect(await screen.findByText('No payments yet.')).toBeInTheDocument()
    unmount()

    mock.onGet('/me/payments').reply(500, { code: 'INTERNAL', detail: 'Payments are down' })
    renderApp('/driver/payments')
    expect(await screen.findByText('Payments are down')).toBeInTheDocument()
  })
})

describe('driver home stats', () => {
  let mock: MockAdapter
  const stats: DriverStatsDto = { totalBookings: 14, completedBookings: 11, amountSpent: 2450, hoursParked: 38.5, pendingReviews: 2, reviewBookingId: 70 }

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    mock.onGet('/me/vehicles').reply(200, [])
    mock.onGet('/me/stats').reply(200, stats)
    mock.onGet('/bookings', { params: { view: 'upcoming', page: 0, size: 1 } }).reply(200, page([summary()]))
  })

  afterEach(() => mock.restore())

  it('shows the stat cards', async () => {
    renderApp('/driver')

    const total = await screen.findByRole('group', { name: 'Bookings' })
    expect(within(total).getByText('14')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Completed' })).getByText('11')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Spent' })).getByText('₹2,450')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Hours parked' })).getByText('38.5')).toBeInTheDocument()
  })

  it('still shows the next booking', async () => {
    renderApp('/driver')

    expect(await screen.findByText('PE-8KQ2M4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View booking' })).toHaveAttribute('href', '/driver/bookings/91')
  })

  it('prompts for pending reviews with a link to the booking that can be reviewed', async () => {
    renderApp('/driver')

    const prompt = await screen.findByRole('region', { name: 'Rate your parking' })
    expect(within(prompt).getByText('2 bookings are waiting for your review.')).toBeInTheDocument()
    expect(within(prompt).getByRole('link', { name: 'Rate your parking' })).toHaveAttribute('href', '/driver/bookings/70#review')
    // The id comes from the stats; no booking list is fetched to guess it.
    expect(mock.history.get.filter((r) => r.url === '/bookings').map((r) => r.params.view)).toEqual(['upcoming'])
  })

  it('says nothing without a booking to review, or without pending reviews', async () => {
    mock.onGet('/me/stats').reply(200, { ...stats, reviewBookingId: null })
    const first = renderApp('/driver')
    await screen.findByRole('group', { name: 'Bookings' })
    expect(screen.queryByRole('region', { name: 'Rate your parking' })).not.toBeInTheDocument()
    first.unmount()

    mock.onGet('/me/stats').reply(200, { ...stats, pendingReviews: 0, reviewBookingId: null })
    renderApp('/driver')
    await screen.findByRole('group', { name: 'Bookings' })
    expect(screen.queryByRole('region', { name: 'Rate your parking' })).not.toBeInTheDocument()
  })

  it('says so when the stats cannot be loaded, leaving the rest of the page', async () => {
    mock.onGet('/me/stats').reply(500, { code: 'INTERNAL', detail: 'boom' })
    renderApp('/driver')

    expect(await screen.findByText('Your stats are unavailable right now.')).toBeInTheDocument()
    expect(await screen.findByText('PE-8KQ2M4')).toBeInTheDocument()
  })
})

describe('booking timeline refunds', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
  })

  afterEach(() => mock.restore())

  it('labels refund events "Refund" instead of repeating the status', async () => {
    const detail = {
      ...summary({ status: 'CANCELLED' }), address: 'FC Road', lat: 18.5, lng: 73.8, slotLabel: 'A-3', pricingMode: 'HOURLY',
      pricingBreakdown: '2 hours', baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 80, holdExpiresAt: null,
      approvalDeadline: null, confirmedAt: null, cancelReason: null, cancelledBy: 'DRIVER', paymentStatus: 'PARTIALLY_REFUNDED',
      invoiceNumber: 'PE-INV-0042', autoApprove: true, ownerFirstName: 'Priya', reviewable: false, review: null,
      events: [
        { fromStatus: 'CONFIRMED', toStatus: 'CANCELLED', actor: 'DRIVER', note: null, at: '2026-10-09T09:00:00Z' },
        { fromStatus: 'CANCELLED', toStatus: 'CANCELLED', actor: 'SYSTEM', note: 'Refund of ₹80 issued', at: '2026-10-09T09:01:00Z' },
        { fromStatus: 'CANCELLED', toStatus: 'CANCELLED', actor: 'SYSTEM', note: 'Refund failed at the provider — retrying', at: '2026-10-09T09:02:00Z' },
      ],
    } as BookingDetailDto
    mock.onGet('/bookings/91').reply(200, detail)
    renderApp('/driver/bookings/91')

    const items = within(await screen.findByRole('list', { name: 'Booking timeline' })).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent(`Cancelled · ${formatDateTime('2026-10-09T09:00:00Z')}`)
    expect(items[1]).toHaveTextContent(`Refund · ${formatDateTime('2026-10-09T09:01:00Z')}`)
    expect(items[1]).toHaveTextContent('Refund of ₹80 issued')
    expect(items[2]).toHaveTextContent(`Refund · ${formatDateTime('2026-10-09T09:02:00Z')}`)
    expect(within(items[1]).queryByText(/^Cancelled/)).not.toBeInTheDocument()
  })

  it('treats a repeated status as a refund even when the note does not say so', async () => {
    const detail = {
      ...summary({ status: 'COMPLETED' }), address: 'FC Road', lat: 18.5, lng: 73.8, slotLabel: 'A-3', pricingMode: 'HOURLY',
      pricingBreakdown: '2 hours', baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 20, holdExpiresAt: null,
      approvalDeadline: null, confirmedAt: null, cancelReason: null, cancelledBy: null, paymentStatus: 'PARTIALLY_REFUNDED',
      invoiceNumber: 'PE-INV-0042', autoApprove: true, ownerFirstName: 'Priya', reviewable: false, review: null,
      events: [{ fromStatus: 'COMPLETED', toStatus: 'COMPLETED', actor: 'ADMIN', note: 'Goodwill', at: '2026-10-09T09:00:00Z' }],
    } as BookingDetailDto
    mock.onGet('/bookings/91').reply(200, detail)
    renderApp('/driver/bookings/91')

    const [item] = within(await screen.findByRole('list', { name: 'Booking timeline' })).getAllByRole('listitem')
    expect(item).toHaveTextContent('Refund')
    expect(item).toHaveTextContent('Goodwill')
  })
})
