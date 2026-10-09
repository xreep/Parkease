import '@testing-library/jest-dom/vitest'
import { QueryClient } from '@tanstack/react-query'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import { formatDateTime } from '../../lib/format'
import type { Block, ListingDetail } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = {
  id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null,
  role: 'OWNER', emailVerified: true, avatarUrl: null,
}

const listing: ListingDetail = {
  id: 7, title: 'FC Road Parking', description: null, address: '12 FC Road', pincode: '411004',
  lat: 18.5204, lng: 73.8567, listingType: 'OFFICE', cityId: 51, cityName: 'Pune', stateName: 'Maharashtra',
  status: 'APPROVED', rejectionReason: null, open24x7: true, rules: null, autoApprove: true,
  pricePerHour: 50, pricePerDay: null, pricePerMonth: null, cancellationPolicy: 'MODERATE', amenities: [],
  photos: [], slots: [
    { id: 31, label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true },
    { id: 32, label: 'A-02', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true },
  ], hours: [], submittedAt: null, approvedAt: null, updatedAt: '2026-10-05T10:00:00Z',
}

const later: Block = { id: 22, slotId: 31, slotLabel: 'A-01', startTime: '2026-11-10T09:00:00Z', endTime: '2026-11-10T12:00:00Z', reason: 'Repainting' }
const sooner: Block = { id: 21, slotId: null, slotLabel: null, startTime: '2026-11-02T09:00:00Z', endTime: '2026-11-02T18:00:00Z', reason: null }

