import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto } from '../../lib/bookings'
import type { Dispute, DisputeSummary } from '../../lib/disputes'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }

function booking(overrides: Partial<BookingDetailDto> = {}): BookingDetailDto {
  return {
    id: 91, bookingCode: 'PE-8KQ2M4', status: 'COMPLETED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
    coverPhotoUrl: '/files/a.jpg', startTime: '2026-10-05T04:30:00Z', endTime: '2026-10-05T06:30:00Z', vehicleType: 'FOUR_WHEELER',
    plateNumber: 'MH12AB1234', totalAmount: 89.44, createdAt: '2026-10-04T08:00:00Z', address: 'FC Road, Shivajinagar', lat: 18.5, lng: 73.8,
    slotLabel: 'A-3', pricingMode: 'HOURLY', pricingBreakdown: '2 hours at ₹40/hr', baseAmount: 80, platformFee: 8, gstAmount: 1.44,
    refundAmount: 0, holdExpiresAt: null, approvalDeadline: null, confirmedAt: '2026-10-04T08:02:00Z', cancelReason: null, cancelledBy: null,
    paymentStatus: 'CAPTURED', invoiceNumber: 'PE-INV-0042', autoApprove: true, ownerFirstName: 'Priya',
    events: [{ fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-04T08:00:00Z' }],
    reviewable: false, review: null, disputes: [], disputable: true,
    ...overrides,
  }
}

const summary = (id: number, overrides: Partial<DisputeSummary> = {}): DisputeSummary => ({
  id, bookingId: 91, bookingCode: 'PE-8KQ2M4', listingTitle: 'Metro Hub Parking', category: 'NO_ACCESS', status: 'OPEN',
  createdAt: '2026-10-06T08:00:00Z', resolvedAt: null, ...overrides,
})

const dispute = (id: number, overrides: Partial<Dispute> = {}): Dispute => ({
  ...summary(id), description: 'The gate was locked and nobody answered', raisedByName: 'Rahul Verma', ownerResponse: null,
  ownerRespondedAt: null, resolution: null, resolutionAmount: null, adminNotes: null, refundableRemaining: null, ...overrides,
})

const page = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

describe('driver problem reports', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  const bookingGets = () => mock.history.get.filter((r) => r.url === '/bookings/91')

  async function openDialog(u: ReturnType<typeof userEvent.setup>) {
    await u.click(await screen.findByRole('button', { name: 'Report a problem' }))
    return within(await screen.findByRole('dialog', { name: 'Report a problem' }))
  }

  it('reports a problem with a category and a description, then shows it on the booking', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking())
    mock.onGet('/bookings/91').reply(200, booking({ disputable: false, disputes: [summary(5)] }))
    mock.onPost('/bookings/91/disputes').reply(201, dispute(5))
    const u = userEvent.setup()
    renderApp('/driver/bookings/91')

    const dialog = await openDialog(u)
    await u.selectOptions(dialog.getByLabelText('What went wrong?'), 'No access')
    await u.type(dialog.getByLabelText('Describe the problem'), 'The gate was locked and nobody answered')
    await u.click(dialog.getByRole('button', { name: 'Send report' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Report sent'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ category: 'NO_ACCESS', description: 'The gate was locked and nobody answered' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const card = within(await screen.findByRole('region', { name: 'Problems reported' }))
    expect(card.getByRole('link', { name: 'No access' })).toHaveAttribute('href', '/driver/disputes/5')
    expect(card.getByText('Open')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Report a problem' })).not.toBeInTheDocument()
  })

  it('validates the description length', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    const u = userEvent.setup()
    renderApp('/driver/bookings/91')
    const dialog = await openDialog(u)

    await u.type(dialog.getByLabelText('Describe the problem'), 'too short')
    await u.click(dialog.getByRole('button', { name: 'Send report' }))
    expect(await dialog.findByText('Describe the problem in at least 10 characters')).toBeInTheDocument()

    await u.clear(dialog.getByLabelText('Describe the problem'))
    await u.click(dialog.getByLabelText('Describe the problem'))
    await u.paste('x'.repeat(2001))
    await u.click(dialog.getByRole('button', { name: 'Send report' }))
    expect(await dialog.findByText('Use at most 2000 characters')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('shows DISPUTE_ALREADY_OPEN in the dialog and refreshes the booking', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onPost('/bookings/91/disputes').reply(409, { code: 'DISPUTE_ALREADY_OPEN', detail: 'There is already an open report for this booking' })
    const u = userEvent.setup()
    renderApp('/driver/bookings/91')
    const dialog = await openDialog(u)
    const before = bookingGets().length
    await u.type(dialog.getByLabelText('Describe the problem'), 'The gate was locked and nobody answered')
    await u.click(dialog.getByRole('button', { name: 'Send report' }))

    expect(await dialog.findByText('There is already an open report for this booking')).toBeInTheDocument()
    await waitFor(() => expect(bookingGets().length).toBeGreaterThan(before))
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('shows DISPUTE_NOT_ALLOWED in the dialog', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onPost('/bookings/91/disputes').reply(409, { code: 'DISPUTE_NOT_ALLOWED', detail: 'Reports can only be raised until 7 days after the booking ends' })
    const u = userEvent.setup()
    renderApp('/driver/bookings/91')
    const dialog = await openDialog(u)
    await u.type(dialog.getByLabelText('Describe the problem'), 'The gate was locked and nobody answered')
    await u.click(dialog.getByRole('button', { name: 'Send report' }))

    expect(await dialog.findByText('Reports can only be raised until 7 days after the booking ends')).toBeInTheDocument()
  })

  it('offers no report button when the booking cannot be disputed, and no card without reports', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ disputable: false }))
    renderApp('/driver/bookings/91')

    await screen.findByText('PE-8KQ2M4')
    expect(screen.queryByRole('button', { name: 'Report a problem' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Problems reported' })).not.toBeInTheDocument()
  })

  it('lists my reports under Help', async () => {
    mock.onGet('/disputes').reply(200, page([summary(5), summary(6, { category: 'OVERSTAY', status: 'RESOLVED', resolvedAt: '2026-10-08T08:00:00Z' })]))
    renderApp('/driver/disputes')

    const list = within(await screen.findByRole('list', { name: 'Your reports' }))
    expect(mock.history.get.find((r) => r.url === '/disputes')!.params).toEqual({ page: 0, size: 20 })
    expect(list.getByRole('link', { name: /No access/ })).toHaveAttribute('href', '/driver/disputes/5')
    expect(list.getAllByText('Metro Hub Parking')).toHaveLength(2)
    expect(list.getByText('Resolved')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Help' })).toHaveAttribute('href', '/driver/disputes')
  })

  it('shows an empty state and server errors for the list', async () => {
    mock.onGet('/disputes').replyOnce(200, page([]))
    renderApp('/driver/disputes')
    expect(await screen.findByText('You haven’t reported any problems.')).toBeInTheDocument()
  })

  it('shows the server message when the list fails', async () => {
    mock.onGet('/disputes').reply(500, { code: 'INTERNAL', detail: 'Reports are down' })
    renderApp('/driver/disputes')
    expect(await screen.findByText('Reports are down')).toBeInTheDocument()
  })

  it('shows one report with the owner response and the outcome', async () => {
    mock.onGet('/disputes/6').reply(200, dispute(6, {
      status: 'RESOLVED', resolvedAt: '2026-10-08T08:00:00Z', ownerResponse: 'I was away; sorry about that',
      ownerRespondedAt: '2026-10-07T08:00:00Z', resolution: 'REFUND_PARTIAL', resolutionAmount: 40,
    }))
    renderApp('/driver/disputes/6')

    expect(await screen.findByRole('heading', { name: 'Problem with PE-8KQ2M4' })).toBeInTheDocument()
    expect(screen.getByText('The gate was locked and nobody answered')).toBeInTheDocument()
    expect(screen.getByText('I was away; sorry about that')).toBeInTheDocument()
    const outcome = within(screen.getByRole('region', { name: 'Outcome' }))
    expect(outcome.getByText('Partial refund')).toBeInTheDocument()
    expect(outcome.getByText('₹40')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View booking' })).toHaveAttribute('href', '/driver/bookings/91')
  })

  it('says when the owner has not responded and there is no outcome yet', async () => {
    mock.onGet('/disputes/5').reply(200, dispute(5))
    renderApp('/driver/disputes/5')

    expect(await screen.findByText('The owner has not responded yet.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Outcome' })).not.toBeInTheDocument()
  })

  it('shows the server message for a missing report', async () => {
    mock.onGet('/disputes/9').reply(404, { code: 'NOT_FOUND', detail: 'Report not found' })
    renderApp('/driver/disputes/9')
    expect(await screen.findByText('Report not found')).toBeInTheDocument()
  })
})
