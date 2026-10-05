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
  readOnly?: boolean
}

vi.mock('../../components/owner/LocationPicker', () => ({
  LocationPicker: ({ value, onChange, readOnly }: PickerProps) => (
    <div>
      {readOnly && <p>Pin locked</p>}
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
      await waitFor(() => expect(screen.getByRole('link', { name: 'Photos' })).toHaveAttribute('aria-current', 'step'))
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
      expect(screen.getByText('Pin locked')).toBeInTheDocument()
    })

    it('links every step once the listing exists', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      renderApp('/owner/listings/7/edit?step=4')

      const nav = await screen.findByRole('navigation', { name: 'Listing steps' })
      expect(within(nav).getByRole('link', { name: 'Hours' })).toHaveAttribute('href', '/owner/listings/7/edit?step=5')
      expect(within(nav).getByRole('link', { name: 'Pricing' })).toHaveAttribute('aria-current', 'step')
    })

    it('scrolls the current step into view', async () => {
      const scrollIntoView = vi.fn()
      Element.prototype.scrollIntoView = scrollIntoView
      try {
        mock.onGet('/owner/listings/7').reply(200, listing)
        renderApp('/owner/listings/7/edit?step=4')

        const nav = await screen.findByRole('navigation', { name: 'Listing steps' })
        await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest' }))
        expect(scrollIntoView.mock.contexts.at(-1)).toBe(within(nav).getByRole('link', { name: 'Pricing' }).closest('li'))
      } finally {
        delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
      }
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

      await waitFor(() => expect(screen.getByRole('link', { name: 'Slots' })).toHaveAttribute('aria-current', 'step'))
    })

    it('goes back to the location step', async () => {
      mock.onGet('/owner/listings/7').reply(200, withPhotos([photoA]))
      renderApp('/owner/listings/7/edit?step=2')

      await userEvent.click(await screen.findByRole('button', { name: 'Back' }))

      await waitFor(() => expect(screen.getByRole('link', { name: 'Location' })).toHaveAttribute('aria-current', 'step'))
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

      await waitFor(() => expect(screen.getByRole('link', { name: 'Pricing' })).toHaveAttribute('aria-current', 'step'))
    })

    it('pads the preview to three digits when the last number reaches 100', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add many slots' }))
      const start = form.getByLabelText('Start number')
      await userEvent.clear(start)
      await userEvent.type(start, '95')

      expect(form.getByText('Creates A-095 to A-104')).toBeInTheDocument()
    })

    it('hides the preview when the numbers are out of range', async () => {
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add many slots' }))
      const count = form.getByLabelText('How many')
      await userEvent.clear(count)
      await userEvent.type(count, '51')
      expect(form.queryByText(/^Creates /)).not.toBeInTheDocument()

      await userEvent.clear(count)
      await userEvent.type(count, '5')
      expect(form.getByText('Creates A-01 to A-05')).toBeInTheDocument()
      const start = form.getByLabelText('Start number')
      await userEvent.clear(start)
      await userEvent.type(start, '1000')
      expect(form.queryByText(/^Creates /)).not.toBeInTheDocument()
    })

    it('reports how many slots were created and advances the start number', async () => {
      const made = (n: number): Slot => ({ id: 40 + n, label: `A-0${n}`, vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true })
      mock.onGet('/owner/listings/7').reply(200, withSlots([]))
      mock.onPost('/owner/listings/7/slots/bulk').replyOnce(201, [made(1), made(2), made(3)]).onPost('/owner/listings/7/slots/bulk').reply(201, [made(4)])
      vi.mocked(toast.success).mockClear()
      renderApp('/owner/listings/7/edit?step=3')

      const form = within(await screen.findByRole('form', { name: 'Add many slots' }))
      const count = form.getByLabelText('How many')
      await userEvent.clear(count)
      await userEvent.type(count, '3')
      await userEvent.click(form.getByRole('button', { name: 'Add slots' }))

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Added 3 slots'))
      await waitFor(() => expect(form.getByLabelText('Start number')).toHaveValue(4))
      expect(form.getByText('Creates A-04 to A-06')).toBeInTheDocument()

      await userEvent.clear(count)
      await userEvent.type(count, '1')
      await userEvent.click(form.getByRole('button', { name: 'Add slots' }))
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Added 1 slot'))
    })
  })

  describe('step 4: pricing', () => {
    beforeEach(() => {
      mock.onGet('/owner/listings/7').reply(200, listing)
    })

    it('rejects a daily price below the hourly price', async () => {
      renderApp('/owner/listings/7/edit?step=4')

      await userEvent.type(await screen.findByLabelText('Price per hour (₹)'), '50')
      await userEvent.type(screen.getByLabelText('Price per day (₹, optional)'), '40')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText("Daily price can't be lower than the hourly price")).toBeInTheDocument()
      expect(mock.history.put).toHaveLength(0)
    })

    it('rejects a monthly price below the daily price', async () => {
      renderApp('/owner/listings/7/edit?step=4')

      await userEvent.type(await screen.findByLabelText('Price per hour (₹)'), '50')
      await userEvent.type(screen.getByLabelText('Price per day (₹, optional)'), '300')
      await userEvent.type(screen.getByLabelText('Price per month (₹, optional)'), '200')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText("Monthly price can't be lower than the daily price")).toBeInTheDocument()
      expect(mock.history.put).toHaveLength(0)
    })

    it('requires the hourly price', async () => {
      renderApp('/owner/listings/7/edit?step=4')

      await userEvent.click(await screen.findByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Enter the hourly price')).toBeInTheDocument()
      expect(mock.history.put).toHaveLength(0)
    })

    it('saves pricing, policy, auto-approve and amenities, then goes to hours', async () => {
      mock.onPut('/owner/listings/7/pricing').reply(200, { ...listing, pricePerHour: 50 })
      renderApp('/owner/listings/7/edit?step=4')

      await userEvent.type(await screen.findByLabelText('Price per hour (₹)'), '50')
      await userEvent.type(screen.getByLabelText('Price per day (₹, optional)'), '400')
      await userEvent.click(screen.getByLabelText('Strict'))
      await userEvent.click(screen.getByLabelText('Approve bookings automatically'))
      await userEvent.click(screen.getByLabelText('CCTV'))
      await userEvent.click(screen.getByLabelText('Covered'))
      await userEvent.type(screen.getByLabelText('Parking rules (optional)'), 'No overnight parking')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({
        pricePerHour: 50, pricePerDay: 400, pricePerMonth: null, cancellationPolicy: 'STRICT',
        autoApprove: true, amenities: ['COVERED', 'CCTV'], rules: 'No overnight parking',
      })
      await waitFor(() => expect(screen.getByRole('link', { name: 'Hours' })).toHaveAttribute('aria-current', 'step'))
    })

    it('prefills the saved pricing', async () => {
      mock.onGet('/owner/listings/7').reply(200, {
        ...listing, pricePerHour: 30, pricePerDay: 200, cancellationPolicy: 'FLEXIBLE', autoApprove: true,
        amenities: ['CCTV'], rules: 'Be kind',
      })
      renderApp('/owner/listings/7/edit?step=4')

      expect(await screen.findByLabelText('Price per hour (₹)')).toHaveValue('30')
      expect(screen.getByLabelText('Price per day (₹, optional)')).toHaveValue('200')
      expect(screen.getByLabelText('Price per month (₹, optional)')).toHaveValue('')
      expect(screen.getByLabelText('Flexible')).toBeChecked()
      expect(screen.getByLabelText('Approve bookings automatically')).toBeChecked()
      expect(screen.getByLabelText('CCTV')).toBeChecked()
      expect(screen.getByLabelText('Covered')).not.toBeChecked()
      expect(screen.getByLabelText('Parking rules (optional)')).toHaveValue('Be kind')
    })

    it('shows the server message for inconsistent pricing', async () => {
      mock.onPut('/owner/listings/7/pricing').reply(400, { code: 'INVALID_PRICING', detail: 'Daily price must not be below the hourly price' })
      renderApp('/owner/listings/7/edit?step=4')

      await userEvent.type(await screen.findByLabelText('Price per hour (₹)'), '50')
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Daily price must not be below the hourly price')).toBeInTheDocument()
    })
  })

  describe('step 5: hours', () => {
    it('saves only the ticked days', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: true, rules: [] })
      mock.onPut('/owner/listings/7/hours').reply(200, { open24x7: false, rules: [] })
      renderApp('/owner/listings/7/edit?step=5')

      const toggle = await screen.findByLabelText('Open 24 × 7')
      await waitFor(() => expect(toggle).toBeChecked())
      await userEvent.click(toggle)
      await userEvent.click(screen.getByLabelText('Open on Monday'))
      fireEvent.change(screen.getByLabelText('Monday opens'), { target: { value: '09:00' } })
      fireEvent.change(screen.getByLabelText('Monday closes'), { target: { value: '18:00' } })
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({
        open24x7: false, rules: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }],
      })
      await waitFor(() => expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute('aria-current', 'step'))
    })

    it('sends no rules when open 24 x 7', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: true, rules: [] })
      mock.onPut('/owner/listings/7/hours').reply(200, { open24x7: true, rules: [] })
      renderApp('/owner/listings/7/edit?step=5')

      const toggle = await screen.findByLabelText('Open 24 × 7')
      await waitFor(() => expect(toggle).toBeChecked())
      expect(screen.queryByLabelText('Open on Monday')).not.toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ open24x7: true, rules: [] })
    })

    it('prefills saved rules', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, {
        open24x7: false, rules: [{ dayOfWeek: 3, openTime: '07:30', closeTime: '20:00' }],
      })
      renderApp('/owner/listings/7/edit?step=5')

      await waitFor(() => expect(screen.getByLabelText('Open on Wednesday')).toBeChecked())
      expect(screen.getByLabelText('Wednesday opens')).toHaveValue('07:30')
      expect(screen.getByLabelText('Wednesday closes')).toHaveValue('20:00')
      expect(screen.getByLabelText('Open on Monday')).not.toBeChecked()
    })

    it('rejects a closing time that is not after the opening time', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: false, rules: [] })
      renderApp('/owner/listings/7/edit?step=5')

      await userEvent.click(await screen.findByLabelText('Open on Tuesday'))
      fireEvent.change(screen.getByLabelText('Tuesday opens'), { target: { value: '18:00' } })
      fireEvent.change(screen.getByLabelText('Tuesday closes'), { target: { value: '09:00' } })
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Tuesday: closing time must be after opening time')).toBeInTheDocument()
      expect(mock.history.put).toHaveLength(0)
    })

    it('needs at least one open day unless open 24 x 7', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: false, rules: [] })
      renderApp('/owner/listings/7/edit?step=5')

      await userEvent.click(await screen.findByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Choose at least one open day, or switch on Open 24 × 7')).toBeInTheDocument()
      expect(mock.history.put).toHaveLength(0)
    })

    it('copies Monday to every day', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, {
        open24x7: false, rules: [{ dayOfWeek: 1, openTime: '10:00', closeTime: '16:00' }],
      })
      renderApp('/owner/listings/7/edit?step=5')

      await waitFor(() => expect(screen.getByLabelText('Open on Monday')).toBeChecked())
      await userEvent.click(screen.getByRole('button', { name: 'Copy Monday to all days' }))

      for (const day of ['Tuesday', 'Sunday']) {
        expect(screen.getByLabelText(`Open on ${day}`)).toBeChecked()
        expect(screen.getByLabelText(`${day} opens`)).toHaveValue('10:00')
        expect(screen.getByLabelText(`${day} closes`)).toHaveValue('16:00')
      }
    })

    it('copies Monday to every day and ticks them all, even when Monday starts unticked', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, {
        open24x7: false, rules: [{ dayOfWeek: 3, openTime: '10:00', closeTime: '16:00' }],
      })
      renderApp('/owner/listings/7/edit?step=5')

      await waitFor(() => expect(screen.getByLabelText('Open on Wednesday')).toBeChecked())
      expect(screen.getByLabelText('Open on Monday')).not.toBeChecked()
      fireEvent.change(screen.getByLabelText('Monday opens'), { target: { value: '07:00' } })
      fireEvent.change(screen.getByLabelText('Monday closes'), { target: { value: '19:30' } })
      await userEvent.click(screen.getByRole('button', { name: 'Copy Monday to all days' }))

      for (const day of ['Monday', 'Tuesday', 'Wednesday', 'Sunday']) {
        expect(screen.getByLabelText(`Open on ${day}`)).toBeChecked()
        expect(screen.getByLabelText(`${day} opens`)).toHaveValue('07:00')
        expect(screen.getByLabelText(`${day} closes`)).toHaveValue('19:30')
      }
    })

    it('applies the weekdays preset', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: false, rules: [] })
      renderApp('/owner/listings/7/edit?step=5')

      await userEvent.click(await screen.findByRole('button', { name: 'Weekdays 8 AM – 10 PM' }))

      expect(screen.getByLabelText('Open on Friday')).toBeChecked()
      expect(screen.getByLabelText('Friday opens')).toHaveValue('08:00')
      expect(screen.getByLabelText('Friday closes')).toHaveValue('22:00')
      expect(screen.getByLabelText('Open on Saturday')).not.toBeChecked()
    })

    it('shows the server message for invalid hours', async () => {
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onGet('/owner/listings/7/hours').reply(200, { open24x7: true, rules: [] })
      mock.onPut('/owner/listings/7/hours').reply(400, { code: 'INVALID_HOURS', detail: 'Closing time must be after opening time' })
      renderApp('/owner/listings/7/edit?step=5')

      await waitFor(() => expect(screen.getByLabelText('Open 24 × 7')).toBeChecked())
      await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

      expect(await screen.findByText('Closing time must be after opening time')).toBeInTheDocument()
    })
  })

  describe('step 6: review and submit', () => {
    const verified = {
      verificationStatus: 'VERIFIED', documentType: 'PAN', hasDocument: true, documentSubmittedAt: null, rejectionReason: null,
      verifiedAt: '2026-10-01T10:00:00Z', payoutUpi: null, payoutAccountName: null, payoutIfsc: null, payoutBankAccountLast4: null,
    }
    const complete: ListingDetail = {
      ...listing, pricePerHour: 50, pricePerDay: 400, cancellationPolicy: 'MODERATE', amenities: ['CCTV'],
      photos: [{ id: 11, url: '/files/a.jpg', sortOrder: 0 }],
      slots: [{ id: 31, label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true }],
      hours: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }],
    }

    beforeEach(() => {
      mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    })

    it('summarises the listing', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, complete)
      renderApp('/owner/listings/7/edit?step=6')

      expect(await screen.findByText('12 FC Road, Pune, Maharashtra 411004')).toBeInTheDocument()
      expect(screen.getByText('Lat 18.52040, Lng 73.85670')).toBeInTheDocument()
      expect(screen.getByRole('img', { name: 'Parking photo 1' })).toHaveAttribute('src', '/files/a.jpg')
      expect(screen.getByText('1 slot · 1 car · 0 two-wheeler')).toBeInTheDocument()
      expect(screen.getByText('₹50/hr')).toBeInTheDocument()
      expect(screen.getByText('₹400/day')).toBeInTheDocument()
      expect(screen.getByText('Monday: 09:00 – 18:00')).toBeInTheDocument()
      expect(screen.getByText('CCTV')).toBeInTheDocument()
    })

    it('shows Open 24 × 7 when the listing is always open', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, { ...complete, open24x7: true, hours: [] })
      renderApp('/owner/listings/7/edit?step=6')

      expect(await screen.findByText('Open 24 × 7')).toBeInTheDocument()
    })

    it('links to the missing steps when the server says the listing is incomplete', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, listing)
      mock.onPost('/owner/listings/7/submit').reply(400, {
        code: 'LISTING_INCOMPLETE', detail: 'Complete all steps before submitting', missing: ['PHOTOS', 'AVAILABILITY'],
      })
      renderApp('/owner/listings/7/edit?step=6')

      const submit = await screen.findByRole('button', { name: 'Submit for approval' })
      await waitFor(() => expect(submit).toBeEnabled())
      await userEvent.click(submit)

      expect(await screen.findByRole('link', { name: 'Add photos' })).toHaveAttribute('href', '/owner/listings/7/edit?step=2')
      expect(screen.getByRole('link', { name: 'Set opening hours' })).toHaveAttribute('href', '/owner/listings/7/edit?step=5')
      expect(screen.queryByRole('link', { name: 'Add slots' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Set pricing' })).not.toBeInTheDocument()
    })

    it('submits and lands on My listings', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, complete)
      mock.onPost('/owner/listings/7/submit').reply(200, { ...complete, status: 'PENDING_REVIEW' })
      vi.mocked(toast.success).mockClear()
      renderApp('/owner/listings/7/edit?step=6')

      const submit = await screen.findByRole('button', { name: 'Submit for approval' })
      await waitFor(() => expect(submit).toBeEnabled())
      await userEvent.click(submit)

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/owner/listings/7/submit')
      expect(toast.success).toHaveBeenCalledWith('Listing submitted for approval')
      expect(await screen.findByRole('heading', { name: 'My listings' })).toBeInTheDocument()
    })

    it('blocks submitting until the owner is verified', async () => {
      mock.onGet('/owner/profile').reply(200, { ...verified, verificationStatus: 'UNSUBMITTED' })
      mock.onGet('/owner/listings/7').reply(200, complete)
      renderApp('/owner/listings/7/edit?step=6')

      expect(await screen.findByText('Verify your identity before submitting')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Verify now' })).toHaveAttribute('href', '/owner/verification')
      expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeDisabled()
    })

    it('shows the server message when the owner is not verified', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, complete)
      mock.onPost('/owner/listings/7/submit').reply(403, { code: 'OWNER_NOT_VERIFIED', detail: 'Verify your identity before submitting listings' })
      renderApp('/owner/listings/7/edit?step=6')

      const submit = await screen.findByRole('button', { name: 'Submit for approval' })
      await waitFor(() => expect(submit).toBeEnabled())
      await userEvent.click(submit)

      expect(await screen.findByRole('alert')).toHaveTextContent('Verify your identity before submitting listings')
    })

    it('waits for approval while pending review', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, { ...complete, status: 'PENDING_REVIEW' })
      renderApp('/owner/listings/7/edit?step=6')

      expect(await screen.findByText('Waiting for approval')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Submit for approval' })).not.toBeInTheDocument()
    })

    it('pauses a live listing', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').replyOnce(200, { ...complete, status: 'APPROVED' }).onGet('/owner/listings/7').reply(200, { ...complete, status: 'PAUSED' })
      mock.onPost('/owner/listings/7/pause').reply(200, { ...complete, status: 'PAUSED' })
      renderApp('/owner/listings/7/edit?step=6')

      expect(await screen.findByText('This listing is live.')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Pause listing' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/owner/listings/7/pause')
      expect(await screen.findByRole('button', { name: 'Resume listing' })).toBeInTheDocument()
    })

    it('resumes a paused listing', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, { ...complete, status: 'PAUSED' })
      mock.onPost('/owner/listings/7/resume').reply(200, { ...complete, status: 'APPROVED' })
      renderApp('/owner/listings/7/edit?step=6')

      await userEvent.click(await screen.findByRole('button', { name: 'Resume listing' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/owner/listings/7/resume')
    })

    it('shows the server message when pausing is not allowed', async () => {
      mock.onGet('/owner/profile').reply(200, verified)
      mock.onGet('/owner/listings/7').reply(200, { ...complete, status: 'APPROVED' })
      mock.onPost('/owner/listings/7/pause').reply(409, { code: 'INVALID_STATUS', detail: 'Only approved listings can be paused' })
      vi.mocked(toast.error).mockClear()
      renderApp('/owner/listings/7/edit?step=6')

      await userEvent.click(await screen.findByRole('button', { name: 'Pause listing' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only approved listings can be paused'))
    })
  })
})
