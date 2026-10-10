import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { RevenueReport, UsageReport } from '../../lib/admin'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }
const NOW = new Date('2026-10-09T10:00:00Z')


const usage: UsageReport = {
  from: '2026-09-10', to: '2026-10-09',
  rows: [
    { cityId: 10, cityName: 'Pune', stateName: 'Maharashtra', listings: 4, slots: 20, bookings: 50, bookedHours: 300, utilizationPercent: 41.5, cancellations: 3 },
    { cityId: 11, cityName: 'Bengaluru', stateName: 'Karnataka', listings: 6, slots: 30, bookings: 45, bookedHours: 280, utilizationPercent: 38, cancellations: 1 },
  ],
  totals: { listings: 10, slots: 50, bookings: 95, bookedHours: 580, utilizationPercent: 40, cancellations: 4 },
}
const revenue: RevenueReport = {
  from: '2026-09-10', to: '2026-10-09',
  rows: [{ cityId: 10, cityName: 'Pune', stateName: 'Maharashtra', bookings: 50, gmv: 70000, platformFees: 7000, gst: 1260, refunds: 2000, ownerEarnings: 61000 }],
  totals: { bookings: 50, gmv: 70000, platformFees: 7000, gst: 1260, refunds: 2000, ownerEarnings: 61000 },
}

describe('admin reports', () => {
  let mock: MockAdapter

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/reports/usage').reply(200, usage)
    mock.onGet('/admin/reports/revenue').reply(200, revenue)
    mock.onGet('/states').reply(200, [
      { id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cityCount: 2 },
      { id: 2, name: 'Karnataka', code: 'KA', slug: 'karnataka', type: 'STATE', capitalName: 'Bengaluru', cityCount: 1 },
    ])
    mock.onGet('/states/maharashtra').reply(200, {
      id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai',
      cities: [{ id: 10, name: 'Pune', slug: 'pune', lat: 18.5, lng: 73.8, capital: false, stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra' }],
    })
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const calls = (kind: string) => mock.history.get.filter((r) => r.url === `/admin/reports/${kind}`)

  it('shows the usage report for the last 30 days with a totals row', async () => {
    renderApp('/admin/reports')

    const table = within(await screen.findByRole('table', { name: 'Usage report' }))
    expect(calls('usage')[0].params).toEqual({ from: '2026-09-10', to: '2026-10-09' })
    expect(table.getByRole('row', { name: /Pune, Maharashtra 4 20 50 300 41.5% 3/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /Bengaluru, Karnataka 6 30 45 280 38% 1/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /Total 10 50 95 580 40% 4/ })).toBeInTheDocument()
  })

  it('switches to the revenue report', async () => {
    const user = userEvent.setup()
    renderApp('/admin/reports')
    await screen.findByRole('table', { name: 'Usage report' })

    await user.click(screen.getByRole('tab', { name: 'Revenue' }))

    const table = within(await screen.findByRole('table', { name: 'Revenue report' }))
    expect(table.getByRole('row', { name: /Pune, Maharashtra 50 ₹70,000 ₹7,000 ₹1,260 ₹2,000 ₹61,000/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /Total 50 ₹70,000/ })).toBeInTheDocument()
  })

  it('narrows by dates, then by state and city', async () => {
    const user = userEvent.setup()
    renderApp('/admin/reports')
    await screen.findByRole('table', { name: 'Usage report' })

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    await waitFor(() => expect(calls('usage').at(-1)!.params).toEqual({ from: '2026-10-01', to: '2026-10-09' }))

    await screen.findByRole('option', { name: 'Maharashtra' })
    await user.selectOptions(screen.getByLabelText('State'), 'Maharashtra')
    await waitFor(() => expect(calls('usage').at(-1)!.params).toEqual({ from: '2026-10-01', to: '2026-10-09', stateId: 1 }))

    await screen.findByRole('option', { name: 'Pune' })
    await user.selectOptions(screen.getByLabelText('City'), 'Pune')
    await waitFor(() => expect(calls('usage').at(-1)!.params).toEqual({ from: '2026-10-01', to: '2026-10-09', stateId: 1, cityId: 10 }))

    await user.selectOptions(screen.getByLabelText('State'), 'All states')
    await waitFor(() => expect(calls('usage').at(-1)!.params).toEqual({ from: '2026-10-01', to: '2026-10-09' }))
  })

  it('shows an empty state and the server message on failure', async () => {
    mock.onGet('/admin/reports/usage').reply(200, { ...usage, rows: [] })
    mock.onGet('/admin/reports/revenue').reply(400, { code: 'INVALID_DATE_RANGE', detail: 'The start date must not be after the end date' })
    const user = userEvent.setup()
    renderApp('/admin/reports')
    expect(await screen.findByText('No activity in this period.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Revenue' }))

    expect(await screen.findByText('The start date must not be after the end date')).toBeInTheDocument()
  })

  describe('CSV export', () => {
    function captureDownload() {
      const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() })
      onTestFinished(() => {
        Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
      })
      const downloads: { download: string; href: string }[] = []
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        downloads.push({ download: this.download, href: this.href })
      })
      return downloads
    }

    it('downloads the current report with the current filters', async () => {
      mock.onGet('/admin/reports/usage').reply((config) =>
        config.params.format === 'csv'
          ? [200, new Blob(['city,bookings']), { 'content-disposition': 'attachment; filename="parkease-usage-2026-10-09.csv"' }]
          : [200, usage],
      )
      const downloads = captureDownload()
      const user = userEvent.setup()
      renderApp('/admin/reports')
      await screen.findByRole('table', { name: 'Usage report' })

      await user.click(screen.getByRole('button', { name: 'Download CSV' }))

      await waitFor(() => expect(downloads).toEqual([{ download: 'parkease-usage-2026-10-09.csv', href: 'blob:report' }]))
      const csv = calls('usage').find((r) => r.params.format === 'csv')!
      expect(csv.params).toEqual({ from: '2026-09-10', to: '2026-10-09', format: 'csv' })
      expect(csv.responseType).toBe('blob')
      expect(toast.warning).not.toHaveBeenCalled()
    })

    it('warns when the server truncated the export', async () => {
      mock.onGet('/admin/reports/usage').reply((config) =>
        config.params.format === 'csv' ? [200, new Blob(['a']), { 'x-truncated': 'true' }] : [200, usage],
      )
      captureDownload()
      const user = userEvent.setup()
      renderApp('/admin/reports')

      await user.click(await screen.findByRole('button', { name: 'Download CSV' }))

      await waitFor(() => expect(toast.warning).toHaveBeenCalledWith('The export was cut at the row limit — narrow the filters.'))
    })

    it('shows the problem when the download fails', async () => {
      mock.onGet('/admin/reports/usage').reply((config) =>
        config.params.format === 'csv'
          ? [400, new Blob([JSON.stringify({ code: 'INVALID_DATE_RANGE', detail: 'Range too long' })])]
          : [200, usage],
      )
      const user = userEvent.setup()
      renderApp('/admin/reports')

      await user.click(await screen.findByRole('button', { name: 'Download CSV' }))

      expect(await screen.findByText('Range too long')).toBeInTheDocument()
    })
  })
})

