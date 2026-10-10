import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { api } from '../../lib/api'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }
const page = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

/** Every admin write lands in the audit log, so an open audit page or overview must refetch after it. */
describe('admin writes refresh the audit log and the numbers', () => {
  let mock: MockAdapter
  let queryClient: QueryClient
  let invalidate: MockInstance<QueryClient['invalidateQueries']>

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    vi.mocked(toast.success).mockClear()
  })

  afterEach(() => mock.restore())

  const invalidated = (key: string[]) => invalidate.mock.calls.some((call) => JSON.stringify((call[0] as { queryKey: unknown }).queryKey) === JSON.stringify(key))

  it('after suspending a user', async () => {
    mock.onGet('/admin/users').reply(200, page([{
      id: 5, firstName: 'Asha', lastName: 'Rao', email: 'a@example.com', phone: null, role: 'DRIVER', status: 'ACTIVE',
      emailVerified: true, createdAt: '2026-09-01T10:00:00Z', bookingsCount: 0, listingsCount: 0,
    }]))
    mock.onPost('/admin/users/5/suspend').reply(200, {})
    const u = userEvent.setup()
    renderApp('/admin/users', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Suspend' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Reason'), 'Abuse')
    await u.click(dialog.getByRole('button', { name: 'Suspend' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(invalidated(['admin', 'audit'])).toBe(true)
    expect(invalidated(['admin', 'stats'])).toBe(true)
  })

  it('after saving settings', async () => {
    const settings = {
      platformFeePercent: 10, gstPercent: 18, holdMinutes: 10, approvalHours: 2, requestMinLeadMinutes: 30,
      priceGuidelines: [{ tier: 1, minHourly: 40, maxHourly: 200 }, { tier: 2, minHourly: 20, maxHourly: 120 }, { tier: 3, minHourly: 10, maxHourly: 80 }],
    }
    mock.onGet('/admin/settings').reply(200, settings)
    mock.onPut('/admin/settings').reply(200, settings)
    const u = userEvent.setup()
    renderApp('/admin/settings', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Save settings' }))
    await u.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Settings saved'))
    expect(invalidated(['admin', 'audit'])).toBe(true)
  })

  it('after retrying a refund', async () => {
    mock.onGet('/admin/payments').reply(200, page([]))
    mock.onGet('/admin/refunds').reply(200, page([{
      id: 2, paymentId: 1, bookingId: 3, bookingCode: 'PE-R', amount: 100, status: 'FAILED', attempts: 2, providerRefundId: null,
      reason: 'x', createdAt: '2026-10-09T06:00:00Z', lastError: 'e',
    }]))
    mock.onPost('/admin/refunds/2/retry').reply(200, { id: 2, status: 'PROCESSED' })
    const u = userEvent.setup()
    renderApp('/admin/payments', queryClient)
    await u.click(await screen.findByRole('tab', { name: 'Refunds' }))
    await u.click(await screen.findByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(invalidated(['admin', 'audit'])).toBe(true)
    expect(invalidated(['admin', 'stats'])).toBe(true)
  })

  it('after marking a payout paid', async () => {
    mock.onGet('/admin/payouts').reply(200, [{ ownerId: 5, ownerName: 'Ravi Kumar', ownerEmail: 'r@example.com', pendingAmount: 500, earningsCount: 1, payoutMethod: null, payoutMasked: null }])
    mock.onGet('/admin/payouts/5/earnings').reply(200, [{
      id: 1, bookingId: 2, bookingCode: 'PE-E1', listingTitle: 'L', startTime: '2026-10-05T04:30:00Z', endTime: '2026-10-05T06:30:00Z',
      gross: 520, commission: 20, net: 500, status: 'PENDING_PAYOUT', paidAt: null, payoutReference: null,
    }])
    mock.onPost('/admin/payouts/mark-paid').reply(200, { paidCount: 1, paidAmount: 500 })
    const u = userEvent.setup()
    renderApp('/admin/payouts', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Show earnings' }))
    await u.click(await screen.findByRole('checkbox', { name: 'Select all' }))
    await u.click(screen.getByRole('button', { name: /Mark selected as paid/ }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Payment reference'), 'UTR123456')
    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(invalidated(['admin', 'audit'])).toBe(true)
    expect(invalidated(['admin', 'stats'])).toBe(true)
  })

  it('after hiding a review', async () => {
    mock.onGet('/admin/reviews').reply(200, page([{
      id: 1, rating: 4, comment: 'ok', authorName: 'R.', createdAt: '2026-10-05T10:00:00Z', ownerReply: null, ownerRepliedAt: null,
      listingId: 9, listingTitle: 'L', bookingCode: 'PE-R1', hidden: false, hiddenReason: null,
    }]))
    mock.onPost('/admin/reviews/1/hide').reply(200, {})
    const u = userEvent.setup()
    renderApp('/admin/reviews', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Hide' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Reason'), 'Spam')
    await u.click(dialog.getByRole('button', { name: 'Hide' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    expect(invalidated(['admin', 'audit'])).toBe(true)
  })

  it('after cancelling a booking', async () => {
    mock.onGet('/admin/bookings/1').reply(200, {
      id: 1, bookingCode: 'PE-B1', status: 'CONFIRMED', listingTitle: 'L', cityName: 'Pune', driverName: 'A', driverEmail: 'a@example.com',
      ownerName: 'O', startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', totalAmount: 300, refundAmount: 0,
      paymentStatus: 'CAPTURED', createdAt: '2026-10-09T05:00:00Z', listingId: 9, slotLabel: 'A', baseAmount: 250, platformFee: 25,
      gstAmount: 25, cancelReason: null, cancelledBy: null, payment: null, refunds: [], events: [], disputes: [],
    })
    mock.onPost('/admin/bookings/1/cancel').reply(200, {})
    const u = userEvent.setup()
    renderApp('/admin/bookings/1', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Cancel booking' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Reason'), 'Because')
    await u.click(dialog.getByRole('button', { name: 'Cancel booking' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking cancelled'))
    expect(invalidated(['admin', 'audit'])).toBe(true)
    expect(invalidated(['admin', 'stats'])).toBe(true)
  })

  it('after adding a city', async () => {
    mock.onGet('/admin/states').reply(200, [{ id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', citiesCount: 0 }])
    mock.onGet('/admin/cities').reply(200, page([]))
    mock.onPost('/admin/cities').reply(201, {})
    const u = userEvent.setup()
    renderApp('/admin/locations', queryClient)
    await u.click(await screen.findByRole('button', { name: 'Add city' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Name'), 'Nashik')
    await u.type(dialog.getByLabelText('Latitude'), '19.99')
    await u.type(dialog.getByLabelText('Longitude'), '73.78')
    await u.click(dialog.getByRole('button', { name: 'Add city' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Nashik added'))
    expect(invalidated(['admin', 'audit'])).toBe(true)
  })
})
