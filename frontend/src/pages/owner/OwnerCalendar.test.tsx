import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { OwnerCalendarDto } from '../../lib/ownerDashboard'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

const listings = [
  { id: 7, title: 'FC Road Parking', status: 'APPROVED', cityName: 'Pune', stateName: 'Maharashtra', coverPhotoUrl: null, pricePerHour: 50, slotCount: 2, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z' },
  { id: 8, title: 'Baner Lot', status: 'PAUSED', cityName: 'Pune', stateName: 'Maharashtra', coverPhotoUrl: null, pricePerHour: 40, slotCount: 1, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z' },
]

// Friday 9 Oct 2026, 11:30 IST: the week is Monday 5 Oct to Sunday 11 Oct.
const NOW = new Date('2026-10-09T06:00:00Z')

function calendar(from: string, to: string, overrides: Partial<OwnerCalendarDto> = {}): OwnerCalendarDto {
  return {
    listingId: 7, from, to,
    slots: [{ id: 1, label: 'A-1' }, { id: 2, label: 'A-2' }],
    bookings: [
      // Wed 7 Oct 10:00-12:00 IST in slot A-2.
      { id: 11, bookingCode: 'PE-CAL001', slotId: 2, startTime: '2026-10-07T04:30:00Z', endTime: '2026-10-07T06:30:00Z', status: 'CONFIRMED', driverName: 'Rahul' },
      // Sat 10 Oct 22:00 IST to Sun 11 Oct 02:00 IST in slot A-1: crosses midnight.
      { id: 12, bookingCode: 'PE-CAL002', slotId: 1, startTime: '2026-10-10T16:30:00Z', endTime: '2026-10-10T20:30:00Z', status: 'AWAITING_APPROVAL', driverName: 'Asha' },
    ],
    blocks: [
      // Mon 5 Oct 09:00-12:00 IST on slot A-1 only.
      { id: 21, slotId: 1, startTime: '2026-10-05T03:30:00Z', endTime: '2026-10-05T06:30:00Z', reason: 'Painting' },
      // Thu 8 Oct the whole listing.
      { id: 22, slotId: null, startTime: '2026-10-07T18:30:00Z', endTime: '2026-10-08T18:30:00Z', reason: null },
    ],
    ...overrides,
  }
}

describe('owner calendar', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/listings').reply(200, { content: listings, page: 0, size: 100, totalElements: 2, totalPages: 1 })
    mock.onGet('/owner/calendar').reply((config) => [200, calendar(config.params.from, config.params.to)])
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
  })

  const calls = () => mock.history.get.filter((r) => r.url === '/owner/calendar')

  const percent = (css: string) => Number.parseFloat(css)

  /** The cell of the slot row for the n-th day of the week (0 = Monday). */
  const cell = (slot: string, day: number) =>
    within(screen.getByRole('row', { name: new RegExp(`^${slot}\\b`) })).getAllByRole('cell')[day]

  it('is reachable from the owner navigation', async () => {
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await user.click(await screen.findByRole('link', { name: 'Calendar' }))

    expect(await screen.findByRole('heading', { name: 'Calendar' })).toBeInTheDocument()
  })

  it('asks for the first listing and the current Monday-to-Sunday week', async () => {
    renderApp('/owner/calendar')

    await screen.findByRole('table', { name: 'Slots by day' })
    expect(calls()[0].params).toEqual({ listingId: 7, from: '2026-10-05', to: '2026-10-11' })
    expect(screen.getByLabelText('Listing')).toHaveValue('7')
    expect(screen.getByText('5 Oct – 11 Oct 2026')).toBeInTheDocument()
  })

  it('has a row per slot and a column per day', async () => {
    renderApp('/owner/calendar')

    await screen.findByRole('table', { name: 'Slots by day' })
    const headers = screen.getAllByRole('columnheader')
    expect(headers.map((h) => h.textContent)).toEqual(['Slot', 'Mon 5', 'Tue 6', 'Wed 7', 'Thu 8', 'Fri 9', 'Sat 10', 'Sun 11'])
    expect(screen.getByRole('row', { name: /^A-1/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /^A-2/ })).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /^A-1/ })).getAllByRole('cell')).toHaveLength(7)
  })

  it('puts a booking bar in its slot row and its day, with the code', async () => {
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    const bar = within(cell('A-2', 2)).getByText('PE-CAL001')
    // 10:00-12:00 IST is 10/24 into the day and two hours wide.
    const holder = bar.closest('[data-booking]') as HTMLElement
    expect(percent(holder.style.left)).toBeCloseTo((10 / 24) * 100)
    expect(percent(holder.style.width)).toBeCloseTo((2 / 24) * 100)
    expect(holder).toHaveAttribute('data-status', 'CONFIRMED')
    expect(holder).toHaveAttribute('title', expect.stringContaining('Rahul'))
    // Not in the other slot or on other days.
    expect(within(screen.getByRole('row', { name: /^A-1/ })).queryByText('PE-CAL001')).not.toBeInTheDocument()
    expect(within(cell('A-2', 1)).queryByText('PE-CAL001')).not.toBeInTheDocument()
  })

  it('gives each bar a text alternative with its status and driver, not only a tooltip', async () => {
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    const bar = within(cell('A-2', 2)).getByText('PE-CAL001').closest('[data-booking]') as HTMLElement
    expect(within(bar).getByText('Confirmed, Rahul', { exact: false })).toHaveClass('sr-only')
    const waiting = within(cell('A-1', 5)).getByText('PE-CAL002').closest('[data-booking]') as HTMLElement
    expect(within(waiting).getByText('Waiting for owner, Asha', { exact: false })).toHaveClass('sr-only')
    // The row's accessible name carries it too.
    expect(screen.getByRole('row', { name: /A-2.*PE-CAL001.*Confirmed, Rahul/ })).toBeInTheDocument()
  })

  it('splits a booking that crosses midnight over both days (in IST)', async () => {
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    const saturday = within(cell('A-1', 5)).getByText('PE-CAL002').closest('[data-booking]') as HTMLElement
    expect(percent(saturday.style.left)).toBeCloseTo((22 / 24) * 100)
    expect(percent(saturday.style.width)).toBeCloseTo((2 / 24) * 100)
    const sunday = within(cell('A-1', 6)).getByText('PE-CAL002').closest('[data-booking]') as HTMLElement
    expect(percent(sunday.style.left)).toBe(0)
    expect(percent(sunday.style.width)).toBeCloseTo((2 / 24) * 100)
    expect(saturday).toHaveAttribute('data-status', 'AWAITING_APPROVAL')
  })

  it('shows blocks hatched: slot blocks in their slot, listing-wide blocks in every slot', async () => {
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    const painting = within(cell('A-1', 0)).getByTitle(/Painting/)
    expect(painting).toHaveAttribute('data-block')
    expect(within(cell('A-2', 0)).queryByTitle(/Painting/)).not.toBeInTheDocument()
    // Thursday 8 Oct in IST (the block runs from Wed 24:00 IST for a day).
    expect(within(cell('A-1', 3)).getByTitle(/Blocked/)).toHaveAttribute('data-block')
    expect(within(cell('A-2', 3)).getByTitle(/Blocked/)).toHaveAttribute('data-block')
    expect(within(cell('A-2', 2)).queryByTitle(/Blocked/)).not.toBeInTheDocument()
  })

  it('explains the colours with a legend', async () => {
    renderApp('/owner/calendar')

    const legend = await screen.findByRole('list', { name: 'Calendar legend' })
    const text = within(legend).getAllByRole('listitem').map((li) => li.textContent)
    expect(text).toEqual(expect.arrayContaining(['Confirmed', 'Active', 'Waiting for owner', 'Completed', 'Blocked']))
  })

  it('keeps the horizontal scroll inside the grid', async () => {
    renderApp('/owner/calendar')

    const table = await screen.findByRole('table', { name: 'Slots by day' })
    expect(table.parentElement).toHaveClass('overflow-x-auto')
  })

  it('lets the week controls wrap on a narrow screen', async () => {
    renderApp('/owner/calendar')

    const label = await screen.findByText('5 Oct – 11 Oct 2026')
    expect(label).toHaveClass('sm:min-w-40')
    expect(label).not.toHaveClass('min-w-40')
    expect(label.parentElement).toHaveClass('flex-wrap')
  })

  it('moves a week at a time and back to this week', async () => {
    const user = userEvent.setup()
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    await user.click(screen.getByRole('button', { name: 'Next week' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ listingId: 7, from: '2026-10-12', to: '2026-10-18' }))
    expect(await screen.findByText('12 Oct – 18 Oct 2026')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Previous week' }))
    await user.click(screen.getByRole('button', { name: 'Previous week' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ listingId: 7, from: '2026-09-28', to: '2026-10-04' }))

    await user.click(screen.getByRole('button', { name: 'This week' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ listingId: 7, from: '2026-10-05', to: '2026-10-11' }))
  })

  it('switches listing', async () => {
    const user = userEvent.setup()
    renderApp('/owner/calendar')
    await screen.findByRole('table', { name: 'Slots by day' })

    await user.selectOptions(screen.getByLabelText('Listing'), 'Baner Lot')

    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ listingId: 8, from: '2026-10-05', to: '2026-10-11' }))
  })

  it('says so when a listing has no slots or the owner has no listings', async () => {
    mock.onGet('/owner/calendar').reply((config) => [200, calendar(config.params.from, config.params.to, { slots: [], bookings: [], blocks: [] })])
    const { unmount } = renderApp('/owner/calendar')
    expect(await screen.findByText('This listing has no slots yet.')).toBeInTheDocument()
    unmount()

    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
    renderApp('/owner/calendar')
    expect(await screen.findByText('Add a listing to see its calendar.')).toBeInTheDocument()
  })

  it('reports a failed load', async () => {
    mock.onGet('/owner/calendar').reply(500, { code: 'INTERNAL', detail: 'Calendar is down' })
    renderApp('/owner/calendar')

    expect(await screen.findByText('Calendar is down')).toBeInTheDocument()
  })
})
