import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminAction, AdminStatsDto, PlatformSettings, RevenueReport, UsageReport } from '../../lib/admin'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }
const NOW = new Date('2026-10-09T10:00:00Z')

const stats: AdminStatsDto = {
  from: '2026-09-10', to: '2026-10-09',
  users: { drivers: 120, owners: 30, newDrivers: 12, newOwners: 3, suspended: 2 },
  listings: { approved: 40, pendingReview: 5, suspended: 1, paused: 2 },
  bookings: { created: 200, confirmed: 150, conversionPercent: 75, cancelled: 20, utilizationPercent: 42.5 },
  money: { gmv: 250000, platformRevenue: 25000, refunds: 12000, ownerEarnings: 200000 },
  topStates: [
    { stateId: 1, name: 'Maharashtra', bookings: 90, gmv: 120000 },
    { stateId: 2, name: 'Karnataka', bookings: 60, gmv: 80000 },
  ],
  topCities: [
    { cityId: 10, name: 'Pune', stateName: 'Maharashtra', bookings: 50, gmv: 70000 },
    { cityId: 11, name: 'Bengaluru', stateName: 'Karnataka', bookings: 45, gmv: 60000 },
  ],
  series: [
    { date: '2026-10-07', bookings: 5, gmv: 5000, revenue: 500 },
    { date: '2026-10-08', bookings: 7, gmv: 8000, revenue: 800 },
  ],
}

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

describe('admin overview', () => {
  let mock: MockAdapter

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/queues').reply(200, { pendingOwners: 2, pendingListings: 1 })
  })

  afterEach(() => {
    mock.restore()
    vi.useRealTimers()
  })

  const statsCalls = () => mock.history.get.filter((r) => r.url === '/admin/stats')

  it('has a sectioned navigation with every admin area', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')

    const nav = within(await screen.findByRole('navigation', { name: 'Sections' }))
    const expected: [string, string][] = [
      ['Overview', '/admin'], ['Owners', '/admin/owners'], ['Listings', '/admin/listings'], ['Bookings', '/admin/bookings'],
      ['Disputes', '/admin/disputes'], ['Payments', '/admin/payments'], ['Payouts', '/admin/payouts'], ['Users', '/admin/users'],
      ['Reviews', '/admin/reviews'], ['Locations', '/admin/locations'], ['Reports', '/admin/reports'],
      ['Settings', '/admin/settings'], ['Audit', '/admin/audit'],
    ]
    for (const [name, href] of expected) expect(nav.getByRole('link', { name })).toHaveAttribute('href', href)
  })

  it('shows the KPI cards, charts, top tables and queue counts', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')

    const kpi = async (name: string) => within(await screen.findByRole('group', { name }))
    expect((await kpi('Users')).getByText('150')).toBeInTheDocument()
    expect((await kpi('Users')).getByText('15 new')).toBeInTheDocument()
    expect((await kpi('Listings')).getByText('40')).toBeInTheDocument()
    expect((await kpi('Bookings')).getByText('200')).toBeInTheDocument()
    expect((await kpi('Conversion')).getByText('75%')).toBeInTheDocument()
    expect((await kpi('Utilization')).getByText('42.5%')).toBeInTheDocument()
    expect((await kpi('GMV')).getByText('₹2,50,000')).toBeInTheDocument()
    expect((await kpi('Revenue')).getByText('₹25,000')).toBeInTheDocument()
    expect((await kpi('Refunds')).getByText('₹12,000')).toBeInTheDocument()

    expect(screen.getByRole('img', { name: 'Bookings per day' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'GMV per day' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Platform revenue per day' })).toBeInTheDocument()

    const states = within(screen.getByRole('table', { name: 'Top states' }))
    expect(states.getByRole('row', { name: /Maharashtra 90 ₹1,20,000/ })).toBeInTheDocument()
    const cities = within(screen.getByRole('table', { name: 'Top cities' }))
    expect(cities.getByRole('row', { name: /Pune, Maharashtra 50 ₹70,000/ })).toBeInTheDocument()

    expect(await screen.findByText('2 owners waiting for verification')).toBeInTheDocument()
    expect(screen.getByText('1 listing waiting for approval')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review owners' })).toHaveAttribute('href', '/admin/owners')
    expect(screen.getByRole('link', { name: 'Review listings' })).toHaveAttribute('href', '/admin/listings')
  })

  it('asks for the last 30 days at first and refetches when the range changes', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    renderApp('/admin')
    await screen.findByRole('group', { name: 'Users' })
    expect(statsCalls()[0].params).toEqual({ from: '2026-09-10', to: '2026-10-09' })

    fireEvent.change(screen.getByLabelText('Range'), { target: { value: '7' } })

    await waitFor(() => expect(statsCalls().at(-1)!.params).toEqual({ from: '2026-10-03', to: '2026-10-09' }))
  })

  it('shows the server message when the stats fail to load', async () => {
    mock.onGet('/admin/stats').reply(500, { code: 'INTERNAL', detail: 'Stats are down' })
    renderApp('/admin')

    expect(await screen.findByText('Stats are down')).toBeInTheDocument()
  })

  it('shows the server message when the queue counts fail to load', async () => {
    mock.onGet('/admin/stats').reply(200, stats)
    mock.onGet('/admin/queues').reply(500, { code: 'INTERNAL', detail: 'Queues are down' })
    renderApp('/admin')

    expect(await screen.findByText('Queues are down')).toBeInTheDocument()
    expect(await screen.findByRole('group', { name: 'Users' })).toBeInTheDocument()
  })
})

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

