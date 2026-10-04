import '@testing-library/jest-dom/vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { ListingDetail, Photo, Slot } from '../../lib/owner'
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

  describe('step 2: photos', () => {
    const photoA: Photo = { id: 11, url: '/files/a.jpg', sortOrder: 0 }
    const photoB: Photo = { id: 12, url: '/files/b.jpg', sortOrder: 1 }
    const withPhotos = (photos: Photo[]) => ({ ...listing, photos })

    it('makes the second photo the cover by reordering', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA, photoB]))
      mock.onPut('/owner/listings/7/photos/order').reply(200, [])
      renderApp('/owner/listings/7/edit?step=2')

      expect(within(await screen.findByRole('listitem', { name: 'Photo 1' })).getByText('Cover')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Make photo 2 the cover' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ photoIds: [12, 11] })
    })

    it('moves a photo right and left', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA, photoB]))
      mock.onPut('/owner/listings/7/photos/order').reply(200, [])
      renderApp('/owner/listings/7/edit?step=2')

      await userEvent.click(await screen.findByRole('button', { name: 'Move photo 1 right' }))
      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ photoIds: [12, 11] })
      expect(screen.getByRole('button', { name: 'Move photo 1 left' })).toBeDisabled()
    })

    it('reorders by dragging one tile onto another', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA, photoB]))
      mock.onPut('/owner/listings/7/photos/order').reply(200, [])
      renderApp('/owner/listings/7/edit?step=2')

      const first = await screen.findByRole('listitem', { name: 'Photo 1' })
      const second = screen.getByRole('listitem', { name: 'Photo 2' })
      const dataTransfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' }
      fireEvent.dragStart(first, { dataTransfer })
      fireEvent.dragOver(second, { dataTransfer })
      fireEvent.drop(second, { dataTransfer })

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ photoIds: [12, 11] })
    })

    it('deletes a photo', async () => {
      mock.onGet('/owner/listings/7').replyOnce(200, withPhotos([photoA, photoB])).onGet('/owner/listings/7').reply(200, withPhotos([photoB]))
      mock.onDelete('/owner/listings/7/photos/11').reply(204)
      renderApp('/owner/listings/7/edit?step=2')

      await userEvent.click(await screen.findByRole('button', { name: 'Delete photo 1' }))

      await waitFor(() => expect(mock.history.delete).toHaveLength(1))
      await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Photo 2' })).not.toBeInTheDocument())
    })

    it('uploads a chosen file', async () => {
      const uploaded = { id: 13, url: '/files/c.jpg', sortOrder: 1 }
      mock.onGet('/owner/listings/7').replyOnce(200, withPhotos([photoA])).onGet('/owner/listings/7').reply(200, withPhotos([photoA, uploaded]))
      mock.onPost('/owner/listings/7/photos').reply(201, uploaded)
      renderApp('/owner/listings/7/edit?step=2')

      const input = await screen.findByLabelText('Photo files')
      expect(input).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp')
      await userEvent.upload(input, new File(['jpeg'], 'gate.jpg', { type: 'image/jpeg' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      const post = mock.history.post[0]
      expect(post.url).toBe('/owner/listings/7/photos')
      expect((post.data as FormData).get('file')).toBeInstanceOf(File)
      expect(await screen.findByRole('listitem', { name: 'Photo 2' })).toBeInTheDocument()
    })

    it('skips files beyond the 8 photo limit', async () => {
      const seven = Array.from({ length: 7 }, (_, i) => ({ id: 20 + i, url: `/files/${i}.jpg`, sortOrder: i }))
      mock.onGet('/owner/listings/7').reply(200, withPhotos(seven))
      mock.onPost('/owner/listings/7/photos').reply(201, { id: 30, url: '/files/x.jpg', sortOrder: 7 })
      renderApp('/owner/listings/7/edit?step=2')

      const files = ['a', 'b', 'c'].map((n) => new File(['x'], `${n}.jpg`, { type: 'image/jpeg' }))
      await userEvent.upload(await screen.findByLabelText('Photo files'), files)

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(toast.error).toHaveBeenCalledWith('A listing can have at most 8 photos')
    })

    it('needs a photo before continuing', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([]))
      renderApp('/owner/listings/7/edit?step=2')

      expect(await screen.findByText('Add at least one photo of the entrance and the parking area.')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
      expect(screen.getByText('Add at least one photo to continue')).toBeInTheDocument()
    })

    it('continues to the slots step', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA]))
      renderApp('/owner/listings/7/edit?step=2')

      await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))

      expect(await screen.findByRole('link', { name: 'Slots' })).toHaveAttribute('aria-current', 'step')
    })

    it('goes back to the location step', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA]))
      renderApp('/owner/listings/7/edit?step=2')

      await userEvent.click(await screen.findByRole('button', { name: 'Back' }))

      expect(await screen.findByRole('link', { name: 'Location' })).toHaveAttribute('aria-current', 'step')
    })
  })

  describe('step 3: slots', () => {
    const slotA: Slot = { id: 31, label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true }
    const slotB: Slot = { id: 32, label: 'B-01', vehicleType: 'TWO_WHEELER', size: 'SMALL', active: true }
    const withSlots = (slots: Slot[]) => ({ ...listing, slots })

    it('adds slots in bulk with a live preview', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      mock.onPost('/owner/listings/7/slots/bulk').reply(201, [])
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add many slots' }))
      expect(form.getByText('Creates A-01 to A-10')).toBeInTheDocument()
      const prefix = form.getByLabelText('Label prefix')
      await userEvent.clear(prefix)
      await userEvent.type(prefix, 'B-')
      const count = form.getByLabelText('How many')
      await userEvent.clear(count)
      await userEvent.type(count, '3')
      await userEvent.selectOptions(form.getByLabelText('Vehicle type'), 'Two-wheeler')
      await userEvent.selectOptions(form.getByLabelText('Size'), 'Small')
      expect(form.getByText('Creates B-01 to B-03')).toBeInTheDocument()
      await userEvent.click(form.getByRole('button', { name: 'Add slots' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(JSON.parse(mock.history.post[0].data)).toEqual({
        prefix: 'B-', startNumber: 1, count: 3, vehicleType: 'TWO_WHEELER', size: 'SMALL',
      })
    })

    it('adds a single slot', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      mock.onPost('/owner/listings/7/slots').reply(201, slotA)
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add one slot' }))
      await userEvent.type(form.getByLabelText('Slot label'), 'Bay 1')
      await userEvent.selectOptions(form.getByLabelText('Vehicle type'), 'Car')
      await userEvent.selectOptions(form.getByLabelText('Size'), 'Large')
      await userEvent.click(form.getByRole('button', { name: 'Add slot' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ label: 'Bay 1', vehicleType: 'FOUR_WHEELER', size: 'LARGE' })
    })

    it('shows the server message when a label is taken', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA]))
      mock.onPost('/owner/listings/7/slots').reply(409, { code: 'SLOT_LABEL_TAKEN', detail: 'A slot named A-01 already exists' })
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add one slot' }))
      await userEvent.type(form.getByLabelText('Slot label'), 'A-01')
      await userEvent.click(form.getByRole('button', { name: 'Add slot' }))

      expect(await screen.findByText('A slot named A-01 already exists')).toBeInTheDocument()
    })

    it('validates the bulk form before sending', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add many slots' }))
      const count = form.getByLabelText('How many')
      await userEvent.clear(count)
      await userEvent.type(count, '51')
      await userEvent.click(form.getByRole('button', { name: 'Add slots' }))

      expect(await form.findByText('Add between 1 and 50 slots at a time')).toBeInTheDocument()
      expect(mock.history.post).toHaveLength(0)
    })

    it('summarises the slots and lists them', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA, slotB]))
      renderApp('/owner/listings/7/edit?step=3')

      expect(await screen.findByText('2 slots · 1 car · 1 two-wheeler')).toBeInTheDocument()
      const row = within(screen.getByRole('listitem', { name: 'Slot B-01' }))
      expect(row.getByText('Two-wheeler · Small')).toBeInTheDocument()
    })

    it('deactivates a slot', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA]))
      mock.onPut('/owner/listings/7/slots/31').reply(200, { ...slotA, active: false })
      renderApp('/owner/listings/7/edit?step=3')

      await userEvent.click(await screen.findByRole('checkbox', { name: 'Slot A-01 active' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: false })
    })

    it('edits a slot in a dialog', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA]))
      mock.onPut('/owner/listings/7/slots/31').reply(200, slotA)
      renderApp('/owner/listings/7/edit?step=3')

      await userEvent.click(within(await screen.findByRole('listitem', { name: 'Slot A-01' })).getByRole('button', { name: 'Edit' }))
      const dialog = within(screen.getByRole('dialog', { name: 'Edit slot A-01' }))
      const label = dialog.getByLabelText('Slot label')
      await userEvent.clear(label)
      await userEvent.type(label, 'A-1')
      await userEvent.selectOptions(dialog.getByLabelText('Size'), 'Large')
      await userEvent.click(dialog.getByRole('button', { name: 'Save changes' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ label: 'A-1', vehicleType: 'FOUR_WHEELER', size: 'LARGE', active: true })
    })

    it('asks before deleting a slot', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA]))
      mock.onDelete('/owner/listings/7/slots/31').reply(204)
      renderApp('/owner/listings/7/edit?step=3')

      await userEvent.click(within(await screen.findByRole('listitem', { name: 'Slot A-01' })).getByRole('button', { name: 'Delete' }))
      const dialog = screen.getByRole('dialog', { name: 'Delete slot A-01?' })
      expect(mock.history.delete).toHaveLength(0)
      await userEvent.click(within(dialog).getByRole('button', { name: 'Delete slot' }))

      await waitFor(() => expect(mock.history.delete).toHaveLength(1))
    })

    it('needs an active slot before continuing', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([{ ...slotA, active: false }]))
      renderApp('/owner/listings/7/edit?step=3')

      expect(await screen.findByRole('button', { name: 'Continue' })).toBeDisabled()
      expect(screen.getByText('Add at least one slot to continue')).toBeInTheDocument()
    })

    it('continues to the pricing step', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([slotA]))
      renderApp('/owner/listings/7/edit?step=3')

      await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))

      expect(await screen.findByRole('link', { name: 'Pricing' })).toHaveAttribute('aria-current', 'step')
    })
  })
})
