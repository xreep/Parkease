import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient } from '@tanstack/react-query'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { ListingSummary } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = {
  id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null,
  role: 'OWNER', emailVerified: true, avatarUrl: null,
}

const live: ListingSummary = {
  id: 7, title: 'FC Road Parking', status: 'APPROVED', cityName: 'Pune', stateName: 'Maharashtra',
  coverPhotoUrl: '/files/a.jpg', pricePerHour: 50, slotCount: 12, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z',
}
const rejected: ListingSummary = {
  id: 8, title: 'Baner Lot', status: 'REJECTED', cityName: 'Pune', stateName: 'Maharashtra',
  coverPhotoUrl: null, pricePerHour: null, slotCount: 1, rejectionReason: 'Photos are blurry', updatedAt: '2026-10-05T10:00:00Z',
}

const page = (content: ListingSummary[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('my listings', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  it('lists the owner\'s listings with status, price and actions', async () => {
    mock.onGet('/owner/listings').reply(200, page([live, rejected]))
    renderApp('/owner/listings')

    expect(await screen.findByRole('heading', { name: 'My listings' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a listing' })).toHaveAttribute('href', '/owner/listings/new')
    const liveCard = within(await screen.findByRole('article', { name: 'FC Road Parking' }))
    expect(liveCard.getByText('Live')).toBeInTheDocument()
    expect(liveCard.getByText('Pune, Maharashtra')).toBeInTheDocument()
    expect(liveCard.getByText('₹50/hr')).toBeInTheDocument()
    expect(liveCard.getByText('12 slots')).toBeInTheDocument()
    expect(liveCard.getByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/owner/listings/7/edit?step=1')
    expect(liveCard.getByRole('link', { name: 'Blocked times' })).toHaveAttribute('href', '/owner/listings/7/blocks')

    const rejectedCard = within(screen.getByRole('article', { name: 'Baner Lot' }))
    expect(rejectedCard.getByText('Changes needed')).toBeInTheDocument()
    expect(rejectedCard.getByText('Photos are blurry')).toBeInTheDocument()
    expect(rejectedCard.getByText('1 slot')).toBeInTheDocument()
    expect(rejectedCard.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument()
  })

  it('pauses a live listing', async () => {
    mock.onGet('/owner/listings').replyOnce(200, page([live, rejected])).onGet('/owner/listings').reply(200, page([{ ...live, status: 'PAUSED' }, rejected]))
    mock.onPost('/owner/listings/7/pause').reply(200, {})
    renderApp('/owner/listings')

    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))

    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(mock.history.post[0].url).toBe('/owner/listings/7/pause')
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeInTheDocument()
    expect(toast.success).toHaveBeenCalledWith('Listing paused')
  })

  it('marks the cached listing detail stale after pausing so the wizard does not show old status', async () => {
    mock.onGet('/owner/listings').reply(200, page([live]))
    mock.onPost('/owner/listings/7/pause').reply(200, {})
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
    client.setQueryData(['owner', 'listing', 7], { id: 7, status: 'APPROVED' })
    renderApp('/owner/listings', client)

    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))

    await waitFor(() => expect(client.getQueryState(['owner', 'listing', 7])?.isInvalidated).toBe(true))
  })

  it('marks the cached listing detail stale after resuming', async () => {
    mock.onGet('/owner/listings').reply(200, page([{ ...live, status: 'PAUSED' }]))
    mock.onPost('/owner/listings/7/resume').reply(200, {})
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
    client.setQueryData(['owner', 'listing', 7], { id: 7, status: 'PAUSED' })
    renderApp('/owner/listings', client)

    await userEvent.click(await screen.findByRole('button', { name: 'Resume' }))

    await waitFor(() => expect(client.getQueryState(['owner', 'listing', 7])?.isInvalidated).toBe(true))
  })

  it('drops the cached listing detail after deleting', async () => {
    mock.onGet('/owner/listings').reply(200, page([rejected]))
    mock.onDelete('/owner/listings/8').reply(204)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
    client.setQueryData(['owner', 'listing', 8], { id: 8, status: 'REJECTED' })
    renderApp('/owner/listings', client)

    await userEvent.click(within(await screen.findByRole('article', { name: 'Baner Lot' })).getByRole('button', { name: 'Delete' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete listing' }))

    await waitFor(() => expect(client.getQueryData(['owner', 'listing', 8])).toBeUndefined())
  })

  it('resumes a paused listing', async () => {
    mock.onGet('/owner/listings').reply(200, page([{ ...live, status: 'PAUSED' }]))
    mock.onPost('/owner/listings/7/resume').reply(200, {})
    renderApp('/owner/listings')

    await userEvent.click(await screen.findByRole('button', { name: 'Resume' }))

    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(mock.history.post[0].url).toBe('/owner/listings/7/resume')
  })

  it('shows the server message when pausing fails', async () => {
    mock.onGet('/owner/listings').reply(200, page([live]))
    mock.onPost('/owner/listings/7/pause').reply(409, { code: 'INVALID_STATUS', detail: 'Only approved listings can be paused' })
    renderApp('/owner/listings')

    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only approved listings can be paused'))
  })

  it('asks before deleting and only offers delete when allowed', async () => {
    const pending = { ...live, id: 9, title: 'Pending Lot', status: 'PENDING_REVIEW' as const }
    mock.onGet('/owner/listings').reply(200, page([live, rejected, pending]))
    mock.onDelete('/owner/listings/8').reply(204)
    renderApp('/owner/listings')

    expect(within(await screen.findByRole('article', { name: 'FC Road Parking' })).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('article', { name: 'Baner Lot' })).getByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog', { name: "Delete Baner Lot? This can't be undone." })
    expect(mock.history.delete).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete listing' }))

    await waitFor(() => expect(mock.history.delete).toHaveLength(1))
    expect(toast.success).toHaveBeenCalledWith('Listing deleted')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('keeps the dialog open with the server message when delete fails', async () => {
    mock.onGet('/owner/listings').reply(200, page([rejected]))
    mock.onDelete('/owner/listings/8').reply(409, { code: 'INVALID_STATUS', detail: 'Pause the listing before deleting it' })
    renderApp('/owner/listings')

    await userEvent.click(within(await screen.findByRole('article', { name: 'Baner Lot' })).getByRole('button', { name: 'Delete' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete listing' }))

    expect(await within(screen.getByRole('dialog')).findByText('Pause the listing before deleting it')).toBeInTheDocument()
  })

  it('pages through the listings', async () => {
    mock.onGet('/owner/listings', { params: { page: 0, size: 20 } }).reply(200, page([live], 2, 0))
    mock.onGet('/owner/listings', { params: { page: 1, size: 20 } }).reply(200, page([rejected], 2, 1))
    renderApp('/owner/listings')

    expect(await screen.findByRole('article', { name: 'FC Road Parking' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByRole('article', { name: 'Baner Lot' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByRole('article', { name: 'FC Road Parking' })).toBeInTheDocument()
  })

  it('hides pagination for a single page', async () => {
    mock.onGet('/owner/listings').reply(200, page([live]))
    renderApp('/owner/listings')

    await screen.findByRole('article', { name: 'FC Road Parking' })
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    mock.onGet('/owner/listings').reply(200, page([]))
    renderApp('/owner/listings')

    expect(await screen.findByText("You haven't added any parking yet.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add your first listing' })).toHaveAttribute('href', '/owner/listings/new')
  })
})
