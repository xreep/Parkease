import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { ListingDetail } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'
import { toast } from 'sonner'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

type PickerProps = {
  value: { lat: number; lng: number } | null
  center: { lat: number; lng: number }
  onChange: (pos: { lat: number; lng: number }) => void
}

vi.mock('../../components/owner/LocationPicker', () => ({
  LocationPicker: ({ value, onChange }: PickerProps) => (
    <div>
      <label>
        Latitude
        <input type="number" value={value?.lat ?? ''} onChange={(e) => onChange({ lat: Number(e.target.value), lng: value?.lng ?? 0 })} />
      </label>
      <label>
        Longitude
        <input type="number" value={value?.lng ?? ''} onChange={(e) => onChange({ lat: value?.lat ?? 0, lng: Number(e.target.value) })} />
      </label>
      <button type="button" onClick={() => onChange({ lat: 18.5204, lng: 73.8567 })}>Place pin here</button>
    </div>
  ),
}))

const owner = {
  id: 3, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null,
  role: 'OWNER', emailVerified: true, avatarUrl: null,
}

const maharashtra = { id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cityCount: 1 }
const maharashtraDetail = {
  id: 1, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai',
  cities: [{
    id: 51, name: 'Pune', slug: 'pune', lat: 18.5204, lng: 73.8567, capital: false,
    stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra',
  }],
}

const listing: ListingDetail = {
  id: 7, title: 'FC Road Parking', description: 'Near the market', address: '12 FC Road', pincode: '411004',
  lat: 18.5204, lng: 73.8567, listingType: 'OFFICE', cityId: 51, cityName: 'Pune', stateName: 'Maharashtra',
  status: 'DRAFT', rejectionReason: null, open24x7: false, rules: null, autoApprove: false,
  pricePerHour: null, pricePerDay: null, pricePerMonth: null, cancellationPolicy: null, amenities: [],
  photos: [], slots: [], hours: [], submittedAt: null, approvedAt: null, updatedAt: '2026-10-05T10:00:00Z',
}

