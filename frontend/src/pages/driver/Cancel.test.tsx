import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto, CancellationPreview } from '../../lib/bookings'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }

function booking(overrides: Partial<BookingDetailDto> = {}): BookingDetailDto {
  return {
    id: 91, bookingCode: 'PE-8KQ2M4', status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
    coverPhotoUrl: null, startTime: new Date(Date.now() + 30 * 3_600_000).toISOString(), endTime: new Date(Date.now() + 32 * 3_600_000).toISOString(),
    vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234', totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z',
    address: 'FC Road, Shivajinagar', lat: 18.5, lng: 73.8, slotLabel: 'A-3', pricingMode: 'HOURLY', pricingBreakdown: '2 hours at ₹40/hr',
    baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 0, holdExpiresAt: null, approvalDeadline: null,
    confirmedAt: '2026-10-09T08:02:00Z', cancelReason: null, cancelledBy: null, paymentStatus: 'CAPTURED', invoiceNumber: 'PE-INV-0042',
    autoApprove: true, ownerFirstName: 'Priya',
    events: [{ fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-09T08:00:00Z' }],
    ...overrides,
  }
}

function preview(overrides: Partial<CancellationPreview> = {}): CancellationPreview {
  return {
    cancellable: true, reason: null, policy: 'MODERATE', refundPercent: 100, refundAmount: 80, nonRefundableAmount: 9.44,
    hoursBeforeStart: 30, ...overrides,
  }
}

describe('driver cancellation', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    mock.onGet('/notifications/unread-count').reply(200, { count: 0 })
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  async function openDialog(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Cancel booking' }))
    return screen.findByRole('dialog', { name: 'Cancel this booking?' })
  }

  it('offers cancelling for pending, awaiting and future confirmed bookings only', async () => {
    const cases: [Partial<BookingDetailDto>, boolean][] = [
      [{ status: 'PENDING_PAYMENT', invoiceNumber: null }, true],
      [{ status: 'AWAITING_APPROVAL', invoiceNumber: null }, true],
      [{ status: 'CONFIRMED' }, true],
      [{ status: 'CONFIRMED', startTime: new Date(Date.now() - 600_000).toISOString() }, false],
      [{ status: 'ACTIVE' }, false],
      [{ status: 'COMPLETED' }, false],
      [{ status: 'CANCELLED', cancelledBy: 'DRIVER' }, false],
    ]
    for (const [overrides, shown] of cases) {
      mock.onGet('/bookings/91').reply(200, booking(overrides))
      const { unmount } = renderApp('/driver/bookings/91')
      await screen.findByText('PE-8KQ2M4')
      if (shown) expect(screen.getByRole('button', { name: 'Cancel booking' })).toBeInTheDocument()
      else expect(screen.queryByRole('button', { name: 'Cancel booking' })).not.toBeInTheDocument()
      unmount()
    }
  })

  it('says nothing has been charged for a booking awaiting payment', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ status: 'PENDING_PAYMENT', invoiceNumber: null }))
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({ policy: null, refundAmount: 0, nonRefundableAmount: 0 }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText('Nothing has been charged yet.')).toBeInTheDocument()
    expect(within(dialog).queryByText(/Non-refundable/)).not.toBeInTheDocument()
  })

  it('promises a full refund for a request the owner has not accepted', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ status: 'AWAITING_APPROVAL', invoiceNumber: null }))
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({ policy: null, refundAmount: 89.44, nonRefundableAmount: 0 }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText("You'll get ₹89.44 back (100%)")).toBeInTheDocument()
    expect(within(dialog).queryByText(/Non-refundable/)).not.toBeInTheDocument()
  })

  it('shows a full refund with the non-refundable fee and GST for a confirmed booking', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText("You'll get ₹80 back (100%)")).toBeInTheDocument()
    expect(within(dialog).getByText('Non-refundable: ₹9.44 (platform fee and GST are not refunded)')).toBeInTheDocument()
    expect(within(dialog).getByText(/Moderate policy: full refund of the parking charge up to 24 hours/)).toBeInTheDocument()
  })

  it('shows a half refund', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({ refundPercent: 50, refundAmount: 40, nonRefundableAmount: 49.44 }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText("You'll get ₹40 back (50%)")).toBeInTheDocument()
    expect(within(dialog).getByText('Non-refundable: ₹49.44 (platform fee and GST are not refunded)')).toBeInTheDocument()
  })

  it('names the policy when no refund applies', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({ policy: 'STRICT', refundPercent: 0, refundAmount: 0, nonRefundableAmount: 89.44 }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText('No refund applies — Strict policy')).toBeInTheDocument()
    expect(within(dialog).getByText('Non-refundable: ₹89.44 (platform fee and GST are not refunded)')).toBeInTheDocument()
    expect(within(dialog).getByText(/Strict policy: 50% refund/)).toBeInTheDocument()
  })

  it('cancels with the optional reason, announces the refund and refreshes', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview())
    mock.onPost('/bookings/91/cancel').reply(200, booking({ status: 'CANCELLED', cancelledBy: 'DRIVER' }))
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/driver/bookings/91', queryClient)

    const dialog = await openDialog(user)
    await within(dialog).findByText("You'll get ₹80 back (100%)")
    await user.type(within(dialog).getByLabelText('Reason (optional)'), 'Plans changed')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking cancelled — ₹80 will be refunded'))
    expect(JSON.parse(mock.history.post.find((r) => r.url === '/bookings/91/cancel')!.data)).toEqual({ reason: 'Plans changed' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const keys = invalidate.mock.calls.map(([filters]) => (filters as { queryKey: unknown[] }).queryKey[0])
    expect(keys).toEqual(expect.arrayContaining(['booking', 'bookings', 'quote', 'search', 'notifications']))
  })

  it('cancels without a reason and without a refund line for an unpaid booking', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ status: 'PENDING_PAYMENT', invoiceNumber: null }))
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({ policy: null, refundAmount: 0, nonRefundableAmount: 0 }))
    mock.onPost('/bookings/91/cancel').reply(200, booking({ status: 'CANCELLED', cancelledBy: 'DRIVER' }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    await within(dialog).findByText('Nothing has been charged yet.')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking cancelled'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({})
  })

  it('keeps the booking when the driver changes their mind', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    await within(dialog).findByText("You'll get ₹80 back (100%)")
    await user.click(within(dialog).getByRole('button', { name: 'Keep booking' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('shows the server message when the booking can no longer be cancelled', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').replyOnce(200, preview())
    mock.onPost('/bookings/91/cancel').reply(409, { code: 'NOT_CANCELLABLE', detail: "Bookings can't be cancelled once they've started" })
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    await within(dialog).findByText("You'll get ₹80 back (100%)")
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    expect(await within(dialog).findByText("Bookings can't be cancelled once they've started")).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: 'Cancel this booking?' })).toBeInTheDocument()
  })

  it('explains why a booking cannot be cancelled when the preview says so', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onGet('/bookings/91/cancellation-preview').reply(200, preview({
      cancellable: false, reason: "Bookings can't be cancelled once they've started", policy: null, refundPercent: 0, refundAmount: 0, nonRefundableAmount: 0,
    }))
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(user)
    expect(await within(dialog).findByText("Bookings can't be cancelled once they've started")).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Cancel booking' })).not.toBeInTheDocument()
  })

  it('shows the active and completed banners', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking({ status: 'ACTIVE' }))
    const { unmount } = renderApp('/driver/bookings/91')
    expect(await screen.findByText('Your parking time is active — show your QR code at the entrance.')).toBeInTheDocument()
    unmount()

    mock.onGet('/bookings/91').reply(200, booking({ status: 'COMPLETED' }))
    renderApp('/driver/bookings/91')
    expect(await screen.findByText('Completed — thanks for parking with ParkEase.')).toBeInTheDocument()
  })

  it('says who cancelled', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking({ status: 'CANCELLED', cancelledBy: 'DRIVER' }))
    const { unmount } = renderApp('/driver/bookings/91')
    expect(await screen.findByText('Cancelled by you')).toBeInTheDocument()
    unmount()

    mock.onGet('/bookings/91').reply(200, booking({ status: 'CANCELLED', cancelledBy: 'OWNER', cancelReason: 'Space is closed' }))
    renderApp('/driver/bookings/91')
    expect(await screen.findByText('Cancelled by the owner: Space is closed')).toBeInTheDocument()
  })

  it('marks an active booking in the list and on the dashboard', async () => {
    const { id, bookingCode, status, listingId, listingTitle, cityName, coverPhotoUrl, startTime, endTime, vehicleType, plateNumber, totalAmount, createdAt } =
      booking({ status: 'ACTIVE' })
    const summary = { id, bookingCode, status, listingId, listingTitle, cityName, coverPhotoUrl, startTime, endTime, vehicleType, plateNumber, totalAmount, createdAt }
    mock.onGet('/bookings').reply(200, { content: [summary], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    mock.onGet('/vehicles').reply(200, [])
    const { unmount } = renderApp('/driver/bookings')
    const card = await screen.findByRole('article', { name: 'PE-8KQ2M4' })
    expect(within(card).getByText('Active', { selector: 'span' })).toBeInTheDocument()
    expect(within(card).getByText('Active now — show your QR code at the entrance.')).toBeInTheDocument()
    unmount()

    renderApp('/driver')
    expect(await screen.findByText('Your parking time is active.')).toBeInTheDocument()
  })
})
