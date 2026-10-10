import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto } from '../../lib/bookings'
import type { ReviewDto } from '../../lib/reviews'
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
    reviewable: true, review: null, disputes: [], disputable: false,
    ...overrides,
  }
}

const posted: ReviewDto = {
  id: 55, rating: 4, comment: 'Easy to find and safe', authorName: 'Rahul V.', createdAt: '2026-10-09T08:00:00Z',
  ownerReply: null, ownerRepliedAt: null,
}

describe('reviewing a booking', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  const reviewPosts = () => mock.history.post.filter((r) => r.url === '/bookings/91/review')

  it('offers a star rating and a comment on a reviewable booking, and shows the posted review', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onPost('/bookings/91/review').reply(200, posted)
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/driver/bookings/91', queryClient)

    const card = await screen.findByRole('region', { name: 'Rate your parking' })
    expect(card).toHaveAttribute('id', 'review')
    const group = within(card).getByRole('radiogroup', { name: 'Your rating' })
    expect(within(group).getAllByRole('radio')).toHaveLength(5)

    await user.click(within(group).getByRole('radio', { name: '4 stars' }))
    await user.type(within(card).getByLabelText('Comment (optional)'), 'Easy to find and safe')
    expect(within(card).getByText('21/1000')).toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Submit review' }))

    const done = await screen.findByRole('region', { name: 'Your review' })
    expect(within(done).getByRole('img', { name: '4 out of 5 stars' })).toBeInTheDocument()
    expect(within(done).getByText('Easy to find and safe')).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    expect(JSON.parse(reviewPosts()[0].data)).toEqual({ rating: 4, comment: 'Easy to find and safe' })
    // Listing ratings and the booking itself change.
    await waitFor(() => {
      const keys = invalidate.mock.calls.map(([f]) => (f as { queryKey: unknown[] }).queryKey[0])
      expect(keys).toEqual(expect.arrayContaining(['reviews', 'listing', 'booking']))
    })
  })

  it('sends no comment when it is left empty', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onPost('/bookings/91/review').reply(200, { ...posted, rating: 5, comment: null })
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('radio', { name: '5 stars' }))
    await user.click(screen.getByRole('button', { name: 'Submit review' }))

    await screen.findByRole('region', { name: 'Your review' })
    expect(JSON.parse(reviewPosts()[0].data)).toEqual({ rating: 5 })
  })

  it('can be filled in from the keyboard', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    const first = await screen.findByRole('radio', { name: '1 star' })
    first.focus()
    await user.keyboard('{ArrowRight}{ArrowRight}')

    expect(screen.getByRole('radio', { name: '3 stars' })).toBeChecked()
  })

  it('needs a rating before it submits', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('button', { name: 'Submit review' }))

    expect(await screen.findByText('Choose a star rating')).toBeInTheDocument()
    expect(reviewPosts()).toHaveLength(0)
  })

  it('limits the comment to 1000 characters', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91')

    const comment = await screen.findByLabelText('Comment (optional)')
    expect(comment).toHaveAttribute('maxlength', '1000')
    fireEvent.change(comment, { target: { value: 'x'.repeat(40) } })
    expect(screen.getByText('40/1000')).toBeInTheDocument()
  })

  it('explains ALREADY_REVIEWED and then shows the review that exists', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking())
    mock.onGet('/bookings/91').reply(200, booking({ reviewable: false, review: posted }))
    mock.onPost('/bookings/91/review').reply(409, { code: 'ALREADY_REVIEWED', detail: 'Already reviewed' })
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('radio', { name: '4 stars' }))
    await user.click(screen.getByRole('button', { name: 'Submit review' }))

    const done = await screen.findByRole('region', { name: 'Your review' })
    expect(within(done).getByText('Easy to find and safe')).toBeInTheDocument()
    // The form (and its inline message) is gone by now; the toast keeps the explanation.
    expect(toast.error).toHaveBeenCalledWith("You've already reviewed this booking.")
  })

  it('shows the server message when the booking is no longer reviewable, even after the form goes away', async () => {
    mock.onGet('/bookings/91').replyOnce(200, booking())
    mock.onGet('/bookings/91').reply(200, booking({ reviewable: false }))
    mock.onPost('/bookings/91/review').reply(409, { code: 'NOT_REVIEWABLE', detail: 'Only completed bookings can be reviewed' })
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('radio', { name: '2 stars' }))
    await user.click(screen.getByRole('button', { name: 'Submit review' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only completed bookings can be reviewed'))
    // The refetch says it can't be reviewed: the panel is replaced, the toast is what is left.
    await waitFor(() => expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument())
    expect(screen.queryByRole('region', { name: 'Rate your parking' })).not.toBeInTheDocument()
  })

  it('shows other failures inline without a toast', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    mock.onPost('/bookings/91/review').reply(500, { code: 'INTERNAL', detail: 'Try again later' })
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('radio', { name: '2 stars' }))
    await user.click(screen.getByRole('button', { name: 'Submit review' }))

    expect(await screen.findByText('Try again later')).toBeInTheDocument()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('links the missing-rating message to the stars and announces it', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    const user = userEvent.setup()
    renderApp('/driver/bookings/91')

    await user.click(await screen.findByRole('button', { name: 'Submit review' }))

    const alert = await screen.findByText('Choose a star rating')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(screen.getByRole('radiogroup', { name: 'Your rating' })).toHaveAttribute('aria-describedby', alert.id)
    expect(alert.id).not.toBe('')
  })

  it('gives each star a touch target of at least 44px', async () => {
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91')

    for (const radio of await screen.findAllByRole('radio')) {
      expect(radio.closest('label')).toHaveClass('h-11', 'w-11')
    }
  })

  it('shows an existing review read-only', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ reviewable: false, review: { ...posted, ownerReply: 'Glad you liked it', ownerRepliedAt: '2026-10-10T08:00:00Z' } }))
    renderApp('/driver/bookings/91')

    const done = await screen.findByRole('region', { name: 'Your review' })
    expect(within(done).getByText('Easy to find and safe')).toBeInTheDocument()
    expect(within(done).getByText('Reply from the owner')).toBeInTheDocument()
    expect(within(done).getByText('Glad you liked it')).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit review' })).not.toBeInTheDocument()
  })

  it('has no review card when the booking is neither reviewable nor reviewed', async () => {
    mock.onGet('/bookings/91').reply(200, booking({ status: 'CONFIRMED', reviewable: false }))
    renderApp('/driver/bookings/91')

    await screen.findByText('PE-8KQ2M4')
    expect(screen.queryByRole('region', { name: 'Rate your parking' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Your review' })).not.toBeInTheDocument()
  })

  it('scrolls to the review card when the URL has #review', async () => {
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91#review')

    await screen.findByRole('region', { name: 'Rate your parking' })
    await waitFor(() => expect(scroll).toHaveBeenCalled())
    expect((scroll.mock.contexts[0] as HTMLElement).id).toBe('review')
  })

  it('does not scroll without the hash', async () => {
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    mock.onGet('/bookings/91').reply(200, booking())
    renderApp('/driver/bookings/91')

    await screen.findByRole('region', { name: 'Rate your parking' })
    expect(scroll).not.toHaveBeenCalled()
  })
})