describe('listing wizard', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/states').reply(200, [maharashtra])
    mock.onGet('/states/maharashtra').reply(200, maharashtraDetail)
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  async function fillLocationForm() {
    await userEvent.selectOptions(await screen.findByLabelText('State'), 'Maharashtra')
    await screen.findByRole('option', { name: 'Pune' })
    await userEvent.selectOptions(screen.getByLabelText('City'), 'Pune')
    await userEvent.type(screen.getByLabelText('Listing title'), 'FC Road Parking')
    await userEvent.selectOptions(screen.getByLabelText('Parking type'), 'Office complex')
    await userEvent.type(screen.getByLabelText('Address'), '12 FC Road')
    await userEvent.type(screen.getByLabelText('PIN code'), '411004')
  }

  describe('step 1: location', () => {
    it('creates the listing and moves to the photos step', async () => {
      mock.onPost('/owner/listings').reply(201, listing)
      mock.onGet('/owner/listings/7').reply(200, listing)
      renderApp('/owner/listings/new')

      await fillLocationForm()
      await userEvent.click(screen.getByRole('button', { name: 'Place pin here' }))
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(JSON.parse(mock.history.post[0].data)).toMatchObject({
        cityId: 51, title: 'FC Road Parking', listingType: 'OFFICE', address: '12 FC Road',
        pincode: '411004', lat: 18.5204, lng: 73.8567,
      })
      const photos = await screen.findByRole('link', { name: 'Photos' })
      expect(photos).toHaveAttribute('aria-current', 'step')
      expect(screen.getByText('FC Road Parking')).toBeInTheDocument()
      for (const label of ['Location', 'Slots', 'Pricing', 'Hours', 'Review']) {
        expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
      }
    })

    it('shows the server message when the pin is outside the city', async () => {
      mock.onPost('/owner/listings').reply(400, {
        code: 'LOCATION_OUTSIDE_CITY', detail: 'The map pin must be within 60 km of Pune',
      })
      renderApp('/owner/listings/new')

      await fillLocationForm()
      await userEvent.click(screen.getByRole('button', { name: 'Place pin here' }))
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('The map pin must be within 60 km of Pune')).toBeInTheDocument()
    })

    it('requires a pin on the map', async () => {
      renderApp('/owner/listings/new')

      await fillLocationForm()
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Place the pin on the map')).toBeInTheDocument()
      expect(mock.history.post).toHaveLength(0)
    })

    it('validates the PIN code and the city', async () => {
      renderApp('/owner/listings/new')

      await userEvent.type(await screen.findByLabelText('PIN code'), '01234')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Enter a valid 6-digit PIN code')).toBeInTheDocument()
      expect(screen.getByText('Choose a city')).toBeInTheDocument()
      expect(mock.history.post).toHaveLength(0)
    })

    it('looks the address up on Nominatim and drops the pin', async () => {
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify([{ lat: '18.5300', lon: '73.8400' }]), { status: 200 }),
      )
      renderApp('/owner/listings/new')

      await fillLocationForm()
      await userEvent.click(screen.getByRole('button', { name: 'Find address on map' }))

      await waitFor(() => expect(screen.getByLabelText('Latitude')).toHaveValue(18.53))
      expect(screen.getByLabelText('Longitude')).toHaveValue(73.84)
      const url = String(fetchMock.mock.calls[0][0])
      expect(url).toContain('https://nominatim.openstreetmap.org/search?')
      expect(url).toContain('countrycodes=in')
      expect(decodeURIComponent(url.replace(/\+/g, ' '))).toContain('12 FC Road, Pune, Maharashtra')
    })

    it('tells the owner when the address is not found', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
      renderApp('/owner/listings/new')

      await fillLocationForm()
      await userEvent.click(screen.getByRole('button', { name: 'Find address on map' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Address not found — place the pin manually'))
    })

    it('prefills an existing listing and saves it with a PUT', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onPut('/owner/listings/7').reply(200, { ...listing, title: 'FC Road Garage' })
      renderApp('/owner/listings/7/edit?step=1')

      const title = await screen.findByLabelText('Listing title')
      await waitFor(() => expect(screen.getByLabelText('State')).toHaveValue('maharashtra'))
      await waitFor(() => expect(screen.getByLabelText('City')).toHaveValue('51'))
      expect(title).toHaveValue('FC Road Parking')
      expect(screen.getByLabelText('PIN code')).toHaveValue('411004')
      expect(screen.getByLabelText('Latitude')).toHaveValue(18.5204)

      await userEvent.clear(title)
      await userEvent.type(title, 'FC Road Garage')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toMatchObject({ cityId: 51, title: 'FC Road Garage', lat: 18.5204 })
      expect(await screen.findByRole('link', { name: 'Photos' })).toHaveAttribute('aria-current', 'step')
    })

    it('shows the rejection note on a rejected listing', async () => {
      mock.onGet('/owner/listings/7').reply(200, { ...listing, status: 'REJECTED', rejectionReason: 'Photos are blurry' })
      renderApp('/owner/listings/7/edit')

      expect(await screen.findByText('Changes requested: Photos are blurry')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Location' })).toHaveAttribute('aria-current', 'step')
    })

    it('blocks editing a suspended listing', async () => {
      mock.onGet('/owner/listings/7').reply(200, { ...listing, status: 'SUSPENDED' })
      renderApp('/owner/listings/7/edit')

      expect(await screen.findByText("This listing was suspended by ParkEase and can't be edited.")).toBeInTheDocument()
      expect(await screen.findByRole('button', { name: 'Save and continue' })).toBeDisabled()
    })

    it('links every step once the listing exists', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      renderApp('/owner/listings/7/edit?step=4')

      const nav = await screen.findByRole('navigation', { name: 'Listing steps' })
      expect(within(nav).getByRole('link', { name: 'Hours' })).toHaveAttribute('href', '/owner/listings/7/edit?step=5')
      expect(within(nav).getByRole('link', { name: 'Pricing' })).toHaveAttribute('aria-current', 'step')
    })
  })
})
