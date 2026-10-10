import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminCity, AdminState } from '../../lib/adminManage'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, Toaster: () => null }))

const admin = { id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null, role: 'ADMIN', emailVerified: true, avatarUrl: null }

const states: AdminState[] = [
  { id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', citiesCount: 2 },
  { id: 2, name: 'Karnataka', code: 'KA', slug: 'karnataka', type: 'STATE', capitalName: 'Bengaluru', citiesCount: 1 },
]

const city = (id: number, overrides: Partial<AdminCity> = {}): AdminCity => ({
  id, stateId: 1, stateName: 'Maharashtra', name: 'Pune', slug: 'pune', lat: 18.5204, lng: 73.8567, capital: false, tier: 1,
  active: true, listingsCount: 4, ...overrides,
})

const page = <T,>(content: T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 })

describe('admin locations', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    mock.onGet('/admin/states').reply(200, states)
    mock.onGet('/admin/cities', { params: { stateId: 1, page: 0, size: 100 } }).reply(200, page([
      city(10),
      city(11, { name: 'Nagpur', slug: 'nagpur', tier: 2, active: false, listingsCount: 0, lat: 21.1458, lng: 79.0882 }),
    ]))
    mock.onGet('/admin/cities', { params: { stateId: 2, page: 0, size: 100 } }).reply(200, page([
      city(20, { stateId: 2, stateName: 'Karnataka', name: 'Bengaluru', slug: 'bengaluru', capital: true }),
    ]))
    vi.mocked(toast.success).mockClear()
  })

  afterEach(() => mock.restore())

  it('lists the states and shows the cities of the first one', async () => {
    renderApp('/admin/locations')

    const list = within(await screen.findByRole('list', { name: 'States' }))
    expect(list.getByRole('button', { name: /Maharashtra/ })).toHaveAttribute('aria-current', 'true')
    expect(list.getByRole('button', { name: /Karnataka/ })).not.toHaveAttribute('aria-current')
    const table = within(await screen.findByRole('table', { name: 'Cities in Maharashtra' }))
    expect(table.getByRole('row', { name: /Pune pune 18.5204, 73.8567 Tier 1 Active 4/ })).toBeInTheDocument()
    expect(table.getByRole('row', { name: /Nagpur nagpur 21.1458, 79.0882 Tier 2 Inactive 0/ })).toBeInTheDocument()
  })

  it('switches state', async () => {
    const u = userEvent.setup()
    renderApp('/admin/locations')
    await screen.findByRole('table', { name: 'Cities in Maharashtra' })

    await u.click(screen.getByRole('button', { name: /Karnataka/ }))

    const table = within(await screen.findByRole('table', { name: 'Cities in Karnataka' }))
    expect(table.getByRole('row', { name: /Bengaluru/ })).toBeInTheDocument()
  })

  it('adds a city', async () => {
    mock.onPost('/admin/cities').reply(201, city(12, { name: 'Nashik' }))
    const u = userEvent.setup()
    renderApp('/admin/locations')
    await screen.findByRole('table', { name: 'Cities in Maharashtra' })

    await u.click(screen.getByRole('button', { name: 'Add city' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Add a city to Maharashtra' }))
    await u.type(dialog.getByLabelText('Name'), 'Nashik')
    await u.type(dialog.getByLabelText('Latitude'), '19.9975')
    await u.type(dialog.getByLabelText('Longitude'), '73.7898')
    await u.selectOptions(dialog.getByLabelText('Tier'), 'Tier 3 · Other')
    await u.click(dialog.getByRole('button', { name: 'Add city' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Nashik added'))
    expect(JSON.parse(mock.history.post[0].data)).toEqual({ stateId: 1, name: 'Nashik', lat: 19.9975, lng: 73.7898, tier: 3, active: true })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('validates the city form', async () => {
    const u = userEvent.setup()
    renderApp('/admin/locations')
    await screen.findByRole('table', { name: 'Cities in Maharashtra' })
    await u.click(screen.getByRole('button', { name: 'Add city' }))
    const dialog = within(await screen.findByRole('dialog'))

    await u.click(dialog.getByRole('button', { name: 'Add city' }))
    expect(await dialog.findByText('Enter the city name')).toBeInTheDocument()
    expect(dialog.getByText('Latitude must be between -90 and 90')).toBeInTheDocument()
    expect(dialog.getByText('Longitude must be between -180 and 180')).toBeInTheDocument()

    await u.type(dialog.getByLabelText('Name'), 'Nashik')
    await u.type(dialog.getByLabelText('Latitude'), '120')
    await u.type(dialog.getByLabelText('Longitude'), '73.7')
    await u.click(dialog.getByRole('button', { name: 'Add city' }))
    expect(await dialog.findByText('Latitude must be between -90 and 90')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('edits a city, including deactivating it', async () => {
    mock.onPatch('/admin/cities/10').reply(200, city(10, { active: false, tier: 2 }))
    const u = userEvent.setup()
    renderApp('/admin/locations')
    const row = await screen.findByRole('row', { name: /Pune pune/ })

    await u.click(within(row).getByRole('button', { name: 'Edit Pune' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'Edit Pune' }))
    expect(dialog.getByLabelText('Name')).toHaveValue('Pune')
    expect(dialog.getByLabelText('Latitude')).toHaveValue(18.5204)
    expect(dialog.getByLabelText('Tier')).toHaveValue('1')
    expect(dialog.getByLabelText('Active')).toBeChecked()
    await u.selectOptions(dialog.getByLabelText('Tier'), 'Tier 2 · Capital or large city')
    await u.click(dialog.getByLabelText('Active'))
    await u.click(dialog.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Pune updated'))
    expect(JSON.parse(mock.history.patch[0].data)).toEqual({ name: 'Pune', lat: 18.5204, lng: 73.8567, tier: 2, active: false })
  })

  it('maps server field errors and messages in the dialog', async () => {
    mock.onPost('/admin/cities').replyOnce(400, {
      code: 'VALIDATION_FAILED', detail: 'Invalid', fieldErrors: [{ field: 'name', message: 'A city with this name exists' }],
    })
    mock.onPost('/admin/cities').reply(409, { code: 'CITY_EXISTS', detail: 'That city already exists in this state' })
    const u = userEvent.setup()
    renderApp('/admin/locations')
    await screen.findByRole('table', { name: 'Cities in Maharashtra' })
    await u.click(screen.getByRole('button', { name: 'Add city' }))
    const dialog = within(await screen.findByRole('dialog'))
    await u.type(dialog.getByLabelText('Name'), 'Pune')
    fireEvent.change(dialog.getByLabelText('Latitude'), { target: { value: '18.5' } })
    fireEvent.change(dialog.getByLabelText('Longitude'), { target: { value: '73.8' } })

    await u.click(dialog.getByRole('button', { name: 'Add city' }))
    expect(await dialog.findByText('A city with this name exists')).toBeInTheDocument()

    await u.click(dialog.getByRole('button', { name: 'Add city' }))
    expect(await dialog.findByText('That city already exists in this state')).toBeInTheDocument()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('shows the server message when the states fail to load', async () => {
    mock.onGet('/admin/states').reply(500, { code: 'INTERNAL', detail: 'States are down' })
    renderApp('/admin/locations')

    expect(await screen.findByText('States are down')).toBeInTheDocument()
  })

  it('shows the server message when the cities fail to load', async () => {
    mock.onGet('/admin/cities', { params: { stateId: 1, page: 0, size: 100 } }).reply(500, { code: 'INTERNAL', detail: 'Cities are down' })
    renderApp('/admin/locations')

    expect(await screen.findByText('Cities are down')).toBeInTheDocument()
  })
})
