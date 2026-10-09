import '@testing-library/jest-dom/vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { AdminListingDetail, AdminListingSummary, AdminOwner } from '../../lib/admin'
import { formatDateTime } from '../../lib/format'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

type PickerProps = { value: { lat: number; lng: number } | null; readOnly?: boolean }

vi.mock('../../components/owner/LocationPicker', () => ({
  LocationPicker: ({ value, readOnly }: PickerProps) => (
    <div>
      <p>{readOnly ? 'Map pin locked' : 'Map pin editable'}</p>
      <p>{value ? `Pin at ${value.lat}, ${value.lng}` : 'No pin'}</p>
    </div>
  ),
}))

const admin = {
  id: 1, name: 'Admin User', email: 'admin@parkease.dev', phone: null,
  role: 'ADMIN', emailVerified: true, avatarUrl: null,
}
const driver = { ...admin, id: 9, name: 'Dev Driver', email: 'driver@example.com', role: 'DRIVER' }

const pendingOwner: AdminOwner = {
  userId: 5, name: 'Ravi Kumar', email: 'ravi@example.com', phone: '9876543210', verificationStatus: 'PENDING',
  documentType: 'AADHAAR', hasDocument: true, documentSubmittedAt: '2026-10-05T10:00:00Z', rejectionReason: null, verifiedAt: null,
  hasPayoutDetails: true, listingCount: 2,
}
const verifiedOwner: AdminOwner = {
  ...pendingOwner, userId: 6, name: 'Meera Nair', email: 'meera@example.com', phone: null, verificationStatus: 'VERIFIED',
  documentType: 'PAN', hasPayoutDetails: false, listingCount: 1, verifiedAt: '2026-10-06T10:00:00Z',
}

const page = <T,>(content: T[], totalPages = 1, pageNo = 0) => ({
  content, page: pageNo, size: 20, totalElements: content.length, totalPages,
})

const pendingListing: AdminListingSummary = {
  id: 9, title: 'Viman Nagar Residency Parking', status: 'PENDING_REVIEW', cityName: 'Pune', stateName: 'Maharashtra',
  coverPhotoUrl: '/files/a.jpg', pricePerHour: 40, slotCount: 6, rejectionReason: null, updatedAt: '2026-10-05T10:00:00Z',
  ownerId: 5, ownerName: 'Ravi Kumar', ownerEmail: 'ravi@example.com', submittedAt: '2026-10-05T09:00:00Z',
}

