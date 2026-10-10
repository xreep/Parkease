import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { PlatformSettings } from '../../lib/admin'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }


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

  it('allows at most two decimals in the fee and GST', async () => {
    const user = userEvent.setup()
    renderApp('/admin/settings')
    const fee = await screen.findByLabelText('Platform fee (%)')
    await user.clear(fee)
    await user.type(fee, '12.555')
    const gst = screen.getByLabelText('GST (%)')
    await user.clear(gst)
    await user.type(gst, '18.123')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(await screen.findByText('Fee can have at most 2 decimals')).toBeInTheDocument()
    expect(screen.getByText('GST can have at most 2 decimals')).toBeInTheDocument()
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

