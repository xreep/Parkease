import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { ListingSummary } from '../../lib/owner'
import type { OwnerReviewDto } from '../../lib/reviews'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

const listings: ListingSummary[] = [
  { id: 7, title: 'FC Road Parking', status: 'APPROVED', cityName: 'Pune', stateName: 'Maharashtra', coverPhotoUrl: null, pricePerHour: 50, slotCount: 4, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z' },
  { id: 8, title: 'Baner Lot', status: 'PAUSED', cityName: 'Pune', stateName: 'Maharashtra', coverPhotoUrl: null, pricePerHour: 40, slotCount: 2, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z' },
]

const review = (id: number, overrides: Partial<OwnerReviewDto> = {}): OwnerReviewDto => ({
  id, rating: 4, comment: `Comment ${id}`, authorName: 'Rahul S.', createdAt: '2026-10-05T10:00:00Z', ownerReply: null, ownerRepliedAt: null,
  listingId: 7, listingTitle: 'FC Road Parking', bookingCode: `PE-REV00${id}`, ...overrides,
})

const page = (content: OwnerReviewDto[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('owner reviews', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/listings').reply(200, { content: listings, page: 0, size: 100, totalElements: 2, totalPages: 1 })
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  const reviewCalls = () => mock.history.get.filter((r) => r.url === '/owner/reviews')

  it('is reachable from the owner navigation', async () => {
    mock.onGet('/owner/reviews').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await user.click(await screen.findByRole('link', { name: 'Reviews' }))

    expect(await screen.findByRole('heading', { name: 'Reviews' })).toBeInTheDocument()
  })

  it('lists reviews across listings with the listing, booking code and rating', async () => {
    mock.onGet('/owner/reviews').reply(200, page([
      review(1),
      review(2, { listingId: 8, listingTitle: 'Baner Lot', rating: 2, comment: null, authorName: 'Asha K.' }),
    ]))
    renderApp('/owner/reviews')

    const first = await screen.findByRole('article', { name: 'Review PE-REV001' })
    expect(within(first).getByText('FC Road Parking')).toBeInTheDocument()
    expect(within(first).getByText('Rahul S.')).toBeInTheDocument()
    expect(within(first).getByText('Comment 1')).toBeInTheDocument()
    expect(within(first).getByRole('img', { name: '4 out of 5 stars' })).toBeInTheDocument()
    const second = screen.getByRole('article', { name: 'Review PE-REV002' })
    expect(within(second).getByText('Baner Lot')).toBeInTheDocument()
    expect(within(second).getByText('No comment')).toBeInTheDocument()
    expect(reviewCalls()[0].params).toEqual({ page: 0, size: 20 })
  })

  it('shows a hidden review with a badge, a note and no reply form', async () => {
    mock.onGet('/owner/reviews').reply(200, page([review(1, { hidden: true }), review(2)]))
    renderApp('/owner/reviews')

    const hidden = within(await screen.findByRole('article', { name: 'Review PE-REV001' }))
    expect(hidden.getByText('Hidden by ParkEase')).toBeInTheDocument()
    expect(hidden.getByText('This review is hidden and doesn’t count towards your rating.')).toBeInTheDocument()
    expect(hidden.queryByRole('button', { name: /reply/i })).not.toBeInTheDocument()
    const visible = within(screen.getByRole('article', { name: 'Review PE-REV002' }))
    expect(visible.queryByText('Hidden by ParkEase')).not.toBeInTheDocument()
    expect(visible.getByRole('button', { name: /reply/i })).toBeInTheDocument()
  })

  it('filters by listing', async () => {
    mock.onGet('/owner/reviews').reply((config) => [200, page(config.params.listingId === 8 ? [review(2, { listingId: 8, listingTitle: 'Baner Lot' })] : [review(1), review(2, { listingId: 8, listingTitle: 'Baner Lot' })])])
    const user = userEvent.setup()
    renderApp('/owner/reviews')
    await screen.findByRole('article', { name: 'Review PE-REV001' })

    await user.selectOptions(await screen.findByLabelText('Listing'), 'Baner Lot')

    await waitFor(() => expect(screen.queryByRole('article', { name: 'Review PE-REV001' })).not.toBeInTheDocument())
    expect(screen.getByRole('article', { name: 'Review PE-REV002' })).toBeInTheDocument()
    expect(reviewCalls().at(-1)!.params).toEqual({ listingId: 8, page: 0, size: 20 })
  })

  it('shows an empty state', async () => {
    mock.onGet('/owner/reviews').reply(200, page([]))
    renderApp('/owner/reviews')

    expect(await screen.findByText('No reviews yet.')).toBeInTheDocument()
  })

  it('pages through reviews', async () => {
    mock.onGet('/owner/reviews', { params: { page: 0, size: 20 } }).reply(200, page([review(1)], 2, 0))
    mock.onGet('/owner/reviews', { params: { page: 1, size: 20 } }).reply(200, page([review(2)], 2, 1))
    const user = userEvent.setup()
    renderApp('/owner/reviews')

    await screen.findByRole('article', { name: 'Review PE-REV001' })
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByRole('article', { name: 'Review PE-REV002' })).toBeInTheDocument()
  })

  it('replies to a review without a reply, with a 500 character counter', async () => {
    mock.onGet('/owner/reviews').replyOnce(200, page([review(1)]))
    mock.onGet('/owner/reviews').reply(200, page([review(1, { ownerReply: 'Thank you!', ownerRepliedAt: '2026-10-06T10:00:00Z' })]))
    mock.onPost('/owner/reviews/1/reply').reply(200, review(1, { ownerReply: 'Thank you!', ownerRepliedAt: '2026-10-06T10:00:00Z' }))
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/reviews', queryClient)

    const card = await screen.findByRole('article', { name: 'Review PE-REV001' })
    const reply = within(card).getByLabelText('Your reply')
    expect(reply).toHaveAttribute('maxlength', '500')
    expect(within(card).getByText('0/500')).toBeInTheDocument()
    await user.type(reply, 'Thank you!')
    expect(within(card).getByText('10/500')).toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Post reply' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Reply posted'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ reply: 'Thank you!' })
    const replied = await screen.findByText('Thank you!')
    expect(replied).toBeInTheDocument()
    expect(screen.queryByLabelText('Your reply')).not.toBeInTheDocument()
    await waitFor(() => {
      const keys = invalidate.mock.calls.map(([f]) => (f as { queryKey: unknown[] }).queryKey[0])
      expect(keys).toEqual(expect.arrayContaining(['owner', 'reviews']))
    })
  })

  it('needs some text for a reply', async () => {
    mock.onGet('/owner/reviews').reply(200, page([review(1)]))
    const user = userEvent.setup()
    renderApp('/owner/reviews')

    await user.click(await screen.findByRole('button', { name: 'Post reply' }))

    expect(await screen.findByText('Write a reply first')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('does not offer a reply to a review that has one', async () => {
    mock.onGet('/owner/reviews').reply(200, page([review(1, { ownerReply: 'Already answered', ownerRepliedAt: '2026-10-06T10:00:00Z' })]))
    renderApp('/owner/reviews')

    const card = await screen.findByRole('article', { name: 'Review PE-REV001' })
    expect(within(card).getByText('Already answered')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Post reply' })).not.toBeInTheDocument()
  })

  it('shows other failures once, inline, without a toast', async () => {
    mock.onGet('/owner/reviews').reply(200, page([review(1)]))
    mock.onPost('/owner/reviews/1/reply').reply(500, { code: 'INTERNAL', detail: 'Could not save the reply' })
    const user = userEvent.setup()
    renderApp('/owner/reviews')

    const card = await screen.findByRole('article', { name: 'Review PE-REV001' })
    await user.type(within(card).getByLabelText('Your reply'), 'Hello')
    await user.click(within(card).getByRole('button', { name: 'Post reply' }))

    expect(await within(card).findByText('Could not save the reply')).toBeInTheDocument()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('explains ALREADY_REPLIED and refreshes the list', async () => {
    mock.onGet('/owner/reviews').replyOnce(200, page([review(1)]))
    mock.onGet('/owner/reviews').reply(200, page([review(1, { ownerReply: 'Answered elsewhere', ownerRepliedAt: '2026-10-06T10:00:00Z' })]))
    mock.onPost('/owner/reviews/1/reply').reply(409, { code: 'ALREADY_REPLIED', detail: 'Already replied' })
    const user = userEvent.setup()
    renderApp('/owner/reviews')

    const card = await screen.findByRole('article', { name: 'Review PE-REV001' })
    await user.type(within(card).getByLabelText('Your reply'), 'Hello')
    await user.click(within(card).getByRole('button', { name: 'Post reply' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("You've already replied to this review."))
    expect(await screen.findByText('Answered elsewhere')).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
