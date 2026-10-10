import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminListingDetail, AdminListingSummary } from '../../lib/admin'
import type { AdminReview } from '../../lib/adminManage'
import type { ListingStatus } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

vi.mock('../../components/owner/LocationPicker', () => ({
  LocationPicker: () => <div>Map pin locked</div>,
}))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

const detail = (status: ListingStatus): AdminListingDetail => ({
  listing: {
    id: 9, title: 'Viman Nagar Residency Parking', description: 'Gated society lot', address: '14 Viman Nagar Road', pincode: '411014',
    lat: 18.5679, lng: 73.9143, listingType: 'RESIDENTIAL', cityId: 51, cityName: 'Pune', stateName: 'Maharashtra',
    status, rejectionReason: null, open24x7: true, rules: null, autoApprove: false,
    pricePerHour: 40, pricePerDay: null, pricePerMonth: null, cancellationPolicy: 'MODERATE', amenities: [],
    photos: [], slots: [{ id: 31, label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true }],
    hours: [], submittedAt: '2026-10-05T09:00:00Z', approvedAt: null, updatedAt: '2026-10-05T10:00:00Z',
  },
  owner: { id: 5, name: 'Ravi Kumar', email: 'ravi@example.com', phone: '9876543210', verificationStatus: 'VERIFIED' },
})

