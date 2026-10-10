import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { PayoutEarning, PayoutOwner } from '../../lib/adminManage'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const owners: PayoutOwner[] = [
  { ownerId: 5, ownerName: 'Ravi Kumar', ownerEmail: 'ravi@example.com', pendingAmount: 1500, earningsCount: 3, payoutMethod: 'UPI', payoutMasked: 'ra***@okhdfc', disputedAmount: 0 },
  { ownerId: 6, ownerName: 'Meera Nair', ownerEmail: 'meera@example.com', pendingAmount: 400, earningsCount: 1, payoutMethod: 'BANK', payoutMasked: 'XXXX1234 · HDFC0001234', disputedAmount: 0 },
  { ownerId: 7, ownerName: 'Sam Joseph', ownerEmail: 'sam@example.com', pendingAmount: 90, earningsCount: 1, payoutMethod: null, payoutMasked: null, disputedAmount: 0 },
]

const earning = (id: number, net: number, disputed = false): PayoutEarning => ({
  id, bookingId: 100 + id, bookingCode: `PE-EARN00${id}`, listingTitle: 'FC Road Parking', startTime: '2026-10-05T04:30:00Z',
  endTime: '2026-10-05T06:30:00Z', gross: net + 20, commission: 20, net, status: 'PENDING_PAYOUT', paidAt: null, payoutReference: null, disputed,
})