const detail: AdminListingDetail = {
  listing: {
    id: 9, title: 'Viman Nagar Residency Parking', description: 'Gated society lot', address: '14 Viman Nagar Road', pincode: '411014',
    lat: 18.5679, lng: 73.9143, listingType: 'RESIDENTIAL', cityId: 51, cityName: 'Pune', stateName: 'Maharashtra',
    status: 'PENDING_REVIEW', rejectionReason: null, open24x7: false, rules: 'No overnight parking', autoApprove: false,
    pricePerHour: 40, pricePerDay: 300, pricePerMonth: null, cancellationPolicy: 'MODERATE', amenities: ['CCTV', 'WELL_LIT'],
    photos: [{ id: 1, url: '/files/p1.jpg', sortOrder: 0 }, { id: 2, url: '/files/p2.jpg', sortOrder: 1 }],
    slots: [
      { id: 31, label: 'A-01', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM', active: true },
      { id: 32, label: 'B-01', vehicleType: 'TWO_WHEELER', size: 'SMALL', active: true },
    ],
    hours: [{ dayOfWeek: 1, openTime: '08:00:00', closeTime: '22:00:00' }],
    submittedAt: '2026-10-05T09:00:00Z', approvedAt: null, updatedAt: '2026-10-05T10:00:00Z',
  },
  owner: { id: 5, name: 'Ravi Kumar', email: 'ravi@example.com', phone: '9876543210', verificationStatus: 'VERIFIED' },
}

describe('admin review area', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, admin)
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => mock.restore())

  describe('layout and overview', () => {
    it('shows the waiting counts with links to each queue', async () => {
      mock.onGet('/admin/queues').reply(200, { pendingOwners: 2, pendingListings: 1 })
      renderApp('/admin')

      expect(await screen.findByRole('heading', { name: 'Admin' })).toBeInTheDocument()
      expect(await screen.findByText('2 owners waiting for verification')).toBeInTheDocument()
      expect(screen.getByText('1 listing waiting for approval')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Review owners' })).toHaveAttribute('href', '/admin/owners')
      expect(screen.getByRole('link', { name: 'Review listings' })).toHaveAttribute('href', '/admin/listings')
      const nav = within(screen.getByRole('navigation', { name: 'Sections' }))
      expect(nav.getByRole('link', { name: 'Overview' })).toHaveAttribute('href', '/admin')
      expect(nav.getByRole('link', { name: 'Owners' })).toHaveAttribute('href', '/admin/owners')
      expect(nav.getByRole('link', { name: 'Listings' })).toHaveAttribute('href', '/admin/listings')
    })

    it('uses the plural form for any count other than one', async () => {
      mock.onGet('/admin/queues').reply(200, { pendingOwners: 0, pendingListings: 3 })
      renderApp('/admin')

      expect(await screen.findByText('0 owners waiting for verification')).toBeInTheDocument()
      expect(screen.getByText('3 listings waiting for approval')).toBeInTheDocument()
    })

    it('shows the server message when the counts fail to load', async () => {
      mock.onGet('/admin/queues').reply(500, { code: 'INTERNAL', detail: 'Something broke' })
      renderApp('/admin')

      expect(await screen.findByText('Something broke')).toBeInTheDocument()
    })

    it('redirects a driver away from the admin area', async () => {
      mock.onGet('/me').reply(200, driver)
      renderApp('/admin')

      expect(await screen.findByRole('heading', { name: /welcome, dev/i })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Admin' })).not.toBeInTheDocument()
    })

    it('redirects a driver away from the nested admin pages', async () => {
      mock.onGet('/me').reply(200, driver)
      renderApp('/admin/listings/9')

      expect(await screen.findByRole('heading', { name: /welcome, dev/i })).toBeInTheDocument()
    })
  })

  describe('owner verification queue', () => {
    it('lists pending owners by default', async () => {
      mock.onGet('/admin/owners').reply(200, page([pendingOwner, verifiedOwner]))
      renderApp('/admin/owners')

      expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument()
      expect(mock.history.get.find((r) => r.url === '/admin/owners')?.params).toEqual({ status: 'PENDING', page: 0, size: 20 })
      const row = within(screen.getByRole('article', { name: 'Ravi Kumar' }))
      expect(row.getByText('ravi@example.com')).toBeInTheDocument()
      expect(row.getByText('9876543210')).toBeInTheDocument()
      expect(row.getByText('Aadhaar card')).toBeInTheDocument()
      expect(row.getByText(formatDateTime(pendingOwner.documentSubmittedAt!))).toBeInTheDocument()
      expect(row.getByText('Payout details added')).toBeInTheDocument()
      expect(row.getByText('2 listings')).toBeInTheDocument()
      expect(row.getByRole('button', { name: 'View document' })).toBeInTheDocument()
      expect(row.getByRole('button', { name: 'Verify' })).toBeInTheDocument()
      expect(row.getByRole('button', { name: 'Reject' })).toBeInTheDocument()

      const verified = within(screen.getByRole('article', { name: 'Meera Nair' }))
      expect(verified.getByText('No payout details')).toBeInTheDocument()
      expect(verified.getByText('1 listing')).toBeInTheDocument()
      expect(verified.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
    })

    it('reloads when the status filter changes', async () => {
      mock.onGet('/admin/owners', { params: { status: 'PENDING', page: 0, size: 20 } }).reply(200, page([pendingOwner]))
      mock.onGet('/admin/owners', { params: { status: 'VERIFIED', page: 0, size: 20 } }).reply(200, page([verifiedOwner]))
      renderApp('/admin/owners')

      await screen.findByText('Ravi Kumar')
      await userEvent.selectOptions(screen.getByLabelText('Show'), 'Verified')

      expect(await screen.findByText('Meera Nair')).toBeInTheDocument()
      expect(screen.queryByText('Ravi Kumar')).not.toBeInTheDocument()
    })

    it('shows an empty state', async () => {
      mock.onGet('/admin/owners').reply(200, page([]))
      renderApp('/admin/owners')

      expect(await screen.findByText('No owners in this list.')).toBeInTheDocument()
    })

    it('verifies an owner', async () => {
      mock.onGet('/admin/owners').replyOnce(200, page([pendingOwner])).onGet('/admin/owners').reply(200, page([]))
      mock.onPost('/admin/owners/5/verify').reply(200, { ...pendingOwner, verificationStatus: 'VERIFIED' })
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/admin/owners/5/verify')
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Ravi Kumar verified'))
      expect(await screen.findByText('No owners in this list.')).toBeInTheDocument()
    })

    it('shows the server message when verifying fails', async () => {
      mock.onGet('/admin/owners').reply(200, page([pendingOwner]))
      mock.onPost('/admin/owners/5/verify').reply(409, { code: 'INVALID_STATUS', detail: 'Owner is not pending' })
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Owner is not pending'))
    })

    it('rejects an owner with a reason', async () => {
      mock.onGet('/admin/owners').replyOnce(200, page([pendingOwner])).onGet('/admin/owners').reply(200, page([]))
      mock.onPost('/admin/owners/5/reject').reply(200, { ...pendingOwner, verificationStatus: 'REJECTED' })
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
      const dialog = screen.getByRole('dialog', { name: 'Reject Ravi Kumar?' })
      await userEvent.type(within(dialog).getByLabelText('Reason'), 'Blurry')
      await userEvent.click(within(dialog).getByRole('button', { name: 'Reject' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/admin/owners/5/reject')
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Blurry' })
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Ravi Kumar rejected'))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('requires a reason before rejecting', async () => {
      mock.onGet('/admin/owners').reply(200, page([pendingOwner]))
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reject' }))

      expect(await screen.findByText('Please give a reason')).toBeInTheDocument()
      expect(mock.history.post).toHaveLength(0)
    })

    it('opens the signed document link in a new tab', async () => {
      mock.onGet('/admin/owners').reply(200, page([pendingOwner]))
      mock.onGet('/admin/owners/5/document-url').reply(200, { url: '/files/private/doc?sig=abc', expiresAt: '2026-10-05T10:05:00Z' })
      const tab = { opener: 'self' as unknown, location: { href: '' }, close: vi.fn() }
      const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'View document' }))

      await waitFor(() => expect(tab.location.href).toBe('/files/private/doc?sig=abc'))
      expect(open).toHaveBeenCalledWith('about:blank', '_blank')
      expect(tab.opener).toBeNull()
      open.mockRestore()
    })

    it('shows the server message when the document link cannot be created', async () => {
      mock.onGet('/admin/owners').reply(200, page([pendingOwner]))
      mock.onGet('/admin/owners/5/document-url').reply(404, { code: 'NOT_FOUND', detail: 'No document submitted' })
      const tab = { opener: 'self' as unknown, location: { href: '' }, close: vi.fn() }
      const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
      renderApp('/admin/owners')

      await userEvent.click(await screen.findByRole('button', { name: 'View document' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('No document submitted'))
      expect(tab.close).toHaveBeenCalled()
      expect(tab.location.href).toBe('')
      open.mockRestore()
    })

    it('hides "View document" for an owner without a document', async () => {
      mock.onGet('/admin/owners').reply(200, page([{ ...pendingOwner, documentType: null, hasDocument: false }]))
      renderApp('/admin/owners')

      await screen.findByText('Ravi Kumar')
      expect(screen.queryByRole('button', { name: 'View document' })).not.toBeInTheDocument()
    })

    it('steps back a page when the current page comes back empty', async () => {
      mock.onGet('/admin/owners', { params: { status: 'PENDING', page: 0, size: 20 } }).reply(200, page([pendingOwner], 2, 0))
      mock.onGet('/admin/owners', { params: { status: 'PENDING', page: 1, size: 20 } }).reply(200, page([], 1, 1))
      renderApp('/admin/owners')

      await screen.findByText('Ravi Kumar')
      await userEvent.click(screen.getByRole('button', { name: 'Next' }))

      expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument()
      expect(screen.queryByText('No owners in this list.')).not.toBeInTheDocument()
    })

    it('pages through the owners', async () => {
      mock.onGet('/admin/owners', { params: { status: 'PENDING', page: 0, size: 20 } }).reply(200, page([pendingOwner], 2, 0))
      mock.onGet('/admin/owners', { params: { status: 'PENDING', page: 1, size: 20 } }).reply(200, page([{ ...pendingOwner, userId: 7, name: 'Sita Rao' }], 2, 1))
      renderApp('/admin/owners')

      await screen.findByText('Ravi Kumar')
      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
      await userEvent.click(screen.getByRole('button', { name: 'Next' }))

      expect(await screen.findByText('Sita Rao')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    })
  })

  describe('listing approval queue', () => {
    it('steps back a page when the current page comes back empty', async () => {
      mock.onGet('/admin/listings', { params: { status: 'PENDING_REVIEW', page: 0, size: 20 } }).reply(200, page([pendingListing], 2, 0))
      mock.onGet('/admin/listings', { params: { status: 'PENDING_REVIEW', page: 1, size: 20 } }).reply(200, page([], 1, 1))
      renderApp('/admin/listings')

      await screen.findByText('Viman Nagar Residency Parking')
      await userEvent.click(screen.getByRole('button', { name: 'Next' }))

      expect(await screen.findByText('Viman Nagar Residency Parking')).toBeInTheDocument()
      expect(screen.queryByText('No listings in this list.')).not.toBeInTheDocument()
    })

    it('lists pending listings with a review link', async () => {
      mock.onGet('/admin/listings').reply(200, page([pendingListing]))
      renderApp('/admin/listings')

      const card = within(await screen.findByRole('article', { name: 'Viman Nagar Residency Parking' }))
      expect(mock.history.get.find((r) => r.url === '/admin/listings')?.params).toEqual({ status: 'PENDING_REVIEW', page: 0, size: 20 })
      expect(card.getByText('Pune, Maharashtra')).toBeInTheDocument()
      expect(card.getByText('Ravi Kumar')).toBeInTheDocument()
      expect(card.getByText('ravi@example.com')).toBeInTheDocument()
      expect(card.getByText(formatDateTime(pendingListing.submittedAt!))).toBeInTheDocument()
      expect(card.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/admin/listings/9')
    })

    it('switches the status filter', async () => {
      mock.onGet('/admin/listings', { params: { status: 'PENDING_REVIEW', page: 0, size: 20 } }).reply(200, page([pendingListing]))
      mock.onGet('/admin/listings', { params: { status: 'APPROVED', page: 0, size: 20 } }).reply(200, page([]))
      renderApp('/admin/listings')

      await screen.findByRole('article', { name: 'Viman Nagar Residency Parking' })
      await userEvent.selectOptions(screen.getByLabelText('Show'), 'Live')

      expect(await screen.findByText('No listings in this list.')).toBeInTheDocument()
    })
  })

  describe('listing review', () => {
    beforeEach(() => {
      mock.onGet('/admin/listings/9').reply(200, detail)
      mock.onGet('/admin/listings').reply(200, page([]))
    })

    it('shows the full read-only detail', async () => {
      renderApp('/admin/listings/9')

      expect(await screen.findByRole('heading', { name: 'Viman Nagar Residency Parking' })).toBeInTheDocument()
      expect(screen.getByText('Pending review')).toBeInTheDocument()
      expect(screen.getByText('Map pin locked')).toBeInTheDocument()
      expect(screen.getByText('Pin at 18.5679, 73.9143')).toBeInTheDocument()
      expect(screen.getByText(/14 Viman Nagar Road, Pune, Maharashtra 411014/)).toBeInTheDocument()
      expect(screen.getByText('Residential')).toBeInTheDocument()
      expect(screen.getAllByRole('img', { name: /Parking photo/ })).toHaveLength(2)
      expect(screen.getByText('2 slots · 1 car · 1 two-wheeler')).toBeInTheDocument()
      expect(screen.getByText('₹40/hr')).toBeInTheDocument()
      expect(screen.getByText('Monday: 08:00 – 22:00')).toBeInTheDocument()
      expect(screen.getByText('CCTV')).toBeInTheDocument()
      expect(screen.getByText('Well lit')).toBeInTheDocument()
      expect(screen.getByText('Rules: No overnight parking')).toBeInTheDocument()
      const owner = within(screen.getByRole('region', { name: 'Owner' }))
      expect(owner.getByText('Ravi Kumar')).toBeInTheDocument()
      expect(owner.getByText('ravi@example.com')).toBeInTheDocument()
      expect(owner.getByText('Verified')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '← Listing approvals' })).toHaveAttribute('href', '/admin/listings')
    })

    it('approves a pending listing and returns to the queue', async () => {
      mock.onPost('/admin/listings/9/approve').reply(200, detail)
      renderApp('/admin/listings/9')

      await userEvent.click(await screen.findByRole('button', { name: 'Approve' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/admin/listings/9/approve')
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Listing approved'))
      expect(await screen.findByText('No listings in this list.')).toBeInTheDocument()
    })

    it('shows the server message when approving fails', async () => {
      mock.onPost('/admin/listings/9/approve').reply(409, { code: 'OWNER_NOT_VERIFIED', detail: 'Owner is not verified' })
      renderApp('/admin/listings/9')

      await userEvent.click(await screen.findByRole('button', { name: 'Approve' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Owner is not verified'))
      expect(screen.getByRole('heading', { name: 'Viman Nagar Residency Parking' })).toBeInTheDocument()
    })

    it('rejects a pending listing with a reason', async () => {
      mock.onPost('/admin/listings/9/reject').reply(200, detail)
      renderApp('/admin/listings/9')

      await userEvent.click(await screen.findByRole('button', { name: 'Reject' }))
      const dialog = screen.getByRole('dialog', { name: 'Reject this listing?' })
      await userEvent.type(within(dialog).getByLabelText('Reason'), 'Photos do not show the entrance')
      await userEvent.click(within(dialog).getByRole('button', { name: 'Reject' }))

      await waitFor(() => expect(mock.history.post).toHaveLength(1))
      expect(mock.history.post[0].url).toBe('/admin/listings/9/reject')
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: 'Photos do not show the entrance' })
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Listing rejected'))
      expect(await screen.findByText('No listings in this list.')).toBeInTheDocument()
    })

    it('offers no decision buttons once the listing is no longer pending', async () => {
      mock.onGet('/admin/listings/9').reply(200, { ...detail, listing: { ...detail.listing, status: 'APPROVED' } })
      renderApp('/admin/listings/9')

      await screen.findByRole('heading', { name: 'Viman Nagar Residency Parking' })
      expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
    })

    it('shows the server message for a listing that does not exist', async () => {
      mock.onGet('/admin/listings/99').reply(404, { code: 'NOT_FOUND', detail: 'Listing not found' })
      renderApp('/admin/listings/99')

      expect(await screen.findByText('Listing not found')).toBeInTheDocument()
    })
  })
})
