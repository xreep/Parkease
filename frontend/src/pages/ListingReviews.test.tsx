import '@testing-library/jest-dom/vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { ListingReviewsDto, ReviewDto } from '../lib/reviews'
import type { PublicListingDto } from '../lib/search'
import { renderApp } from '../test/renderApp'

vi.mock('../components/owner/LocationPicker', () => ({
  LocationPicker: () => <div data-testid="picker" />,
}))

const listing: PublicListingDto = {
  id: 7, title: 'Metro Hub Parking', description: null, listingType: 'METRO',
  address: 'FC Road, Shivajinagar', pincode: '411005', lat: 18.5204, lng: 73.8567, cityName: 'Pune', citySlug: 'pune',
  stateName: 'Maharashtra', stateSlug: 'maharashtra', photos: [], amenities: [], rules: null,
  cancellationPolicy: 'MODERATE', autoApprove: true, open24x7: true, hours: [],
  pricePerHour: 40, pricePerDay: null, pricePerMonth: null,
  slotSummary: { twoWheeler: 2, fourWheeler: 3, small: 1, medium: 3, large: 1 },
  avgRating: 4.5, reviewCount: 12, ownerFirstName: 'Priya',
}

const review = (id: number, overrides: Partial<ReviewDto> = {}): ReviewDto => ({
  id, rating: 5, comment: `Comment number ${id}`, authorName: `Driver ${id} S.`, createdAt: '2026-10-05T10:00:00Z',
  ownerReply: null, ownerRepliedAt: null, ...overrides,
})

const distribution = { '1': 0, '2': 1, '3': 1, '4': 3, '5': 7 }

function reviewsBody(content: ReviewDto[], pageNo = 0, totalPages = 2, reviewCount = 12): ListingReviewsDto {
  return {
    summary: { avgRating: 4.5, reviewCount, distribution },
    reviews: { content, page: pageNo, size: 10, totalElements: reviewCount, totalPages },
  }
}

describe('listing reviews', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
    mock.onGet('/listings/7').reply(200, listing)
    mock.onGet('/listings/7/quote').reply(200, { available: true, reason: null, freeSlots: 3, totalSlots: 5, quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 89.44, breakdown: '2 hours' } })
    mock.onGet('/listings/7/availability').reply(200, { listingId: 7, days: [] })
  })

  afterEach(() => mock.restore())

  const reviewCalls = () => mock.history.get.filter((r) => r.url === '/listings/7/reviews')

  it('shows the rating summary with a distribution bar for every star count', async () => {
    mock.onGet('/listings/7/reviews').reply(200, reviewsBody([review(1), review(2)]))
    renderApp('/listings/7')

    const section = await screen.findByRole('region', { name: 'Reviews' })
    expect(await within(section).findByText('4.5')).toBeInTheDocument()
    expect(within(section).getByText('12 reviews')).toBeInTheDocument()
    expect(within(section).getByRole('img', { name: '4.5 out of 5 stars' })).toBeInTheDocument()

    const rows = within(within(section).getByRole('list', { name: 'Rating distribution' })).getAllByRole('listitem')
    expect(rows).toHaveLength(5)
    // Highest rating first, each with its own count.
    expect(rows[0]).toHaveTextContent('5 stars')
    expect(rows[0]).toHaveTextContent('7')
    expect(rows[1]).toHaveTextContent('4 stars')
    expect(rows[1]).toHaveTextContent('3')
    expect(rows[4]).toHaveTextContent('1 star')
    expect(rows[4]).toHaveTextContent('0')
    expect(within(rows[0]).getByRole('presentation')).toHaveStyle({ width: '58%' })
    expect(reviewCalls()[0].params).toEqual({ page: 0, size: 10 })
  })

  it('lists reviews with the author, rating, comment and the owner reply', async () => {
    mock.onGet('/listings/7/reviews').reply(200, reviewsBody([
      review(1, { rating: 4, comment: 'Easy to find', authorName: 'Rahul S.' }),
      review(2, { comment: null, ownerReply: 'Thanks for parking with us', ownerRepliedAt: '2026-10-06T10:00:00Z' }),
    ]))
    renderApp('/listings/7')

    const first = await screen.findByRole('article', { name: 'Review by Rahul S.' })
    expect(within(first).getByText('Easy to find')).toBeInTheDocument()
    expect(within(first).getByRole('img', { name: '4 out of 5 stars' })).toBeInTheDocument()
    expect(within(first).queryByText(/Reply from the owner/)).not.toBeInTheDocument()

    const second = screen.getByRole('article', { name: 'Review by Driver 2 S.' })
    expect(within(second).getByText('Reply from the owner')).toBeInTheDocument()
    expect(within(second).getByText('Thanks for parking with us')).toBeInTheDocument()
  })

  it('renders review text literally, never as HTML', async () => {
    mock.onGet('/listings/7/reviews').reply(200, reviewsBody([
      review(1, { comment: '<img src=x onerror=alert(1)> <b>bold</b>', authorName: '<i>Eve</i> S.', ownerReply: '<script>alert(2)</script>', ownerRepliedAt: '2026-10-06T10:00:00Z' }),
    ], 0, 1, 1))
    renderApp('/listings/7')

    const card = await screen.findByRole('article', { name: 'Review by <i>Eve</i> S.' })
    expect(within(card).getByText('<img src=x onerror=alert(1)> <b>bold</b>')).toBeInTheDocument()
    expect(within(card).getByText('<script>alert(2)</script>')).toBeInTheDocument()
    expect(card.querySelector('img, b, i, script')).toBeNull()
  })

  it('loads the next page of reviews on "Show more" and hides the button on the last page', async () => {
    mock.onGet('/listings/7/reviews', { params: { page: 0, size: 10 } }).reply(200, reviewsBody([review(1), review(2)], 0, 2))
    mock.onGet('/listings/7/reviews', { params: { page: 1, size: 10 } }).reply(200, reviewsBody([review(3)], 1, 2))
    const user = userEvent.setup()
    renderApp('/listings/7')

    await screen.findByRole('article', { name: 'Review by Driver 1 S.' })
    expect(screen.queryByRole('article', { name: 'Review by Driver 3 S.' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show more reviews' }))

    expect(await screen.findByRole('article', { name: 'Review by Driver 3 S.' })).toBeInTheDocument()
    // The earlier reviews stay.
    expect(screen.getByRole('article', { name: 'Review by Driver 1 S.' })).toBeInTheDocument()
    expect(reviewCalls().map((r) => r.params.page)).toEqual([0, 1])
    expect(screen.queryByRole('button', { name: 'Show more reviews' })).not.toBeInTheDocument()
  })

  it('shows an empty state when the listing has no reviews', async () => {
    mock.onGet('/listings/7/reviews').reply(200, {
      summary: { avgRating: 0, reviewCount: 0, distribution: { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 } },
      reviews: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
    })
    renderApp('/listings/7')

    expect(await screen.findByText('No reviews yet')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Rating distribution' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Show more reviews' })).not.toBeInTheDocument()
  })

  it('says so when the reviews cannot be loaded, without hiding the listing', async () => {
    mock.onGet('/listings/7/reviews').reply(500, { code: 'INTERNAL', detail: 'boom' })
    renderApp('/listings/7')

    expect(await screen.findByText('Could not load reviews.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Metro Hub Parking' })).toBeInTheDocument()
  })
})