const settings: PlatformSettings = {
  platformFeePercent: 10, gstPercent: 18, holdMinutes: 10, approvalHours: 2, requestMinLeadMinutes: 30,
  priceGuidelines: [
    { tier: 1, minHourly: 40, maxHourly: 200 },
    { tier: 2, minHourly: 20, maxHourly: 120 },
    { tier: 3, minHourly: 10, maxHourly: 80 },
  ],
}

describe('admin settings', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/settings').reply(200, settings)
    vi.mocked(toast.success).mockClear()
  })

  afterEach(() => mock.restore())

  it('loads the current values and the guideline rows per tier', async () => {
    renderApp('/admin/settings')

    expect(await screen.findByLabelText('Platform fee (%)')).toHaveValue(10)
    expect(screen.getByLabelText('GST (%)')).toHaveValue(18)
    expect(screen.getByLabelText('Payment hold (minutes)')).toHaveValue(10)
    expect(screen.getByLabelText('Owner approval window (hours)')).toHaveValue(2)
    expect(screen.getByLabelText('Minimum booking lead time (minutes)')).toHaveValue(30)
    expect(screen.getByLabelText('Metro minimum (₹ per hour)')).toHaveValue(40)
    expect(screen.getByLabelText('Metro maximum (₹ per hour)')).toHaveValue(200)
    expect(screen.getByLabelText('Large city minimum (₹ per hour)')).toHaveValue(20)
    expect(screen.getByLabelText('Other city maximum (₹ per hour)')).toHaveValue(80)
  })

  it('rejects out-of-range values before asking for confirmation', async () => {
    const user = userEvent.setup()
    renderApp('/admin/settings')
    const fee = await screen.findByLabelText('Platform fee (%)')

    await user.clear(fee)
    await user.type(fee, '60')
    const hold = screen.getByLabelText('Payment hold (minutes)')
    await user.clear(hold)
    await user.type(hold, '2')
    await user.clear(screen.getByLabelText('Metro minimum (₹ per hour)'))
    await user.type(screen.getByLabelText('Metro minimum (₹ per hour)'), '500')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(await screen.findByText('Fee must be between 0 and 50')).toBeInTheDocument()
    expect(screen.getByText('Hold must be between 5 and 60 minutes')).toBeInTheDocument()
    expect(screen.getByText('Minimum can’t be above the maximum')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)
  })

  it('rejects a blank value', async () => {
    const user = userEvent.setup()
    renderApp('/admin/settings')
    await user.clear(await screen.findByLabelText('GST (%)'))
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(await screen.findByText('GST must be between 0 and 28')).toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)
  })

  it('confirms that changes apply to new bookings only, then saves', async () => {
    mock.onPut('/admin/settings').reply((config) => [200, JSON.parse(config.data)])
    const user = userEvent.setup()
    renderApp('/admin/settings')
    const fee = await screen.findByLabelText('Platform fee (%)')
    await user.clear(fee)
    await user.type(fee, '12.5')
    await user.clear(screen.getByLabelText('Large city maximum (₹ per hour)'))
    await user.type(screen.getByLabelText('Large city maximum (₹ per hour)'), '150')

    await user.click(screen.getByRole('button', { name: 'Save settings' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Save these settings?' }))
    expect(dialog.getByText(/apply to new bookings only/i)).toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)
    await user.click(dialog.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Settings saved'))
    expect(JSON.parse(mock.history.put[0].data)).toEqual({
      ...settings,
      platformFeePercent: 12.5,
      priceGuidelines: [settings.priceGuidelines[0], { tier: 2, minHourly: 20, maxHourly: 150 }, settings.priceGuidelines[2]],
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('can be cancelled from the confirmation', async () => {
    const user = userEvent.setup()
    renderApp('/admin/settings')
    await user.click(await screen.findByRole('button', { name: 'Save settings' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)
  })

  it('maps server field errors onto the fields', async () => {
    mock.onPut('/admin/settings').reply(400, {
      code: 'INVALID_SETTING', detail: 'Invalid setting',
      fieldErrors: [
        { field: 'gstPercent', message: 'GST is capped by law' },
        { field: 'priceGuidelines[1].maxHourly', message: 'Too high for this tier' },
      ],
    })
    const user = userEvent.setup()
    renderApp('/admin/settings')
    await user.click(await screen.findByRole('button', { name: 'Save settings' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('GST is capped by law')).toBeInTheDocument()
    expect(screen.getByText('Too high for this tier')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('shows the server message when an invalid setting has no field', async () => {
    mock.onPut('/admin/settings').reply(400, { code: 'INVALID_SETTING', detail: 'Minimum must not exceed maximum' })
    const user = userEvent.setup()
    renderApp('/admin/settings')
    await user.click(await screen.findByRole('button', { name: 'Save settings' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Minimum must not exceed maximum')).toBeInTheDocument()
  })

  it('shows the server message when the settings fail to load', async () => {
    mock.onGet('/admin/settings').reply(500, { code: 'INTERNAL', detail: 'Settings are down' })
    renderApp('/admin/settings')

    expect(await screen.findByText('Settings are down')).toBeInTheDocument()
  })
})

const action = (id: number, overrides: Partial<AdminAction> = {}): AdminAction => ({
  id, adminName: 'Admin User', action: 'USER_SUSPENDED', targetType: 'USER', targetId: 7,
  details: 'Reason: spam', createdAt: '2026-10-08T10:00:00Z', ...overrides,
})

describe('admin audit log', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
  })

  afterEach(() => mock.restore())

  const calls = () => mock.history.get.filter((r) => r.url === '/admin/audit')

  it('lists the actions, newest first as served', async () => {
    mock.onGet('/admin/audit').reply(200, page([action(1), action(2, { action: 'SETTINGS_UPDATED', targetType: 'SETTINGS', targetId: null, details: null })]))
    renderApp('/admin/audit')

    const table = within(await screen.findByRole('table', { name: 'Audit log' }))
    expect(calls()[0].params).toEqual({ page: 0, size: 20 })
    expect(table.getByRole('row', { name: /Admin User USER_SUSPENDED USER 7 Reason: spam/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /SETTINGS_UPDATED SETTINGS —/ })).toBeInTheDocument()
  })

  it('filters by action and target type', async () => {
    mock.onGet('/admin/audit').reply(200, page([action(1)]))
    const user = userEvent.setup()
    renderApp('/admin/audit')
    await screen.findByRole('table', { name: 'Audit log' })

    await user.type(screen.getByLabelText('Action'), 'USER_SUSPENDED')
    await user.selectOptions(screen.getByLabelText('Target type'), 'User')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ action: 'USER_SUSPENDED', targetType: 'USER', page: 0, size: 20 }))

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(calls().at(-1)!.params).toEqual({ page: 0, size: 20 }))
  })

  it('pages through the log', async () => {
    mock.onGet('/admin/audit').reply((config) => [200, page([action(config.params.page + 1)], 2, config.params.page)])
    const user = userEvent.setup()
    renderApp('/admin/audit')
    await screen.findByRole('table', { name: 'Audit log' })

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(calls().at(-1)!.params).toMatchObject({ page: 1 }))
  })

  it('shows an empty state and server errors', async () => {
    mock.onGet('/admin/audit').replyOnce(200, page([]))
    renderApp('/admin/audit')
    expect(await screen.findByText('No actions match these filters.')).toBeInTheDocument()
  })

  it('shows the server message on failure', async () => {
    mock.onGet('/admin/audit').reply(500, { code: 'INTERNAL', detail: 'Audit is down' })
    renderApp('/admin/audit')
    expect(await screen.findByText('Audit is down')).toBeInTheDocument()
  })
})