describe('admin payouts', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/payouts/5/earnings').reply(200, [earning(1, 500), earning(2, 600), earning(3, 400)])
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
    vi.mocked(toast.warning).mockClear()
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  const listCalls = () => mock.history.get.filter((r) => r.url === '/admin/payouts' && !r.params?.format)

  async function expandRavi(u: ReturnType<typeof userEvent.setup>) {
    await u.click(within(await screen.findByRole('article', { name: 'Ravi Kumar' })).getByRole('button', { name: 'Show earnings' }))
    return within(await screen.findByRole('region', { name: 'Pending earnings for Ravi Kumar' }))
  }

  it('lists owners with pending totals and masked payout details', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    renderApp('/admin/payouts')

    const ravi = within(await screen.findByRole('article', { name: 'Ravi Kumar' }))
    expect(ravi.getByText('ravi@example.com')).toBeInTheDocument()
    expect(ravi.getByText('₹1,500')).toBeInTheDocument()
    expect(ravi.getByText('3 earnings')).toBeInTheDocument()
    expect(ravi.getByText('UPI · ra***@okhdfc')).toBeInTheDocument()
    const meera = within(screen.getByRole('article', { name: 'Meera Nair' }))
    expect(meera.getByText('1 earning')).toBeInTheDocument()
    expect(meera.getByText('Bank · XXXX1234 · HDFC0001234')).toBeInTheDocument()
    expect(within(screen.getByRole('article', { name: 'Sam Joseph' })).getByText('No payout details yet')).toBeInTheDocument()
    expect(screen.getByText('Total pending: ₹1,990')).toBeInTheDocument()
  })

  it('says how much is held for open disputes', async () => {
    mock.onGet('/admin/payouts').reply(200, [{ ...owners[0], disputedAmount: 300 }, owners[1]])
    renderApp('/admin/payouts')

    expect(within(await screen.findByRole('article', { name: 'Ravi Kumar' })).getByText('₹300 held for open disputes')).toBeInTheDocument()
    expect(within(screen.getByRole('article', { name: 'Meera Nair' })).queryByText(/held for open disputes/)).not.toBeInTheDocument()
  })

  it('does not let disputed earnings be selected, with a hint why', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    mock.onGet('/admin/payouts/5/earnings').reply(200, [earning(1, 500), earning(2, 600, true)])
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)

    const held = await earnings.findByRole('checkbox', { name: 'PE-EARN002' })
    expect(held).toBeDisabled()
    expect(earnings.getByRole('checkbox', { name: 'PE-EARN001' })).toBeEnabled()
    expect(earnings.getByText('Held for an open dispute')).toBeInTheDocument()
    expect(held).toHaveAccessibleDescription('Held for an open dispute')

    await u.click(earnings.getByRole('checkbox', { name: 'Select all' }))
    expect(earnings.getByRole('checkbox', { name: 'PE-EARN001' })).toBeChecked()
    expect(held).not.toBeChecked()
    expect(earnings.getByRole('button', { name: 'Mark selected as paid (1 · ₹500)' })).toBeEnabled()
  })

  it('explains EARNING_DISPUTED and reloads', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    mock.onPost('/admin/payouts/mark-paid').reply(409, { code: 'EARNING_DISPUTED', detail: 'nope' })
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)
    await u.click(await earnings.findByRole('checkbox', { name: 'Select all' }))
    await u.click(earnings.getByRole('button', { name: /Mark selected as paid/ }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Payment reference'), 'UTR123456789')
    const before = listCalls().length

    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))

    expect(await dialog.findByText('Some of those earnings are held for an open dispute. Resolve it first.')).toBeInTheDocument()
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(before))
  })

  it('words the confirmation as recording an outside payout', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)
    await u.click(await earnings.findByRole('checkbox', { name: 'Select all' }))
    await u.click(earnings.getByRole('button', { name: /Mark selected as paid/ }))

    const dialog = within(await screen.findByRole('dialog'))
    expect(dialog.getByText('Record a payout you’ve made outside ParkEase (using the owner’s payout details on file). This doesn’t move money.')).toBeInTheDocument()
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/admin/payouts').replyOnce(200, [])
    renderApp('/admin/payouts')
    expect(await screen.findByText('No payouts are pending.')).toBeInTheDocument()
  })

  it('shows the server message when the list fails', async () => {
    mock.onGet('/admin/payouts').reply(500, { code: 'INTERNAL', detail: 'Payouts are down' })
    renderApp('/admin/payouts')
    expect(await screen.findByText('Payouts are down')).toBeInTheDocument()
  })

  it('expands an owner to their pending earnings, all unselected', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    const u = userEvent.setup()
    renderApp('/admin/payouts')

    const earnings = await expandRavi(u)

    expect(await earnings.findByRole('checkbox', { name: 'PE-EARN001' })).not.toBeChecked()
    expect(earnings.getByText('₹500')).toBeInTheDocument()
    expect(earnings.getAllByRole('checkbox')).toHaveLength(4)
    expect(earnings.getByRole('button', { name: /Mark selected as paid/ })).toBeDisabled()
  })

  it('selects earnings, totals them and marks them paid with a reference', async () => {
    mock.onGet('/admin/payouts').replyOnce(200, owners)
    mock.onGet('/admin/payouts').reply(200, owners.slice(1))
    mock.onPost('/admin/payouts/mark-paid').reply(200, { paidCount: 2, paidAmount: 1100 })
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)

    await u.click(await earnings.findByRole('checkbox', { name: 'PE-EARN001' }))
    await u.click(earnings.getByRole('checkbox', { name: 'PE-EARN002' }))
    expect(earnings.getByRole('button', { name: 'Mark selected as paid (2 · ₹1,100)' })).toBeEnabled()
    await u.click(earnings.getByRole('button', { name: /Mark selected as paid/ }))

    const dialog = within(await screen.findByRole('dialog', { name: 'Mark payout as paid' }))
    expect(dialog.getByText(/Ravi Kumar/)).toBeInTheDocument()
    expect(dialog.getByText(/₹1,100/)).toBeInTheDocument()
    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))
    expect(await dialog.findByText('Enter between 3 and 100 characters')).toBeInTheDocument()
    await u.type(dialog.getByLabelText('Payment reference'), 'UTR123456789')
    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Marked 2 earnings as paid (₹1,100)'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ ownerId: 5, earningIds: [1, 2], reference: 'UTR123456789' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Ravi Kumar' })).not.toBeInTheDocument())
  })

  it('selects every earning at once', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)

    await u.click(await earnings.findByRole('checkbox', { name: 'Select all' }))

    expect(earnings.getByRole('button', { name: 'Mark selected as paid (3 · ₹1,500)' })).toBeEnabled()
    await u.click(earnings.getByRole('checkbox', { name: 'Select all' }))
    expect(earnings.getByRole('button', { name: /Mark selected as paid/ })).toBeDisabled()
  })

  it('explains NOTHING_TO_PAY in the dialog and reloads the owners', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    mock.onPost('/admin/payouts/mark-paid').reply(409, { code: 'NOTHING_TO_PAY', detail: 'nope' })
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)
    await u.click(await earnings.findByRole('checkbox', { name: 'Select all' }))
    await u.click(earnings.getByRole('button', { name: /Mark selected as paid/ }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Payment reference'), 'UTR123456789')
    const before = listCalls().length

    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))

    expect(await dialog.findByText('There is nothing to pay: those earnings are no longer pending payout.')).toBeInTheDocument()
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(before))
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('disables the confirmation when the selection is gone after a refresh', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    let loads = 0
    mock.onGet('/admin/payouts/5/earnings').reply(() => [200, loads++ === 0 ? [earning(1, 500)] : []])
    mock.onPost('/admin/payouts/mark-paid').reply(409, { code: 'NOTHING_TO_PAY', detail: 'nope' })
    const u = userEvent.setup()
    renderApp('/admin/payouts')
    const earnings = await expandRavi(u)
    await u.click(await earnings.findByRole('checkbox', { name: 'PE-EARN001' }))
    await u.click(earnings.getByRole('button', { name: /Mark selected as paid/ }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Payment reference'), 'UTR123456789')

    await u.click(dialog.getByRole('button', { name: 'Mark as paid' }))

    expect(await dialog.findByText('There is nothing to pay: those earnings are no longer pending payout.')).toBeInTheDocument()
    await waitFor(() => expect(dialog.getByRole('button', { name: 'Mark as paid' })).toBeDisabled())
  })

  it('shows the server message when the earnings fail to load', async () => {
    mock.onGet('/admin/payouts').reply(200, owners)
    mock.onGet('/admin/payouts/5/earnings').reply(500, { code: 'INTERNAL', detail: 'Earnings are down' })
    const u = userEvent.setup()
    renderApp('/admin/payouts')

    await expandRavi(u)

    expect(await screen.findByText('Earnings are down')).toBeInTheDocument()
  })

  describe('CSV', () => {
    function captureDownload() {
      const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:payouts'), revokeObjectURL: vi.fn() })
      onTestFinished(() => {
        Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
      })
      const downloads: { download: string; href: string }[] = []
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        downloads.push({ download: this.download, href: this.href })
      })
      return downloads
    }

    it('downloads the pending payouts under the server filename', async () => {
      mock.onGet('/admin/payouts').reply((config) =>
        config.params?.format === 'csv'
          ? [200, new Blob(['a']), { 'content-disposition': 'attachment; filename="parkease-payouts-2026-10-09.csv"' }]
          : [200, owners],
      )
      const downloads = captureDownload()
      const u = userEvent.setup()
      renderApp('/admin/payouts')

      await u.click(await screen.findByRole('button', { name: 'Download CSV' }))

      await waitFor(() => expect(downloads).toEqual([{ download: 'parkease-payouts-2026-10-09.csv', href: 'blob:payouts' }]))
    })

    it('warns about truncation and shows a failure', async () => {
      mock.onGet('/admin/payouts').reply((config) =>
        config.params?.format === 'csv' ? [200, new Blob(['a']), { 'x-truncated': 'true' }] : [200, owners],
      )
      captureDownload()
      const u = userEvent.setup()
      renderApp('/admin/payouts')

      await u.click(await screen.findByRole('button', { name: 'Download CSV' }))
      await waitFor(() => expect(toast.warning).toHaveBeenCalledWith('The export was cut at the row limit.'))

      mock.onGet('/admin/payouts').reply((config) =>
        config.params?.format === 'csv' ? [500, new Blob([JSON.stringify({ code: 'INTERNAL', detail: 'Export failed' })])] : [200, owners],
      )
      await u.click(screen.getByRole('button', { name: 'Download CSV' }))
      expect(await screen.findByText('Export failed')).toBeInTheDocument()
    })
  })
})