describe('blocked times', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/listings/7').reply(200, listing)
  })

  afterEach(() => mock.restore())

  async function fillRange(from: string, until: string) {
    fireEvent.change(await screen.findByLabelText('From'), { target: { value: from } })
    fireEvent.change(screen.getByLabelText('Until'), { target: { value: until } })
  }

  it('shows the heading, a back link and an empty state', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    renderApp('/owner/listings/7/blocks')

    expect(await screen.findByRole('heading', { name: 'Blocked times — FC Road Parking' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← My listings' })).toHaveAttribute('href', '/owner/listings')
    expect(await screen.findByText('No blocked times. Your listing follows its weekly hours.')).toBeInTheDocument()
  })

  it('lists blocks sorted by start time', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [later, sooner])
    renderApp('/owner/listings/7/blocks')

    const items = within(await screen.findByRole('list', { name: 'Upcoming blocked times' })).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText(`${formatDateTime(sooner.startTime)} → ${formatDateTime(sooner.endTime)}`)).toBeInTheDocument()
    expect(within(items[0]).getByText('Whole listing')).toBeInTheDocument()
    expect(within(items[1]).getByText('A-01')).toBeInTheDocument()
    expect(within(items[1]).getByText('Repainting')).toBeInTheDocument()
    expect(within(items[0]).getByRole('button', { name: 'Remove block 1' })).toBeInTheDocument()
  })

  it('sorts by the actual instant even when offsets differ', async () => {
    const earlier: Block = { ...sooner, id: 31, startTime: '2026-11-02T09:00:00+05:30', endTime: '2026-11-02T12:00:00+05:30', reason: 'Earlier instant' }
    const laterInstant: Block = { ...sooner, id: 32, startTime: '2026-11-02T04:30:00Z', endTime: '2026-11-02T06:00:00Z', reason: 'Later instant' }
    mock.onGet('/owner/listings/7/blocks').reply(200, [laterInstant, earlier])
    renderApp('/owner/listings/7/blocks')

    const items = within(await screen.findByRole('list', { name: 'Upcoming blocked times' })).getAllByRole('listitem')
    expect(within(items[0]).getByText('Earlier instant')).toBeInTheDocument()
    expect(within(items[1]).getByText('Later instant')).toBeInTheDocument()
  })

  it('does not offer removal on a suspended listing', async () => {
    mock.onGet('/owner/listings/7').reply(200, { ...listing, status: 'SUSPENDED' })
    mock.onGet('/owner/listings/7/blocks').reply(200, [sooner])
    renderApp('/owner/listings/7/blocks')

    expect(await screen.findByText("This listing was suspended by ParkEase and can't be edited.")).toBeInTheDocument()
    expect(await screen.findByText('Whole listing')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove block/ })).not.toBeInTheDocument()
  })

  it('blocks the whole listing', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    mock.onPost('/owner/listings/7/blocks').reply(201, sooner)
    renderApp('/owner/listings/7/blocks')

    await fillRange('2030-05-01T09:00', '2030-05-01T17:00')
    await userEvent.type(screen.getByLabelText('Reason (optional)'), 'Maintenance')
    await userEvent.click(screen.getByRole('button', { name: 'Block time' }))

    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({
      slotId: null,
      startTime: new Date('2030-05-01T09:00').toISOString(),
      endTime: new Date('2030-05-01T17:00').toISOString(),
      reason: 'Maintenance',
    })
  })

  it('blocks a single slot', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    mock.onPost('/owner/listings/7/blocks').reply(201, later)
    renderApp('/owner/listings/7/blocks')

    await userEvent.selectOptions(await screen.findByLabelText('Applies to'), 'A-02')
    await fillRange('2030-05-01T09:00', '2030-05-02T09:00')
    await userEvent.click(screen.getByRole('button', { name: 'Block time' }))

    await waitFor(() => expect(mock.history.post).toHaveLength(1))
    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({ slotId: 32 })
  })

  const invalidatedKeys = (spy: { mock: { calls: unknown[][] } }) =>
    spy.mock.calls.map(([filters]) => (filters as { queryKey: unknown[] }).queryKey)

  it('refreshes the list and clears the form after adding', async () => {
    mock.onGet('/owner/listings/7/blocks').replyOnce(200, []).onGet('/owner/listings/7/blocks').reply(200, [sooner])
    mock.onPost('/owner/listings/7/blocks').reply(201, sooner)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/listings/7/blocks', queryClient)

    await fillRange('2030-05-01T09:00', '2030-05-01T17:00')
    await userEvent.click(screen.getByRole('button', { name: 'Block time' }))

    expect(await screen.findByRole('button', { name: 'Remove block 1' })).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('')
    // A block changes what the owner's calendar and the public availability calendar show.
    expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining([['owner', 'blocks', 7], ['owner', 'calendar'], ['availability']]))
  })

  it('rejects an end that is not after the start', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    renderApp('/owner/listings/7/blocks')

    await fillRange('2030-05-01T17:00', '2030-05-01T09:00')
    await userEvent.click(screen.getByRole('button', { name: 'Block time' }))

    expect(await screen.findByText("'Until' must be after 'From'")).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('requires both times', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    renderApp('/owner/listings/7/blocks')

    await userEvent.click(await screen.findByRole('button', { name: 'Block time' }))

    expect(await screen.findByText('Choose when the block starts')).toBeInTheDocument()
    expect(screen.getByText('Choose when the block ends')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('shows the server message for an invalid block', async () => {
    mock.onGet('/owner/listings/7/blocks').reply(200, [])
    mock.onPost('/owner/listings/7/blocks').reply(400, { code: 'INVALID_BLOCK', detail: 'A block must end in the future' })
    renderApp('/owner/listings/7/blocks')

    await fillRange('2030-05-01T09:00', '2030-05-01T17:00')
    await userEvent.click(screen.getByRole('button', { name: 'Block time' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A block must end in the future')
  })

  it('removes a block', async () => {
    mock.onGet('/owner/listings/7/blocks').replyOnce(200, [sooner, later]).onGet('/owner/listings/7/blocks').reply(200, [later])
    mock.onDelete('/owner/listings/7/blocks/21').reply(204)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderApp('/owner/listings/7/blocks', queryClient)

    await userEvent.click(await screen.findByRole('button', { name: 'Remove block 1' }))

    await waitFor(() => expect(mock.history.delete).toHaveLength(1))
    expect(mock.history.delete[0].url).toBe('/owner/listings/7/blocks/21')
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Upcoming blocked times' })).getAllByRole('listitem')).toHaveLength(1))
    expect(invalidatedKeys(invalidate)).toEqual(expect.arrayContaining([['owner', 'calendar'], ['availability']]))
  })

  it('shows not found for a listing that is not the owner\'s', async () => {
    mock.onGet('/owner/listings/9').reply(404, { code: 'NOT_FOUND', detail: 'Listing not found' })
    mock.onGet('/owner/listings/9/blocks').reply(404, { code: 'NOT_FOUND', detail: 'Listing not found' })
    renderApp('/owner/listings/9/blocks')

    expect(await screen.findByText('Listing not found')).toBeInTheDocument()
  })
})