describe('admin listing moderation', () => {
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

  it('suspends a live listing with a reason and stays on the page', async () => {
    mock.onGet('/admin/listings/9').replyOnce(200, detail('APPROVED'))
    mock.onGet('/admin/listings/9').reply(200, detail('SUSPENDED'))
    mock.onPost('/admin/listings/9/suspend').reply(200, detail('SUSPENDED'))
    const u = userEvent.setup()
    renderApp('/admin/listings/9')

    expect(await screen.findByText('Live')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    await u.click(screen.getByRole('button', { name: 'Suspend' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Suspend this listing?' }))
    await u.type(dialog.getByLabelText('Reason'), 'Fake photos')
    await u.click(dialog.getByRole('button', { name: 'Suspend' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Listing suspended'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Fake photos' })
    expect(await screen.findByRole('button', { name: 'Reinstate' })).toBeInTheDocument()
    expect(screen.getByText('Suspended')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Viman Nagar Residency Parking' })).toBeInTheDocument()
  })

  it('offers suspending a paused listing', async () => {
    mock.onGet('/admin/listings/9').reply(200, detail('PAUSED'))
    renderApp('/admin/listings/9')

    expect(await screen.findByRole('button', { name: 'Suspend' })).toBeInTheDocument()
  })

  it('reinstates a suspended listing', async () => {
    mock.onGet('/admin/listings/9').replyOnce(200, detail('SUSPENDED'))
    mock.onGet('/admin/listings/9').reply(200, detail('APPROVED'))
    mock.onPost('/admin/listings/9/reinstate').reply(200, detail('APPROVED'))
    const u = userEvent.setup()
    renderApp('/admin/listings/9')

    await u.click(await screen.findByRole('button', { name: 'Reinstate' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Listing reinstated'))
    expect(await screen.findByRole('button', { name: 'Suspend' })).toBeInTheDocument()
  })

  it('shows the server message when reinstating fails', async () => {
    mock.onGet('/admin/listings/9').reply(200, detail('SUSPENDED'))
    mock.onPost('/admin/listings/9/reinstate').reply(409, { code: 'INVALID_STATE', detail: 'The owner is suspended' })
    const u = userEvent.setup()
    renderApp('/admin/listings/9')

    await u.click(await screen.findByRole('button', { name: 'Reinstate' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('The owner is suspended'))
  })

  it('shows no suspend action while a listing is pending review', async () => {
    mock.onGet('/admin/listings/9').reply(200, detail('PENDING_REVIEW'))
    renderApp('/admin/listings/9')

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suspend' })).not.toBeInTheDocument()
  })

  it('lets the queue show paused and suspended listings with their status', async () => {
    const summary: AdminListingSummary = {
      id: 9, title: 'Viman Nagar Residency Parking', status: 'SUSPENDED', cityName: 'Pune', stateName: 'Maharashtra',
      coverPhotoUrl: null, pricePerHour: 40, slotCount: 6, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z',
      ownerId: 5, ownerName: 'Ravi Kumar', ownerEmail: 'ravi@example.com', submittedAt: null,
    }
    mock.onGet('/admin/listings').reply(200, page([summary]))
    const u = userEvent.setup()
    renderApp('/admin/listings')
    await screen.findByText('Viman Nagar Residency Parking')

    await u.selectOptions(screen.getByLabelText('Show'), 'Suspended')

    await waitFor(() => expect(mock.history.get.filter((r) => r.url === '/admin/listings').at(-1)!.params).toEqual({ status: 'SUSPENDED', page: 0, size: 20 }))
    expect(within(screen.getByRole('article', { name: 'Viman Nagar Residency Parking' })).getByText('Suspended')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Paused' })).toBeInTheDocument()
  })
})

const review = (id: number, overrides: Partial<AdminReview> = {}): AdminReview => ({
  id, rating: 4, comment: 'Easy to find', authorName: 'Rahul S.', createdAt: '2026-10-05T10:00:00Z', ownerReply: null, ownerRepliedAt: null,
  listingId: 9, listingTitle: 'FC Road Parking', bookingCode: `PE-REV00${id}`, hidden: false, hiddenReason: null, ...overrides,
})

describe('admin reviews', () => {
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

  const calls = () => mock.history.get.filter((r) => r.url === '/admin/reviews')

  it('lists reviews with the listing, booking and hidden state', async () => {
    mock.onGet('/admin/reviews').reply(200, page([review(1), review(2, { hidden: true, hiddenReason: 'Contains a phone number' })]))
    renderApp('/admin/reviews')

    const visible = within(await screen.findByRole('article', { name: 'Review PE-REV001' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(visible.getByText('FC Road Parking')).toBeInTheDocument()
    expect(visible.getByText('Easy to find')).toBeInTheDocument()
    expect(visible.getByRole('button', { name: 'Hide' })).toBeInTheDocument()
    expect(visible.queryByText('Hidden')).not.toBeInTheDocument()
    const hidden = within(screen.getByRole('article', { name: 'Review PE-REV002' }))
    expect(hidden.getByText('Hidden')).toBeInTheDocument()
    expect(hidden.getByText('Contains a phone number')).toBeInTheDocument()
    expect(hidden.getByRole('button', { name: 'Unhide' })).toBeInTheDocument()
  })

  it('filters by hidden state and searches', async () => {
    mock.onGet('/admin/reviews').reply(200, page([review(1)]))
    const u = userEvent.setup()
    renderApp('/admin/reviews')
    await screen.findByRole('article', { name: 'Review PE-REV001' })

    await u.selectOptions(screen.getByLabelText('Show'), 'Hidden only')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ hidden: true, page: 0, size: 20 }))
    await u.selectOptions(screen.getByLabelText('Show'), 'Visible only')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ hidden: false, page: 0, size: 20 }))
    await u.type(screen.getByLabelText('Search reviews'), 'phone')
    await u.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ hidden: false, q: 'phone', page: 0, size: 20 }))
  })

  it('hides a review with a reason', async () => {
    mock.onGet('/admin/reviews').replyOnce(200, page([review(1)]))
    mock.onGet('/admin/reviews').reply(200, page([review(1, { hidden: true, hiddenReason: 'Spam' })]))
    mock.onPost('/admin/reviews/1/hide').reply(200, review(1, { hidden: true, hiddenReason: 'Spam' }))
    const u = userEvent.setup()
    renderApp('/admin/reviews')

    await u.click(await screen.findByRole('button', { name: 'Hide' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Hide this review?' }))
    await u.type(dialog.getByLabelText('Reason'), 'Spam')
    await u.click(dialog.getByRole('button', { name: 'Hide' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Review hidden'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Spam' })
    expect(await screen.findByRole('button', { name: 'Unhide' })).toBeInTheDocument()
  })

  it('limits a hide reason to 300 characters', async () => {
    mock.onGet('/admin/reviews').reply(200, page([review(1)]))
    const u = userEvent.setup()
    renderApp('/admin/reviews')
    await u.click(await screen.findByRole('button', { name: 'Hide' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Hide this review?' }))

    await u.click(dialog.getByLabelText('Reason'))
    await u.paste('x'.repeat(301))
    await u.click(dialog.getByRole('button', { name: 'Hide' }))

    expect(await dialog.findByText('Use at most 300 characters')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('unhides a review', async () => {
    mock.onGet('/admin/reviews').replyOnce(200, page([review(2, { hidden: true, hiddenReason: 'Spam' })]))
    mock.onGet('/admin/reviews').reply(200, page([review(2)]))
    mock.onPost('/admin/reviews/2/unhide').reply(200, review(2))
    const u = userEvent.setup()
    renderApp('/admin/reviews')

    await u.click(await screen.findByRole('button', { name: 'Unhide' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Review is visible again'))
    expect(await screen.findByRole('button', { name: 'Hide' })).toBeInTheDocument()
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/admin/reviews').replyOnce(200, page([]))
    mock.onGet('/admin/reviews').reply(500, { code: 'INTERNAL', detail: 'Reviews are down' })
    const u = userEvent.setup()
    renderApp('/admin/reviews')
    expect(await screen.findByText('No reviews match these filters.')).toBeInTheDocument()

    await u.selectOptions(screen.getByLabelText('Show'), 'Hidden only')
    expect(await screen.findByText('Reviews are down')).toBeInTheDocument()
  })
})
