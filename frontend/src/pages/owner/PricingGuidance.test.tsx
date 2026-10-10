import '@testing-library/jest-dom/vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { ListingDetail } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))
vi.mock('../../components/owner/LocationPicker', () => ({ LocationPicker: () => <div>Map</div> }))

const owner = { id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null, role: 'OWNER', emailVerified: true, avatarUrl: null }

const listing: ListingDetail = {
  id: 7, title: 'FC Road Parking', description: 'Near the market', address: '12 FC Road', pincode: '411004',
  lat: 18.5204, lng: 73.8567, listingType: 'OFFICE', cityId: 51, cityName: 'Pune', stateName: 'Maharashtra',
  status: 'DRAFT', rejectionReason: null, open24x7: false, rules: null, autoApprove: false,
  pricePerHour: null, pricePerDay: null, pricePerMonth: null, cancellationPolicy: null, amenities: [],
  photos: [], slots: [], hours: [], submittedAt: null, approvedAt: null, updatedAt: '2026-10-05T10:00:00Z',
}

describe('pricing guidance in the wizard', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/listings/7').reply(200, listing)
    mock.onGet('/pricing-guidelines').reply(200, { tier: 1, minHourly: 40, maxHourly: 200 })
  })

  afterEach(() => mock.restore())

  it('shows the usual range for the listing’s city', async () => {
    renderApp('/owner/listings/7/edit?step=4')

    expect(await screen.findByText('Usual range in Pune: ₹40 to ₹200 per hour')).toBeInTheDocument()
    expect(mock.history.get.find((r) => r.url === '/pricing-guidelines')!.params).toEqual({ cityId: 51 })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('warns above the range but still saves', async () => {
    mock.onPut('/owner/listings/7/pricing').reply(200, { ...listing, pricePerHour: 500 })
    renderApp('/owner/listings/7/edit?step=4')
    await screen.findByText('Usual range in Pune: ₹40 to ₹200 per hour')

    await userEvent.type(screen.getByLabelText('Price per hour (₹)'), '500')

    expect(await screen.findByRole('status')).toHaveTextContent('₹500 is above the usual range for Pune (₹40 to ₹200). You can still save this price.')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))
    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(JSON.parse(mock.history.put[0].data)).toMatchObject({ pricePerHour: 500 })
  })

  it('warns below the range, and clears the warning inside it', async () => {
    renderApp('/owner/listings/7/edit?step=4')
    const price = await screen.findByLabelText('Price per hour (₹)')

    await userEvent.type(price, '10')
    expect(await screen.findByRole('status')).toHaveTextContent('₹10 is below the usual range for Pune (₹40 to ₹200). You can still save this price.')

    await userEvent.clear(price)
    await userEvent.type(price, '80')
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  })

  it('shows nothing and does not block when the guideline cannot be loaded', async () => {
    mock.onGet('/pricing-guidelines').reply(500, { code: 'INTERNAL', detail: 'Down' })
    mock.onPut('/owner/listings/7/pricing').reply(200, { ...listing, pricePerHour: 50 })
    renderApp('/owner/listings/7/edit?step=4')

    await userEvent.type(await screen.findByLabelText('Price per hour (₹)'), '50')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(screen.queryByText(/Usual range/)).not.toBeInTheDocument()
  })
})
