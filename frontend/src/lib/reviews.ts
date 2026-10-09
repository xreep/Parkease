import { useInfiniteQuery, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Page } from './owner'

export type ReviewDto = {
  id: number
  rating: number
  comment: string | null
  /** "Rahul S." */
  authorName: string
  createdAt: string
  ownerReply: string | null
  ownerRepliedAt: string | null
}

export type ReviewSummaryDto = {
  avgRating: number
  reviewCount: number
  distribution: Record<'1' | '2' | '3' | '4' | '5', number>
}

export type ListingReviewsDto = { summary: ReviewSummaryDto; reviews: Page<ReviewDto> }
export type OwnerReviewDto = ReviewDto & { listingId: number; listingTitle: string; bookingCode: string }

export const REVIEW_COMMENT_MAX = 1000
export const REPLY_MAX = 500
export const REVIEWS_PAGE_SIZE = 10
export const OWNER_REVIEWS_PAGE_SIZE = 20

export const getListingReviews = async (listingId: number | string, page = 0, size = REVIEWS_PAGE_SIZE) =>
  (await api.get<ListingReviewsDto>(`/listings/${listingId}/reviews`, { params: { page, size } })).data
export const submitReview = async (bookingId: number, rating: number, comment?: string) =>
  (await api.post<ReviewDto>(`/bookings/${bookingId}/review`, comment ? { rating, comment } : { rating })).data
export const listOwnerReviews = async (listingId: number | undefined, page = 0, size = OWNER_REVIEWS_PAGE_SIZE) =>
  (await api.get<Page<OwnerReviewDto>>('/owner/reviews', { params: { ...(listingId !== undefined && { listingId }), page, size } })).data
export const replyToReview = async (id: number, reply: string) =>
  (await api.post<ReviewDto>(`/owner/reviews/${id}/reply`, { reply })).data

/** A listing's reviews, a page at a time ("Show more" appends); the summary comes with every page. */
export function useListingReviews(listingId: number | string) {
  return useInfiniteQuery({
    queryKey: ['reviews', 'listing', String(listingId)],
    queryFn: ({ pageParam }) => getListingReviews(listingId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.reviews.page + 1 < last.reviews.totalPages ? last.reviews.page + 1 : undefined),
  })
}

export function useOwnerReviews(listingId: number | undefined, page: number) {
  return useQuery({
    queryKey: ['owner', 'reviews', listingId ?? 'all', page],
    queryFn: () => listOwnerReviews(listingId, page),
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === (listingId ?? 'all') ? previous : undefined),
  })
}

/** A new review changes the listing's rating, the booking's review and the driver's pending reviews. */
export function invalidateReviewQueries(queryClient: QueryClient) {
  return Promise.all(
    ['reviews', 'listing', 'booking', 'bookings', 'search', 'driver'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
  ).then(() => undefined)
}

export function invalidateOwnerReviews(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['owner', 'reviews'] }),
    queryClient.invalidateQueries({ queryKey: ['reviews'] }),
  ]).then(() => undefined)
}

export function useInvalidateReviews() {
  const queryClient = useQueryClient()
  return () => invalidateReviewQueries(queryClient)
}

/** "4" for 4, "4.5" for 4.5. */
export const ratingText = (value: number) => value.toFixed(1)
