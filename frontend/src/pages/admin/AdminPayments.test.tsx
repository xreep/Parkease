import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminPayment, AdminRefund } from '../../lib/adminManage'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const payment = (id: number, overrides: Partial<AdminPayment> = {}): AdminPayment => ({
  id, bookingId: 100 + id, bookingCode: `PE-PAY00${id}`, driverEmail: 'asha@example.com', provider: 'RAZORPAY', providerOrderId: `order_${id}`,
  providerPaymentId: `pay_${id}`, status: 'CAPTURED', amount: 300, refundAmount: 0, createdAt: '2026-10-09T05:00:00Z',
  capturedAt: '2026-10-09T05:01:00Z', ...overrides,
})

const refund = (id: number, overrides: Partial<AdminRefund> = {}): AdminRefund => ({
  id, paymentId: id, bookingId: 100 + id, bookingCode: `PE-REF00${id}`, amount: 120, status: 'PROCESSED', attempts: 1,
  providerRefundId: `rfnd_${id}`, reason: 'Driver cancelled', createdAt: '2026-10-09T06:00:00Z', lastError: null, ...overrides,
})

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('admin payments and refunds', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  const payments = () => mock.history.get.filter((r) => r.url === '/admin/payments')
  const refunds = () => mock.history.get.filter((r) => r.url === '/admin/refunds')

  it('lists payments with a link to the booking', async () => {
    mock.onGet('/admin/payments').reply(200, page([payment(1), payment(2, { status: 'PARTIALLY_REFUNDED', refundAmount: 100, providerPaymentId: null })]))
    renderApp('/admin/payments')

    const table = within(await screen.findByRole('table', { name: 'Payments' }))
    expect(payments()[0].params).toEqual({ page: 0, size: 20 })
    const row = table.getByRole('row', { name: /PE-PAY001/ })
    expect(within(row).getByRole('link', { name: 'PE-PAY001' })).toHaveAttribute('href', '/admin/bookings/101')
    expect(within(row).getByText('asha@example.com')).toBeInTheDocument()
    expect(within(row).getByText('Paid')).toBeInTheDocument()
    expect(within(row).getByText('₹300')).toBeInTheDocument()
    expect(within(table.getByRole('row', { name: /PE-PAY002/ })).getByText('Partially refunded')).toBeInTheDocument()
    expect(within(table.getByRole('row', { name: /PE-PAY002/ })).getByText('₹100')).toBeInTheDocument()
  })

  it('filters payments by status, dates and search', async () => {
    mock.onGet('/admin/payments').reply(200, page([payment(1)]))
    const u = userEvent.setup()
    renderApp('/admin/payments')
    await screen.findByRole('table', { name: 'Payments' })

    await u.selectOptions(screen.getByLabelText('Status'), 'Failed')
    await waitFor(() => expect(payments().at(-1)!.params).toEqual({ status: 'FAILED', page: 0, size: 20 }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    await waitFor(() => expect(payments().at(-1)!.params).toEqual({ status: 'FAILED', from: '2026-10-01', page: 0, size: 20 }))
    await u.type(screen.getByLabelText('Search payments'), 'asha@')
    await u.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(payments().at(-1)!.params).toEqual({ status: 'FAILED', from: '2026-10-01', q: 'asha@', page: 0, size: 20 }))
  })

  it('shows an empty state and server errors for payments', async () => {
    mock.onGet('/admin/payments').replyOnce(200, page([]))
    mock.onGet('/admin/payments').reply(500, { code: 'INTERNAL', detail: 'Payments are down' })
    const u = userEvent.setup()
    renderApp('/admin/payments')
    expect(await screen.findByText('No payments match these filters.')).toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Status'), 'Paid')
    expect(await screen.findByText('Payments are down')).toBeInTheDocument()
  })

  it('lists refunds on their own tab, filters FAILED ones and shows the error', async () => {
    mock.onGet('/admin/payments').reply(200, page([]))
    mock.onGet('/admin/refunds').reply(200, page([
      refund(1),
      refund(2, { status: 'FAILED', attempts: 3, providerRefundId: null, lastError: 'Gateway timeout' }),
    ]))
    const u = userEvent.setup()
    renderApp('/admin/payments')
    await u.click(await screen.findByRole('tab', { name: 'Refunds' }))

    const table = within(await screen.findByRole('table', { name: 'Refunds' }))
    expect(refunds()[0].params).toEqual({ page: 0, size: 20 })
    const failed = table.getByRole('row', { name: /PE-REF002/ })
    expect(within(failed).getByText('Failed')).toBeInTheDocument()
    expect(within(failed).getByText('Gateway timeout')).toBeInTheDocument()
    expect(within(failed).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(within(table.getByRole('row', { name: /PE-REF001/ })).queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Status'), 'Failed')
    await waitFor(() => expect(refunds().at(-1)!.params).toEqual({ status: 'FAILED', page: 0, size: 20 }))
  })

  it('retries a failed refund and reloads', async () => {
    mock.onGet('/admin/payments').reply(200, page([]))
    mock.onGet('/admin/refunds').replyOnce(200, page([refund(2, { status: 'FAILED', providerRefundId: null, lastError: 'Gateway timeout' })]))
    mock.onGet('/admin/refunds').reply(200, page([refund(2)]))
    mock.onPost('/admin/refunds/2/retry').reply(200, refund(2))
    const u = userEvent.setup()
    renderApp('/admin/payments')
    await u.click(await screen.findByRole('tab', { name: 'Refunds' }))

    await u.click(await screen.findByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Refund retried'))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument())
  })

  it('explains NOT_RETRYABLE and reloads the list', async () => {
    mock.onGet('/admin/payments').reply(200, page([]))
    mock.onGet('/admin/refunds').reply(200, page([refund(2, { status: 'FAILED', lastError: 'x' })]))
    mock.onPost('/admin/refunds/2/retry').reply(409, { code: 'NOT_RETRYABLE', detail: 'nope' })
    const u = userEvent.setup()
    renderApp('/admin/payments')
    await u.click(await screen.findByRole('tab', { name: 'Refunds' }))
    await screen.findByRole('button', { name: 'Retry' })
    const before = refunds().length

    await u.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('This refund can’t be retried — it has already been processed or is still in progress.'))
    await waitFor(() => expect(refunds().length).toBeGreaterThan(before))
  })

  it('shows an empty state for refunds', async () => {
    mock.onGet('/admin/payments').reply(200, page([]))
    mock.onGet('/admin/refunds').reply(200, page([]))
    const u = userEvent.setup()
    renderApp('/admin/payments')
    await u.click(await screen.findByRole('tab', { name: 'Refunds' }))

    expect(await screen.findByText('No refunds match this filter.')).toBeInTheDocument()
  })
})
