import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { OwnerBookingDto } from '../../lib/bookings'
import { formatDateTime } from '../../lib/format'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

function ownerBooking(overrides: Partial<OwnerBookingDto> = {}): OwnerBookingDto {
  return {
    id: 5, bookingCode: 'PE-REQ001', status: 'AWAITING_APPROVAL', listingId: 7, listingTitle: 'FC Road Parking', slotLabel: 'A-3',
    startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
    driverFirstName: 'Rahul', baseAmount: 240, approvalDeadline: new Date(Date.now() + 5 * 3_600_000).toISOString(),
    createdAt: '2026-10-09T08:00:00Z', ...overrides,
  }
}

const page = (content: OwnerBookingDto[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('owner bookings', () => {
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

  const listCalls = () => mock.history.get.filter((r) => r.url === '/owner/bookings')

  it('shows booking requests with the owner\'s earnings, driver and response deadline', async () => {
    const deadline = new Date(Date.now() + 5 * 3_600_000).toISOString()
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking({ approvalDeadline: deadline })]))
    renderApp('/owner/bookings')

    const card = await screen.findByRole('article', { name: 'PE-REQ001' })
    expect(within(card).getByText('FC Road Parking')).toBeInTheDocument()
    expect(within(card).getByText(/Slot A-3/)).toBeInTheDocument()
    expect(within(card).getByText(/Rahul/)).toBeInTheDocument()
    expect(within(card).getByText(/MH12AB1234/)).toBeInTheDocument()
    expect(within(card).getByText('You earn ₹240')).toBeInTheDocument()
    const respondBy = within(card).getByText(`Respond by ${formatDateTime(deadline)}`)
    expect(respondBy.className).not.toMatch(/red/)
    expect(listCalls()[0].params).toEqual({ view: 'requests', page: 0, size: 20 })
    expect(screen.getByRole('tab', { name: 'Requests' })).toHaveAttribute('aria-selected', 'true')
  })

  it('highlights a deadline less than 30 minutes away', async () => {
    const deadline = new Date(Date.now() + 10 * 60_000).toISOString()
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking({ approvalDeadline: deadline })]))
    renderApp('/owner/bookings')

    const respondBy = await screen.findByText(`Respond by ${formatDateTime(deadline)}`)
    expect(respondBy.className).toMatch(/red/)
  })

  const invalidatedKeys = (spy: { mock: { calls: unknown[][] } }) =>
    spy.mock.calls.map(([filters]) => (filters as { queryKey: unknown[] }).queryKey[0])

  it('approves a request', async () => {
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking()]))
    mock.onPost('/owner/bookings/5/approve').reply(200, ownerBooking({ status: 'CONFIRMED' }))
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/bookings', queryClient)

    await user.click(await screen.findByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking approved'))
    expect(mock.history.post.map((r) => r.url)).toEqual(['/owner/bookings/5/approve'])
    // The driver's booking, lists and the availability shown by quotes and search change too.
    await waitFor(() => expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining(['owner', 'bookings', 'booking', 'quote', 'search'])))
    // The list is refetched after the decision.
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(1))
  })

  it('declines a request with a reason', async () => {
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking()]))
    mock.onPost('/owner/bookings/5/reject').reply(200, ownerBooking({ status: 'REJECTED' }))
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/bookings', queryClient)

    await user.click(await screen.findByRole('button', { name: 'Decline' }))
    const dialog = await screen.findByRole('dialog', { name: 'Decline this booking?' })
    await user.type(within(dialog).getByLabelText('Reason'), 'Space is closed that day')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Booking declined — the driver will be refunded'))
    const post = mock.history.post.find((r) => r.url === '/owner/bookings/5/reject')
    expect(JSON.parse(post!.data)).toEqual({ reason: 'Space is closed that day' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining(['owner', 'booking', 'bookings', 'quote', 'search'])))
  })

  it('shows why a decline failed and still refreshes the list', async () => {
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking()]))
    mock.onPost('/owner/bookings/5/reject').reply(409, { code: 'INVALID_STATUS', detail: 'This booking is no longer awaiting approval' })
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/bookings', queryClient)

    await user.click(await screen.findByRole('button', { name: 'Decline' }))
    const dialog = await screen.findByRole('dialog', { name: 'Decline this booking?' })
    await user.type(within(dialog).getByLabelText('Reason'), 'Closed that day')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))

    expect(toast.success).not.toHaveBeenCalled()
    await waitFor(() => expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining(['owner', 'bookings', 'booking'])))
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(1))
  })

  it('requires a reason to decline', async () => {
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking()]))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await user.click(await screen.findByRole('button', { name: 'Decline' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))

    expect(await within(dialog).findByText('Please give a reason')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('reports a request that can no longer be approved and refreshes the list', async () => {
    mock.onGet('/owner/bookings').reply(200, page([ownerBooking()]))
    mock.onPost('/owner/bookings/5/approve').reply(409, { code: 'INVALID_STATUS', detail: 'This booking is no longer awaiting approval' })
    const user = userEvent.setup()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/bookings', queryClient)

    await user.click(await screen.findByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining(['booking', 'quote', 'search'])))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('This booking is no longer awaiting approval'))
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(1))
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('switches between requests, upcoming and past with their own empty states', async () => {
    mock.onGet('/owner/bookings', { params: { view: 'requests', page: 0, size: 20 } }).reply(200, page([]))
    mock.onGet('/owner/bookings', { params: { view: 'upcoming', page: 0, size: 20 } }).reply(
      200,
      page([ownerBooking({ id: 6, bookingCode: 'PE-UP0001', status: 'CONFIRMED', approvalDeadline: null })]),
    )
    mock.onGet('/owner/bookings', { params: { view: 'past', page: 0, size: 20 } }).reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    expect(await screen.findByText('No booking requests right now.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Upcoming' }))
    const card = await screen.findByRole('article', { name: 'PE-UP0001' })
    expect(within(card).getByText('Confirmed')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(within(card).queryByText(/Respond by/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Past' }))
    expect(await screen.findByText('No past bookings yet.')).toBeInTheDocument()
    expect(listCalls().map((r) => r.params.view)).toEqual(['requests', 'upcoming', 'past'])
  })

  it('does not show the previous tab\'s requests or a wrong empty state while the next tab loads', async () => {
    mock.onGet('/owner/bookings', { params: { view: 'requests', page: 0, size: 20 } }).reply(200, page([ownerBooking()]))
    mock.onGet('/owner/bookings', { params: { view: 'upcoming', page: 0, size: 20 } }).reply(() => new Promise(() => {}))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await screen.findByRole('article', { name: 'PE-REQ001' })
    await user.click(screen.getByRole('tab', { name: 'Upcoming' }))

    await waitFor(() => expect(screen.queryByRole('article', { name: 'PE-REQ001' })).not.toBeInTheDocument())
    expect(screen.queryByText('No upcoming bookings.')).not.toBeInTheDocument()
    expect(screen.queryByText('No booking requests right now.')).not.toBeInTheDocument()
  })

  it('shows "No upcoming bookings." on an empty upcoming tab', async () => {
    mock.onGet('/owner/bookings').reply(200, page([]))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await user.click(await screen.findByRole('tab', { name: 'Upcoming' }))
    expect(await screen.findByText('No upcoming bookings.')).toBeInTheDocument()
  })

  it('has a Bookings tab in the owner dashboard', async () => {
    mock.onGet('/owner/bookings').reply(200, page([]))
    renderApp('/owner/bookings')

    const nav = await screen.findByRole('navigation', { name: 'Sections' })
    expect(within(nav).getByRole('link', { name: 'Bookings' })).toHaveAttribute('href', '/owner/bookings')
  })
})

describe('owner home booking requests card', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/profile').reply(200, { verificationStatus: 'VERIFIED', documentType: null, rejectionReason: null })
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  })

  afterEach(() => mock.restore())

  it('shows how many requests are waiting, with a link to review them', async () => {
    mock.onGet('/owner/bookings').reply(200, {
      content: [ownerBooking()], page: 0, size: 1, totalElements: 3, totalPages: 3,
    })
    renderApp('/owner')

    expect(await screen.findByText('3 booking requests waiting')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review requests' })).toHaveAttribute('href', '/owner/bookings')
    expect(mock.history.get.find((r) => r.url === '/owner/bookings')?.params).toMatchObject({ view: 'requests' })
  })

  it('says nothing when there are no requests', async () => {
    mock.onGet('/owner/bookings').reply(200, { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
    renderApp('/owner')

    await screen.findByText('Your listings')
    await waitFor(() => expect(mock.history.get.some((r) => r.url === '/owner/bookings')).toBe(true))
    expect(screen.queryByText(/booking requests? waiting/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Review requests' })).not.toBeInTheDocument()
  })
})
