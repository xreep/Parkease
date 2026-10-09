import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingSummaryDto } from '../../lib/bookings'
import { tokenStore } from '../../lib/tokenStore'
import type { VehicleDto } from '../../lib/vehicles'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }
const owner = { ...driver, id: 2, name: 'Ravi Kumar', role: 'OWNER' }

const car: VehicleDto = { id: 11, type: 'FOUR_WHEELER', plateNumber: 'MH12AB1234', makeModel: 'Honda City', isDefault: true }
const bike: VehicleDto = { id: 12, type: 'TWO_WHEELER', plateNumber: 'MH12XY9876', makeModel: null, isDefault: false }

const booking: BookingSummaryDto = {
  id: 91, bookingCode: 'PE-8KQ2M4', status: 'CONFIRMED', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
  coverPhotoUrl: null, startTime: '2026-10-12T04:30:00Z', endTime: '2026-10-12T06:30:00Z', vehicleType: 'FOUR_WHEELER',
  plateNumber: 'MH12AB1234', totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z',
}

const page = <T,>(content: T[]) => ({ content, page: 0, size: 1, totalElements: content.length, totalPages: 1 })

describe('driver area', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  describe('vehicles', () => {
    it('lists vehicles with type, make and the default badge', async () => {
      mock.onGet('/me/vehicles').reply(200, [car, bike])
      renderApp('/driver/vehicles')

      expect(await screen.findByRole('heading', { name: 'My parking' })).toBeInTheDocument()
      const carCard = within(await screen.findByRole('article', { name: 'MH12AB1234' }))
      expect(carCard.getByText('MH12AB1234')).toHaveClass('font-mono')
      expect(carCard.getByText('Car · Honda City')).toBeInTheDocument()
      expect(carCard.getByText('Default')).toBeInTheDocument()
      expect(carCard.queryByRole('button', { name: 'Make default' })).not.toBeInTheDocument()
      const bikeCard = within(screen.getByRole('article', { name: 'MH12XY9876' }))
      expect(bikeCard.getByText('Two-wheeler')).toBeInTheDocument()
      expect(bikeCard.queryByText('Default')).not.toBeInTheDocument()
      expect(bikeCard.getByRole('button', { name: 'Make default' })).toBeInTheDocument()
    })

    it('shows the empty state', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp('/driver/vehicles')

      expect(await screen.findByText('Add your vehicle to start booking.')).toBeInTheDocument()
    })

    it('normalises the plate before adding a vehicle', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      mock.onPost('/me/vehicles').reply(201, car)
      renderApp('/driver/vehicles')
      await screen.findByText('Add your vehicle to start booking.')

      expect(screen.getByLabelText('Number plate')).toHaveAttribute('placeholder', 'MH 12 AB 1234')
      await userEvent.selectOptions(screen.getByLabelText('Vehicle type'), 'FOUR_WHEELER')
      await userEvent.type(screen.getByLabelText('Number plate'), 'mh 12 ab 1234')
      await userEvent.type(screen.getByLabelText('Make and model (optional)'), 'Honda City')
      await userEvent.click(screen.getByLabelText('Use as default'))
      await userEvent.click(screen.getByRole('button', { name: 'Add vehicle' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(JSON.parse(mock.history.post[0].data)).toEqual({
        type: 'FOUR_WHEELER', plateNumber: 'MH12AB1234', makeModel: 'Honda City', isDefault: true,
      })
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Vehicle added'))
      expect(screen.getByLabelText('Number plate')).toHaveValue('')
    })

    it.each(['MH12', 'HELLO', '1234', 'MH 12 AB 12345'])('rejects the plate %s without calling the server', async (plate) => {
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp('/driver/vehicles')
      await screen.findByText('Add your vehicle to start booking.')

      await userEvent.type(screen.getByLabelText('Number plate'), plate)
      await userEvent.click(screen.getByRole('button', { name: 'Add vehicle' }))

      expect(await screen.findByText('Enter a valid Indian number plate')).toBeInTheDocument()
      expect(mock.history.post).toHaveLength(0)
    })

    it('accepts Bharat series plates', async () => {
      mock.onGet('/me/vehicles').reply(200, [])
      mock.onPost('/me/vehicles').reply(201, car)
      renderApp('/driver/vehicles')
      await screen.findByText('Add your vehicle to start booking.')

      await userEvent.type(screen.getByLabelText('Number plate'), '22 bh 1234 aa')
      await userEvent.click(screen.getByRole('button', { name: 'Add vehicle' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(JSON.parse(mock.history.post[0].data).plateNumber).toBe('22BH1234AA')
    })

    it('shows PLATE_TAKEN as a field error', async () => {
      mock.onGet('/me/vehicles').reply(200, [car])
      mock.onPost('/me/vehicles').reply(409, { code: 'PLATE_TAKEN', detail: 'You have already saved a vehicle with this number plate' })
      renderApp('/driver/vehicles')
      await screen.findByRole('article', { name: 'MH12AB1234' })

      await userEvent.type(screen.getByLabelText('Number plate'), 'MH12AB1234')
      await userEvent.click(screen.getByRole('button', { name: 'Add vehicle' }))

      expect(await screen.findByText("You've already added this vehicle")).toBeInTheDocument()
      expect(screen.getByLabelText('Number plate')).toHaveAttribute('aria-invalid', 'true')
    })

    it('shows other server errors (e.g. VEHICLE_LIMIT) above the form', async () => {
      mock.onGet('/me/vehicles').reply(200, [car])
      mock.onPost('/me/vehicles').reply(409, { code: 'VEHICLE_LIMIT', detail: 'You can save at most 10 vehicles' })
      renderApp('/driver/vehicles')
      await screen.findByRole('article', { name: 'MH12AB1234' })

      await userEvent.type(screen.getByLabelText('Number plate'), 'MH12XY9876')
      await userEvent.click(screen.getByRole('button', { name: 'Add vehicle' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('You can save at most 10 vehicles')
    })

    it('asks for confirmation before deleting a vehicle', async () => {
      mock.onGet('/me/vehicles').reply(200, [car, bike])
      mock.onDelete('/me/vehicles/12').reply(204)
      renderApp('/driver/vehicles')
      const card = within(await screen.findByRole('article', { name: 'MH12XY9876' }))

      await userEvent.click(card.getByRole('button', { name: 'Delete' }))

      const dialog = within(screen.getByRole('dialog', { name: 'Delete MH12XY9876?' }))
      expect(mock.history.delete).toHaveLength(0)
      await userEvent.click(dialog.getByRole('button', { name: 'Cancel' }))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(mock.history.delete).toHaveLength(0)

      await userEvent.click(card.getByRole('button', { name: 'Delete' }))
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete vehicle' }))

      await waitFor(() => expect(mock.history.delete).toHaveLength(1))
      expect(mock.history.delete[0].url).toBe('/me/vehicles/12')
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('makes a vehicle the default', async () => {
      mock.onGet('/me/vehicles').reply(200, [car, bike])
      mock.onPut('/me/vehicles/12').reply(200, { ...bike, isDefault: true })
      renderApp('/driver/vehicles')
      const card = within(await screen.findByRole('article', { name: 'MH12XY9876' }))

      await userEvent.click(card.getByRole('button', { name: 'Make default' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ type: 'TWO_WHEELER', plateNumber: 'MH12XY9876', isDefault: true })
    })

    it('edits a vehicle', async () => {
      mock.onGet('/me/vehicles').reply(200, [car])
      mock.onPut('/me/vehicles/11').reply(200, { ...car, makeModel: 'Honda Amaze' })
      renderApp('/driver/vehicles')
      const card = within(await screen.findByRole('article', { name: 'MH12AB1234' }))

      await userEvent.click(card.getByRole('button', { name: 'Edit' }))
      const dialog = within(screen.getByRole('dialog', { name: 'Edit MH12AB1234' }))
      const field = dialog.getByLabelText('Make and model (optional)')
      expect(field).toHaveValue('Honda City')
      await userEvent.clear(field)
      await userEvent.type(field, 'Honda Amaze')
      await userEvent.click(dialog.getByRole('button', { name: 'Save changes' }))

      await waitFor(() => expect(mock.history.put).toHaveLength(1))
      expect(JSON.parse(mock.history.put[0].data)).toEqual({ type: 'FOUR_WHEELER', plateNumber: 'MH12AB1234', makeModel: 'Honda Amaze' })
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('sends an owner away from the driver area', async () => {
      mock.onGet('/me').reply(200, owner)
      mock.onGet('/owner/profile').reply(200, { verificationStatus: 'UNSUBMITTED' })
      mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      renderApp('/driver/vehicles')

      expect(await screen.findByRole('heading', { name: 'Owner dashboard' })).toBeInTheDocument()
      expect(mock.history.get.some((r) => r.url === '/me/vehicles')).toBe(false)
    })
  })

  describe('home', () => {
    it('shows the next upcoming booking and the vehicle count', async () => {
      mock.onGet('/bookings').reply(200, page([booking]))
      mock.onGet('/me/vehicles').reply(200, [car, bike])
      renderApp('/driver')

      expect(await screen.findByText('Welcome, Rahul')).toBeInTheDocument()
      expect(await screen.findByText('PE-8KQ2M4')).toBeInTheDocument()
      expect(screen.getByText('Metro Hub Parking')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'View booking' })).toHaveAttribute('href', '/driver/bookings/91')
      expect(mock.history.get.find((r) => r.url === '/bookings')?.params).toEqual({ view: 'active', page: 0, size: 1 })
      expect(await screen.findByText('2')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Manage vehicles' })).toHaveAttribute('href', '/driver/vehicles')
    })

    it('offers to find parking when there is no upcoming booking', async () => {
      mock.onGet('/bookings').reply(200, page([]))
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp('/driver')

      expect(await screen.findByText('No upcoming bookings.')).toBeInTheDocument()
      expect(within(screen.getByRole('main')).getByRole('link', { name: 'Find parking' })).toHaveAttribute('href', '/search')
    })

    it('has Overview, Bookings and Vehicles tabs', async () => {
      mock.onGet('/bookings').reply(200, page([]))
      mock.onGet('/me/vehicles').reply(200, [])
      renderApp('/driver')

      const nav = within(await screen.findByRole('navigation', { name: 'Sections' }))
      expect(nav.getByRole('link', { name: 'Overview' })).toHaveAttribute('href', '/driver')
      expect(nav.getByRole('link', { name: 'Bookings' })).toHaveAttribute('href', '/driver/bookings')
      expect(nav.getByRole('link', { name: 'Vehicles' })).toHaveAttribute('href', '/driver/vehicles')
    })
  })
})
