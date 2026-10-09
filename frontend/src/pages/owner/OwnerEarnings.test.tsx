import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { OwnerEarningDto } from '../../lib/ownerDashboard'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

const earning = (id: number, overrides: Partial<OwnerEarningDto> = {}): OwnerEarningDto => ({
  id, bookingId: 100 + id, bookingCode: `PE-ERN00${id}`, listingTitle: 'FC Road Parking',
  startTime: '2026-10-05T04:30:00Z', endTime: '2026-10-05T06:30:00Z', gross: 240, commission: 24, net: 216, status: 'HELD',
  paidAt: null, payoutReference: null, ...overrides,
})

const totals = { held: 1200, pendingPayout: 3400, paid: 7880, reversedCount: 2 }
const body = (content: OwnerEarningDto[], totalPages = 1, pageNo = 0) => ({
  totals,
  earnings: { content, page: pageNo, size: 20, totalElements: content.length, totalPages },
})

describe('owner earnings', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  const calls = () => mock.history.get.filter((r) => r.url === '/owner/earnings')

  it('is reachable from the owner navigation', async () => {
    mock.onGet('/owner/earnings').reply(200, body([]))
    const user = userEvent.setup()
    renderApp('/owner/bookings')

    await user.click(await screen.findByRole('link', { name: 'Earnings' }))

    expect(await screen.findByRole('heading', { name: 'Earnings' })).toBeInTheDocument()
  })

  it('shows the totals and one row per earning', async () => {
    mock.onGet('/owner/earnings').reply(200, body([
      earning(1),
      earning(2, { status: 'PAID', paidAt: '2026-10-08T10:00:00Z', payoutReference: 'UTR123456', gross: 100, commission: 10, net: 90 }),
      earning(3, { status: 'REVERSED', net: 0 }),
    ]))
    renderApp('/owner/earnings')

    const summary = await screen.findByRole('region', { name: 'Totals' })
    expect(within(within(summary).getByRole('group', { name: 'Held' })).getByText('₹1,200')).toBeInTheDocument()
    expect(within(within(summary).getByRole('group', { name: 'Pending payout' })).getByText('₹3,400')).toBeInTheDocument()
    expect(within(within(summary).getByRole('group', { name: 'Paid' })).getByText('₹7,880')).toBeInTheDocument()
    expect(within(within(summary).getByRole('group', { name: 'Reversed' })).getByText('2')).toBeInTheDocument()

    const table = screen.getByRole('table', { name: 'Earnings' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(rows[1]).toHaveTextContent('PE-ERN001')
    expect(rows[1]).toHaveTextContent('FC Road Parking')
    expect(rows[1]).toHaveTextContent('₹240')
    expect(rows[1]).toHaveTextContent('₹24')
    expect(rows[1]).toHaveTextContent('₹216')
    expect(rows[1]).toHaveTextContent('Held')
    expect(rows[2]).toHaveTextContent('Paid')
    expect(rows[2]).toHaveTextContent('UTR123456')
    expect(rows[3]).toHaveTextContent('Reversed')
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
  })

  it('has a card per earning for small screens', async () => {
    mock.onGet('/owner/earnings').reply(200, body([earning(1)]))
    renderApp('/owner/earnings')

    const cards = await screen.findByRole('list', { name: 'Earnings list' })
    const card = within(cards).getByRole('listitem')
    expect(card).toHaveTextContent('PE-ERN001')
    expect(card).toHaveTextContent('₹216')
  })

  it('filters by status and date range and goes back to the first page', async () => {
    mock.onGet('/owner/earnings').reply(200, body([earning(1)], 3))
    const user = userEvent.setup()
    renderApp('/owner/earnings')
    await screen.findByRole('table', { name: 'Earnings' })
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ page: 1, size: 20 }))

    await user.selectOptions(screen.getByLabelText('Status'), 'Paid')
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'PAID', page: 0, size: 20 }))

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-31' } })
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ status: 'PAID', from: '2026-10-01', to: '2026-10-31', page: 0, size: 20 }))
  })

  it('shows an empty state', async () => {
    mock.onGet('/owner/earnings').reply(200, body([]))
    renderApp('/owner/earnings')

    expect(await screen.findByText('No earnings match these filters.')).toBeInTheDocument()
  })

  it('pages through the ledger', async () => {
    mock.onGet('/owner/earnings').reply((config) => [200, body([earning(config.params.page + 1)], 2, config.params.page)])
    const user = userEvent.setup()
    renderApp('/owner/earnings')

    await screen.findByText('PE-ERN001', { selector: 'td *, td' })
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('PE-ERN002', { selector: 'td *, td' })).toBeInTheDocument()
  })

  it('reports a failed load', async () => {
    mock.onGet('/owner/earnings').reply(500, { code: 'INTERNAL', detail: 'Ledger is down' })
    renderApp('/owner/earnings')

    expect(await screen.findByText('Ledger is down')).toBeInTheDocument()
  })

  describe('CSV export', () => {
    function captureDownload() {
      const createObjectURL = vi.fn(() => 'blob:earnings')
      const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }
      Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
      onTestFinished(() => {
        Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
      })
      const downloads: { download: string; href: string }[] = []
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        downloads.push({ download: this.download, href: this.href })
      })
      return downloads
    }

    it('requests the csv with the current filters and saves it under the server filename', async () => {
      mock.onGet('/owner/earnings', { params: { page: 0, size: 20 } }).reply(200, body([earning(1)]))
      mock.onGet('/owner/earnings').reply((config) =>
        config.params.format === 'csv'
          ? [200, new Blob(['id,net\n1,216']), { 'content-disposition': 'attachment; filename="parkease-earnings-2026-10-09.csv"' }]
          : [200, body([earning(1)])],
      )
      const downloads = captureDownload()
      const user = userEvent.setup()
      renderApp('/owner/earnings')
      await screen.findByRole('table', { name: 'Earnings' })
      await user.selectOptions(screen.getByLabelText('Status'), 'Paid')
      fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
      await waitFor(() => expect(calls().at(-1)!.params).toMatchObject({ status: 'PAID', from: '2026-10-01' }))

      await user.click(screen.getByRole('button', { name: 'Download CSV' }))

      await waitFor(() => expect(downloads).toEqual([{ download: 'parkease-earnings-2026-10-09.csv', href: 'blob:earnings' }]))
      const csv = calls().find((r) => r.params.format === 'csv')!
      expect(csv.params).toEqual({ status: 'PAID', from: '2026-10-01', format: 'csv' })
      expect(csv.responseType).toBe('blob')
    })

    it('falls back to a plain filename and shows why a download failed', async () => {
      mock.onGet('/owner/earnings').reply((config) =>
        config.params.format === 'csv' ? [200, new Blob(['a,b'])] : [200, body([earning(1)])],
      )
      const downloads = captureDownload()
      const user = userEvent.setup()
      renderApp('/owner/earnings')
      await user.click(await screen.findByRole('button', { name: 'Download CSV' }))
      await waitFor(() => expect(downloads).toEqual([{ download: 'earnings.csv', href: 'blob:earnings' }]))

      mock.onGet('/owner/earnings').reply((config) =>
        config.params.format === 'csv'
          ? [400, new Blob([JSON.stringify({ code: 'INVALID_DATE_RANGE', detail: 'The start date must not be after the end date' })])]
          : [200, body([earning(1)])],
      )
      await user.click(screen.getByRole('button', { name: 'Download CSV' }))
      expect(await screen.findByText('The start date must not be after the end date')).toBeInTheDocument()
    })
  })
})
