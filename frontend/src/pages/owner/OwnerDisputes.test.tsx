import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { Dispute, DisputeSummary } from '../../lib/disputes'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

const summary = (id: number, overrides: Partial<DisputeSummary> = {}): DisputeSummary => ({
  id, bookingId: 91, bookingCode: `PE-DSP00${id}`, listingTitle: 'FC Road Parking', category: 'OVERSTAY', status: 'OPEN',
  createdAt: '2026-10-06T08:00:00Z', resolvedAt: null, ...overrides,
})

const dispute = (id: number, overrides: Partial<Dispute> = {}): Dispute => ({
  ...summary(id), description: 'Another car was parked in my slot', raisedByName: 'Rahul Verma', ownerResponse: null,
  ownerRespondedAt: null, resolution: null, resolutionAmount: null, adminNotes: null, refundableRemaining: null, ...overrides,
})

const page = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

describe('owner disputes', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    vi.mocked(toast.success).mockClear()
  })

  afterEach(() => mock.restore())

  const calls = () => mock.history.get.filter((r) => r.url === '/owner/disputes')

  it('lists reports on my bookings and filters by status', async () => {
    mock.onGet('/owner/disputes').reply(200, page([summary(1), summary(2, { status: 'UNDER_REVIEW', category: 'DAMAGE' })]))
    const u = userEvent.setup()
    renderApp('/owner/disputes')

    const list = within(await screen.findByRole('list', { name: 'Reports' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(list.getByRole('link', { name: /PE-DSP001/ })).toHaveAttribute('href', '/owner/disputes/1')
    expect(list.getByText('Under review')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Disputes' })).toHaveAttribute('href', '/owner/disputes')

    await u.selectOptions(screen.getByLabelText('Show'), 'Resolved')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'RESOLVED', page: 0, size: 20 }))
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/owner/disputes').replyOnce(200, page([]))
    mock.onGet('/owner/disputes').reply(500, { code: 'INTERNAL', detail: 'Reports are down' })
    const u = userEvent.setup()
    renderApp('/owner/disputes')
    expect(await screen.findByText('No reports on your bookings.')).toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Show'), 'Open')
    expect(await screen.findByText('Reports are down')).toBeInTheDocument()
  })

  it('shows the report and sends one response', async () => {
    mock.onGet('/owner/disputes/1').replyOnce(200, dispute(1))
    mock.onGet('/owner/disputes/1').reply(200, dispute(1, { ownerResponse: 'The car was a delivery van', ownerRespondedAt: '2026-10-07T08:00:00Z' }))
    mock.onPost('/owner/disputes/1/respond').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/owner/disputes/1')

    expect(await screen.findByRole('heading', { name: 'Report on PE-DSP001' })).toBeInTheDocument()
    expect(screen.getByText('Another car was parked in my slot')).toBeInTheDocument()
    expect(screen.getByText(/Rahul Verma/)).toBeInTheDocument()
    await u.type(screen.getByLabelText('Your response'), 'The car was a delivery van')
    await u.click(screen.getByRole('button', { name: 'Send response' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Response sent'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ response: 'The car was a delivery van' })
    expect(await screen.findByText('The car was a delivery van')).toBeInTheDocument()
    expect(screen.queryByLabelText('Your response')).not.toBeInTheDocument()
  })

  it('requires a response of at most 1000 characters', async () => {
    mock.onGet('/owner/disputes/1').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/owner/disputes/1')
    await screen.findByLabelText('Your response')

    await u.click(screen.getByRole('button', { name: 'Send response' }))
    expect(await screen.findByText('Write your response')).toBeInTheDocument()
    await u.click(screen.getByLabelText('Your response'))
    await u.paste('x'.repeat(1001))
    await u.click(screen.getByRole('button', { name: 'Send response' }))
    expect(await screen.findByText('Use at most 1000 characters')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('shows ALREADY_RESPONDED and reloads the report', async () => {
    mock.onGet('/owner/disputes/1').replyOnce(200, dispute(1))
    mock.onGet('/owner/disputes/1').reply(200, dispute(1, { ownerResponse: 'Sent from another tab', ownerRespondedAt: '2026-10-07T08:00:00Z' }))
    mock.onPost('/owner/disputes/1/respond').reply(409, { code: 'ALREADY_RESPONDED', detail: 'You have already responded to this report' })
    const u = userEvent.setup()
    renderApp('/owner/disputes/1')
    await u.type(await screen.findByLabelText('Your response'), 'My answer')
    await u.click(screen.getByRole('button', { name: 'Send response' }))

    expect(await screen.findByText('You have already responded to this report')).toBeInTheDocument()
    expect(await screen.findByText('Sent from another tab')).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('offers no response form once the report is resolved, and shows the outcome', async () => {
    mock.onGet('/owner/disputes/2').reply(200, dispute(2, {
      status: 'RESOLVED', resolvedAt: '2026-10-08T08:00:00Z', resolution: 'WARNING',
    }))
    renderApp('/owner/disputes/2')

    expect(await screen.findByRole('heading', { name: 'Report on PE-DSP002' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Your response')).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Outcome' })).getByText('Warning issued')).toBeInTheDocument()
  })

  it('shows the server message for a missing report', async () => {
    mock.onGet('/owner/disputes/9').reply(404, { code: 'NOT_FOUND', detail: 'Report not found' })
    renderApp('/owner/disputes/9')
    expect(await screen.findByText('Report not found')).toBeInTheDocument()
  })
})
