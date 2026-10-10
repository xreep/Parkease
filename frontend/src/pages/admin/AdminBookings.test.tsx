import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminBookingDetail, AdminBookingSummary } from '../../lib/adminManage'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const summary = (id: number, overrides: Partial<AdminBookingSummary> = {}): AdminBookingSummary => ({
  id, bookingCode: `PE-BK00${id}`, status: 'CONFIRMED', listingTitle: 'FC Road Parking', cityName: 'Pune', driverName: 'Asha Rao',
  driverEmail: 'asha@example.com', ownerName: 'Ravi Kumar', startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z',
  totalAmount: 300, refundAmount: 0, paymentStatus: 'CAPTURED', createdAt: '2026-10-09T05:00:00Z', ...overrides,
})

const detail = (id: number, overrides: Partial<AdminBookingDetail> = {}): AdminBookingDetail => ({
  ...summary(id),
  listingId: 9, slotLabel: 'A-01', baseAmount: 250, platformFee: 25, gstAmount: 25, cancelReason: null, cancelledBy: null,
  payment: {
    id: 70, provider: 'RAZORPAY', providerOrderId: 'order_ABC', providerPaymentId: 'pay_XYZ', status: 'CAPTURED', amount: 300,
    capturedAt: '2026-10-09T05:01:00Z',
  },
  refunds: [],
  events: [
    { fromStatus: null, toStatus: 'PENDING_PAYMENT', actor: 'DRIVER', note: null, at: '2026-10-09T05:00:00Z' },
    { fromStatus: 'PENDING_PAYMENT', toStatus: 'CONFIRMED', actor: 'SYSTEM', note: 'Payment captured', at: '2026-10-09T05:01:00Z' },
  ],
  disputes: [],
  ...overrides,
})

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('admin bookings', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/states').reply(200, [
      { id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cityCount: 1 },
    ])
    mock.onGet('/states/maharashtra').reply(200, {
      id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai',
      cities: [{ id: 10, name: 'Pune', slug: 'pune', lat: 18.5, lng: 73.8, capital: false, stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra' }],
    })
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  describe('list', () => {
    const calls = () => mock.history.get.filter((r) => r.url === '/admin/bookings')

    it('lists bookings with a link to each detail', async () => {
      mock.onGet('/admin/bookings').reply(200, page([summary(1), summary(2, { status: 'CANCELLED', refundAmount: 300, paymentStatus: 'REFUNDED', bookingCode: 'PE-BK002' })]))
      renderApp('/admin/bookings')

      const table = within(await screen.findByRole('table', { name: 'Bookings' }))
      expect(calls()[0].params).toEqual({ page: 0, size: 20 })
      const row = table.getByRole('row', { name: /PE-BK001/ })
      expect(within(row).getByRole('link', { name: 'PE-BK001' })).toHaveAttribute('href', '/admin/bookings/1')
      expect(within(row).getByText('Confirmed')).toBeInTheDocument()
      expect(within(row).getByText('FC Road Parking')).toBeInTheDocument()
      expect(within(row).getByText('Asha Rao')).toBeInTheDocument()
      expect(within(row).getByText('₹300')).toBeInTheDocument()
      expect(within(table.getByRole('row', { name: /PE-BK002/ })).getByText('Cancelled')).toBeInTheDocument()
    })

    it('filters by status, dates, city and search text', async () => {
      mock.onGet('/admin/bookings').reply(200, page([summary(1)]))
      const u = userEvent.setup()
      renderApp('/admin/bookings')
      await screen.findByRole('table', { name: 'Bookings' })

      await u.selectOptions(screen.getByLabelText('Status'), 'Cancelled')
      await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'CANCELLED', page: 0, size: 20 }))
      fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
      fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-09' } })
      await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'CANCELLED', from: '2026-10-01', to: '2026-10-09', page: 0, size: 20 }))

      await screen.findByRole('option', { name: 'Maharashtra' })
      await u.selectOptions(screen.getByLabelText('State'), 'Maharashtra')
      await screen.findByRole('option', { name: 'Pune' })
      await u.selectOptions(screen.getByLabelText('City'), 'Pune')
      await waitFor(() => expect(calls().at(-1)!.params).toMatchObject({ cityId: 10 }))

      await u.type(screen.getByLabelText('Search bookings'), 'PE-BK0')
      await u.click(screen.getByRole('button', { name: 'Search' }))
      await waitFor(() => expect(calls().at(-1)!.params).toEqual({
        status: 'CANCELLED', from: '2026-10-01', to: '2026-10-09', cityId: 10, q: 'PE-BK0', page: 0, size: 20,
      }))
    })

    it('says the state only narrows the city list, and does not filter by itself', async () => {
      mock.onGet('/admin/bookings').reply(200, page([summary(1)]))
      const u = userEvent.setup()
      renderApp('/admin/bookings')
      await screen.findByRole('table', { name: 'Bookings' })
      expect(screen.getByText('Pick a city to filter by place — the state only narrows the city list.')).toBeInTheDocument()
      const before = calls().length

      await screen.findByRole('option', { name: 'Maharashtra' })
      await u.selectOptions(screen.getByLabelText('State'), 'Maharashtra')

      await screen.findByRole('option', { name: 'Pune' })
      expect(calls()).toHaveLength(before)
    })

    it('shows an empty state and server errors', async () => {
      mock.onGet('/admin/bookings').replyOnce(200, page([]))
      mock.onGet('/admin/bookings').reply(500, { code: 'INTERNAL', detail: 'Bookings are down' })
      const u = userEvent.setup()
      renderApp('/admin/bookings')
      expect(await screen.findByText('No bookings match these filters.')).toBeInTheDocument()

      await u.selectOptions(screen.getByLabelText('Status'), 'Active')
      expect(await screen.findByText('Bookings are down')).toBeInTheDocument()
    })
  })

  describe('detail', () => {
    it('shows the booking, amounts, payment, refunds, timeline and disputes', async () => {
      mock.onGet('/admin/bookings/1').reply(200, detail(1, {
        refundAmount: 50,
        refunds: [{ id: 5, amount: 50, status: 'PROCESSED', attempts: 1, providerRefundId: 'rfnd_1', reason: 'Owner cancelled', createdAt: '2026-10-10T05:00:00Z' }],
        disputes: [{ id: 3, bookingId: 1, bookingCode: 'PE-BK001', listingTitle: 'FC Road Parking', category: 'NO_ACCESS', status: 'OPEN', createdAt: '2026-10-12T08:00:00Z', resolvedAt: null }],
      }))
      renderApp('/admin/bookings/1')

      expect(await screen.findByRole('heading', { name: 'PE-BK001' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '← Bookings' })).toHaveAttribute('href', '/admin/bookings')
      const booking = within(screen.getByRole('region', { name: 'Booking' }))
      expect(booking.getByText('FC Road Parking')).toBeInTheDocument()
      expect(booking.getByText(/A-01/)).toBeInTheDocument()
      expect(booking.getByText('Asha Rao')).toBeInTheDocument()
      expect(booking.getByText('Ravi Kumar')).toBeInTheDocument()
      const amounts = within(screen.getByRole('region', { name: 'Amounts' }))
      expect(amounts.getByText('₹250')).toBeInTheDocument()
      expect(amounts.getAllByText('₹25')).toHaveLength(2)
      expect(amounts.getByText('₹300')).toBeInTheDocument()
      const payment = within(screen.getByRole('region', { name: 'Payment' }))
      expect(payment.getByText('order_ABC')).toBeInTheDocument()
      expect(payment.getByText('pay_XYZ')).toBeInTheDocument()
      const refunds = within(screen.getByRole('region', { name: 'Refunds' }))
      expect(refunds.getByText('Owner cancelled')).toBeInTheDocument()
      expect(refunds.getByText('Processed')).toBeInTheDocument()
      const timeline = within(screen.getByRole('list', { name: 'Timeline' }))
      expect(timeline.getAllByRole('listitem')).toHaveLength(2)
      expect(timeline.getByText('Payment captured')).toBeInTheDocument()
      const disputes = within(screen.getByRole('region', { name: 'Disputes' }))
      expect(disputes.getByRole('link', { name: /No access/ })).toHaveAttribute('href', '/admin/disputes/3')
    })

    it('shows the empty sections', async () => {
      mock.onGet('/admin/bookings/1').reply(200, detail(1, { payment: null, paymentStatus: null, disputes: [] }))
      renderApp('/admin/bookings/1')

      expect(await screen.findByText('No payment was started for this booking.')).toBeInTheDocument()
      expect(screen.getByText('No refunds.')).toBeInTheDocument()
      expect(screen.getByText('No disputes.')).toBeInTheDocument()
    })

    it('cancels after showing the refund that will be issued (paid minus refunded)', async () => {
      mock.onGet('/admin/bookings/1').replyOnce(200, detail(1, { refundAmount: 50 }))
      mock.onGet('/admin/bookings/1').reply(200, detail(1, { status: 'CANCELLED', refundAmount: 300, cancelReason: 'Owner unreachable', cancelledBy: 'ADMIN' }))
      mock.onPost('/admin/bookings/1/cancel').reply(200, detail(1, { status: 'CANCELLED', refundAmount: 300 }))
      const u = userEvent.setup()
      renderApp('/admin/bookings/1')

      await u.click(await screen.findByRole('button', { name: 'Cancel booking' }))
      const dialog = within(await screen.findByRole('dialog', { name: 'Cancel this booking?' }))
      expect(dialog.getByRole('button', { name: 'Keep booking' })).toBeInTheDocument()
      expect(dialog.getByText('₹250')).toBeInTheDocument()
      expect(dialog.getByText(/will be refunded/)).toBeInTheDocument()
      await u.type(dialog.getByLabelText('Reason'), 'Owner unreachable')
      await u.click(dialog.getByRole('button', { name: 'Cancel booking' }))

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking cancelled'))
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Owner unreachable' })
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel booking' })).not.toBeInTheDocument())
      expect(screen.getByText('Owner unreachable')).toBeInTheDocument()
    })

    it('says nothing is refunded when nothing was paid', async () => {
      mock.onGet('/admin/bookings/1').reply(200, detail(1, { status: 'PENDING_PAYMENT', payment: null, paymentStatus: null }))
      const u = userEvent.setup()
      renderApp('/admin/bookings/1')

      await u.click(await screen.findByRole('button', { name: 'Cancel booking' }))

      expect(within(await screen.findByRole('dialog')).getByText('Nothing has been paid, so no refund will be issued.')).toBeInTheDocument()
    })

    it('does not offer cancelling a finished booking', async () => {
      mock.onGet('/admin/bookings/1').reply(200, detail(1, { status: 'COMPLETED' }))
      renderApp('/admin/bookings/1')

      await screen.findByRole('heading', { name: 'PE-BK001' })
      expect(screen.queryByRole('button', { name: 'Cancel booking' })).not.toBeInTheDocument()
    })

    it('shows the server message when the cancellation fails', async () => {
      mock.onGet('/admin/bookings/1').reply(200, detail(1))
      mock.onPost('/admin/bookings/1/cancel').reply(409, { code: 'INVALID_STATE', detail: 'The booking already ended' })
      const u = userEvent.setup()
      renderApp('/admin/bookings/1')
      await u.click(await screen.findByRole('button', { name: 'Cancel booking' }))
      const dialog = within(await screen.findByRole('dialog'))
      await u.type(dialog.getByLabelText('Reason'), 'Because')
      await u.click(dialog.getByRole('button', { name: 'Cancel booking' }))

      expect(await dialog.findByText('The booking already ended')).toBeInTheDocument()
    })

    it('shows the server message for a missing booking', async () => {
      mock.onGet('/admin/bookings/9').reply(404, { code: 'NOT_FOUND', detail: 'Booking not found' })
      renderApp('/admin/bookings/9')

      expect(await screen.findByText('Booking not found')).toBeInTheDocument()
    })
  })
})
