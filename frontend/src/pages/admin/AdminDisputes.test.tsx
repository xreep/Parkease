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

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const summary = (id: number, overrides: Partial<DisputeSummary> = {}): DisputeSummary => ({
  id, bookingId: 91, bookingCode: `PE-DSP00${id}`, listingTitle: 'FC Road Parking', category: 'NO_ACCESS', status: 'OPEN',
  createdAt: '2026-10-06T08:00:00Z', resolvedAt: null, ...overrides,
})

const dispute = (id: number, overrides: Partial<Dispute> = {}): Dispute => ({
  ...summary(id), description: 'The gate was locked and nobody answered', raisedByName: 'Rahul Verma', ownerResponse: 'I was away',
  ownerRespondedAt: '2026-10-07T08:00:00Z', resolution: null, resolutionAmount: null, adminNotes: null, refundableRemaining: 300, ...overrides,
})

const page = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

describe('admin disputes', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    vi.mocked(toast.success).mockClear()
  })

  afterEach(() => mock.restore())

  const calls = () => mock.history.get.filter((r) => r.url === '/admin/disputes')

  it('lists reports and filters by status', async () => {
    mock.onGet('/admin/disputes').reply(200, page([summary(1), summary(2, { status: 'RESOLVED', category: 'DAMAGE' })]))
    const u = userEvent.setup()
    renderApp('/admin/disputes')

    const list = within(await screen.findByRole('list', { name: 'Reports' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(list.getByRole('link', { name: /PE-DSP001/ })).toHaveAttribute('href', '/admin/disputes/1')
    expect(list.getByText('Resolved')).toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Show'), 'Open')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'OPEN', page: 0, size: 20 }))
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/admin/disputes').replyOnce(200, page([]))
    mock.onGet('/admin/disputes').reply(500, { code: 'INTERNAL', detail: 'Reports are down' })
    const u = userEvent.setup()
    renderApp('/admin/disputes')
    expect(await screen.findByText('No reports match this filter.')).toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Show'), 'Open')
    expect(await screen.findByText('Reports are down')).toBeInTheDocument()
  })

  it('shows the full report with the booking link and what is still refundable', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    renderApp('/admin/disputes/1')

    expect(await screen.findByRole('heading', { name: 'Report on PE-DSP001' })).toBeInTheDocument()
    expect(screen.getByText('The gate was locked and nobody answered')).toBeInTheDocument()
    expect(screen.getByText('I was away')).toBeInTheDocument()
    expect(screen.getByText('Refundable: ₹300')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View booking' })).toHaveAttribute('href', '/admin/bookings/91')
  })

  it('takes an open report under review', async () => {
    mock.onGet('/admin/disputes/1').replyOnce(200, dispute(1))
    mock.onGet('/admin/disputes/1').reply(200, dispute(1, { status: 'UNDER_REVIEW' }))
    mock.onPost('/admin/disputes/1/review').reply(200, dispute(1, { status: 'UNDER_REVIEW' }))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')

    await u.click(await screen.findByRole('button', { name: 'Start review' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Report is under review'))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument()
  })

  it('shows the server message when starting the review fails', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    mock.onPost('/admin/disputes/1/review').reply(409, { code: 'INVALID_STATUS', detail: 'Only open reports can be taken under review' })
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')

    await u.click(await screen.findByRole('button', { name: 'Start review' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only open reports can be taken under review'))
  })

  async function openResolve(u: ReturnType<typeof userEvent.setup>) {
    await u.click(await screen.findByRole('button', { name: 'Resolve' }))
    return within(await screen.findByRole('dialog', { name: 'Resolve this report' }))
  }

  it('resolves with a full refund of what remains', async () => {
    mock.onGet('/admin/disputes/1').replyOnce(200, dispute(1, { status: 'UNDER_REVIEW' }))
    mock.onGet('/admin/disputes/1').reply(200, dispute(1, {
      status: 'RESOLVED', resolvedAt: '2026-10-09T08:00:00Z', resolution: 'REFUND_FULL', resolutionAmount: 300, adminNotes: 'Gate really was locked',
    }))
    mock.onPost('/admin/disputes/1/resolve').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')

    const dialog = await openResolve(u)
    await u.click(dialog.getByLabelText('Full refund (₹300)'))
    await u.type(dialog.getByLabelText('Notes'), 'Gate really was locked')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Report resolved'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ resolution: 'REFUND_FULL', notes: 'Gate really was locked' })
    expect(await screen.findByText('Gate really was locked')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Outcome' })).getByText('Full refund')).toBeInTheDocument()
  })

  it('validates a partial refund amount against what is refundable', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1, { status: 'UNDER_REVIEW' }))
    mock.onPost('/admin/disputes/1/resolve').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)

    expect(dialog.queryByLabelText('Refund amount (₹)')).not.toBeInTheDocument()
    await u.click(dialog.getByLabelText('Partial refund'))
    await u.type(dialog.getByLabelText('Notes'), 'Part of it')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    expect(await dialog.findByText('Enter an amount above ₹0, up to ₹300')).toBeInTheDocument()

    await u.type(dialog.getByLabelText('Refund amount (₹)'), '350')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    expect(await dialog.findByText('Enter an amount above ₹0, up to ₹300')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)

    await u.clear(dialog.getByLabelText('Refund amount (₹)'))
    await u.type(dialog.getByLabelText('Refund amount (₹)'), '120.50')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ resolution: 'REFUND_PARTIAL', amount: 120.5, notes: 'Part of it' })
  })

  it('says the notes stay in the admin record', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)

    expect(dialog.getByText('Kept in the admin record; not shown to the driver or owner.')).toBeInTheDocument()
  })

  it('rejects a partial amount of zero or with three decimals', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)
    await u.click(dialog.getByLabelText('Partial refund'))
    await u.type(dialog.getByLabelText('Notes'), 'Part')

    await u.type(dialog.getByLabelText('Refund amount (₹)'), '0')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    expect(await dialog.findByText('Enter an amount above ₹0, up to ₹300')).toBeInTheDocument()

    await u.clear(dialog.getByLabelText('Refund amount (₹)'))
    await u.type(dialog.getByLabelText('Refund amount (₹)'), '10.123')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    expect(await dialog.findByText('Enter an amount above ₹0, up to ₹300')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('requires notes and sends no amount for no-refund or warning', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    mock.onPost('/admin/disputes/1/resolve').reply(200, dispute(1))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)

    await u.click(dialog.getByLabelText('Warning only'))
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    expect(await dialog.findByText('Add notes for the record')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)

    await u.type(dialog.getByLabelText('Notes'), 'Warned the owner')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))
    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ resolution: 'WARNING', notes: 'Warned the owner' })
  })

  it('disables the refund options when nothing is left to refund', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1, { refundableRemaining: 0 }))
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)

    expect(dialog.getByLabelText('Full refund (₹0)')).toBeDisabled()
    expect(dialog.getByLabelText('Partial refund')).toBeDisabled()
    expect(dialog.getByLabelText('No refund')).toBeEnabled()
  })

  it('shows INVALID_RESOLUTION in the dialog', async () => {
    mock.onGet('/admin/disputes/1').reply(200, dispute(1))
    mock.onPost('/admin/disputes/1/resolve').reply(400, { code: 'INVALID_RESOLUTION', detail: 'The amount exceeds what can still be refunded' })
    const u = userEvent.setup()
    renderApp('/admin/disputes/1')
    const dialog = await openResolve(u)
    await u.click(dialog.getByLabelText('No refund'))
    await u.type(dialog.getByLabelText('Notes'), 'Because')
    await u.click(dialog.getByRole('button', { name: 'Resolve report' }))

    expect(await dialog.findByText('The amount exceeds what can still be refunded')).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('shows a resolved report with its notes and no actions', async () => {
    mock.onGet('/admin/disputes/2').reply(200, dispute(2, {
      status: 'RESOLVED', resolvedAt: '2026-10-09T08:00:00Z', resolution: 'REFUND_PARTIAL', resolutionAmount: 100, adminNotes: 'Half is fair',
    }))
    renderApp('/admin/disputes/2')

    expect(await screen.findByText('Half is fair')).toBeInTheDocument()
    expect(screen.getByText('₹100')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument()
  })

  it('shows the server message for a missing report', async () => {
    mock.onGet('/admin/disputes/9').reply(404, { code: 'NOT_FOUND', detail: 'Report not found' })
    renderApp('/admin/disputes/9')
    expect(await screen.findByText('Report not found')).toBeInTheDocument()
  })
})
