import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { OwnerProfile } from '../../lib/owner'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const owner = {
  id: 7, name: 'Ravi Kumar', email: 'ravi@example.com', phone: null,
  role: 'OWNER', emailVerified: true, avatarUrl: null,
}

const unsubmitted: OwnerProfile = {
  verificationStatus: 'UNSUBMITTED', documentType: null, documentSubmittedAt: null, rejectionReason: null,
  verifiedAt: null, payoutUpi: null, payoutAccountName: null, payoutIfsc: null, payoutBankAccountLast4: null,
}

describe('owner verification', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  })

  afterEach(() => mock.restore())

  it('shows the unsubmitted state on the owner home', async () => {
    mock.onGet('/owner/profile').reply(200, unsubmitted)
    renderApp('/owner')

    expect(await screen.findByText('Not submitted')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Start verification' })).toHaveAttribute('href', '/owner/verification')
    expect(screen.getByRole('link', { name: 'Add a listing' })).toHaveAttribute('href', '/owner/listings/new')
  })

  it('uploads the document with its type and shows the pending state', async () => {
    mock.onGet('/owner/profile').reply(200, unsubmitted)
    mock.onPost('/owner/verification').reply(200, {
      ...unsubmitted, verificationStatus: 'PENDING', documentType: 'PAN', documentSubmittedAt: '2026-10-05T10:00:00Z',
    })
    renderApp('/owner/verification')

    await userEvent.selectOptions(await screen.findByLabelText('Document type'), 'PAN card')
    const file = new File(['%PDF-1.4'], 'pan.pdf', { type: 'application/pdf' })
    await userEvent.upload(screen.getByLabelText('Document file'), file)
    await userEvent.click(screen.getByRole('button', { name: 'Submit for verification' }))

    expect(await screen.findByText('Under review')).toBeInTheDocument()
    const post = mock.history.post.find((r) => r.url === '/owner/verification')!
    expect(post.data).toBeInstanceOf(FormData)
    expect((post.data as FormData).get('documentType')).toBe('PAN')
    expect((post.data as FormData).get('file')).toBeInstanceOf(File)
  })

  it('requires a document before submitting', async () => {
    mock.onGet('/owner/profile').reply(200, unsubmitted)
    renderApp('/owner/verification')

    await userEvent.click(await screen.findByRole('button', { name: 'Submit for verification' }))

    expect(await screen.findByText('Choose a file to upload')).toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
  })

  it('validates payout details before saving them', async () => {
    mock.onGet('/owner/profile').reply(200, unsubmitted)
    mock.onPut('/owner/profile/payout').reply(200, { ...unsubmitted, payoutUpi: 'ravi@okaxis', payoutAccountName: 'Ravi Kumar' })
    renderApp('/owner/verification')

    await userEvent.type(await screen.findByLabelText('Account holder name'), 'Ravi Kumar')
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))

    expect(await screen.findByText('Add a UPI ID, or a bank account with IFSC.')).toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)

    await userEvent.type(screen.getByLabelText('UPI ID'), 'ravi@okaxis')
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))

    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(JSON.parse(mock.history.put[0].data)).toMatchObject({ upiId: 'ravi@okaxis', accountName: 'Ravi Kumar' })
  })

  it('uppercases the IFSC and sends bank details', async () => {
    mock.onGet('/owner/profile').reply(200, unsubmitted)
    mock.onPut('/owner/profile/payout').reply(200, unsubmitted)
    renderApp('/owner/verification')

    await userEvent.type(await screen.findByLabelText('Bank account number'), '123456789012')
    await userEvent.type(screen.getByLabelText('IFSC code'), 'hdfc0001234')
    await userEvent.type(screen.getByLabelText('Account holder name'), 'Ravi Kumar')
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))

    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(JSON.parse(mock.history.put[0].data)).toMatchObject({ bankAccount: '123456789012', ifsc: 'HDFC0001234' })
  })

  it('shows the rejection reason and prefills saved payout details', async () => {
    mock.onGet('/owner/profile').reply(200, {
      ...unsubmitted, verificationStatus: 'REJECTED', rejectionReason: 'Blurry photo', documentType: 'PAN',
      documentSubmittedAt: '2026-10-05T10:00:00Z', payoutUpi: 'ravi@okaxis', payoutAccountName: 'Ravi Kumar',
      payoutIfsc: 'HDFC0001234', payoutBankAccountLast4: '9012',
    })
    renderApp('/owner/verification')

    expect(await screen.findByText('Blurry photo')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View document' })).toBeInTheDocument()
    expect(await screen.findByDisplayValue('ravi@okaxis')).toBeInTheDocument()
    expect(screen.getByLabelText('Bank account number')).toHaveAttribute('placeholder', '•••• 9012')
  })

  it('opens the signed document URL in a new tab', async () => {
    mock.onGet('/owner/profile').reply(200, {
      ...unsubmitted, verificationStatus: 'VERIFIED', documentType: 'PAN', documentSubmittedAt: '2026-10-05T10:00:00Z',
    })
    mock.onGet('/owner/verification/document-url').reply(200, { url: 'https://files.test/doc?sig=1', expiresAt: '2026-10-05T10:05:00Z' })
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    renderApp('/owner/verification')

    expect(await screen.findByText('Your identity is verified.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'View document' }))

    await waitFor(() => expect(open).toHaveBeenCalledWith('https://files.test/doc?sig=1', '_blank', 'noopener'))
    open.mockRestore()
  })

  const withBank: OwnerProfile = {
    ...unsubmitted, payoutUpi: 'ravi@okaxis', payoutAccountName: 'Ravi Kumar', payoutIfsc: 'HDFC0001234',
    payoutBankAccountLast4: '9012',
  }

  it('omits bankAccount when a saved account is left untouched', async () => {
    mock.onGet('/owner/profile').reply(200, withBank)
    mock.onPut('/owner/profile/payout').reply(200, withBank)
    renderApp('/owner/verification')

    const upi = await screen.findByLabelText('UPI ID')
    expect(screen.getByText('Leave blank to keep •••• 9012')).toBeInTheDocument()
    await userEvent.clear(upi)
    await userEvent.type(upi, 'ravi.new@okaxis')
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))

    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    const body = JSON.parse(mock.history.put[0].data)
    expect(body.upiId).toBe('ravi.new@okaxis')
    expect(body).not.toHaveProperty('bankAccount')
  })

  it('sends an empty bankAccount when removing the saved account', async () => {
    mock.onGet('/owner/profile').reply(200, withBank)
    mock.onPut('/owner/profile/payout').reply(200, { ...withBank, payoutBankAccountLast4: null })
    renderApp('/owner/verification')

    await userEvent.click(await screen.findByLabelText('Remove saved bank account'))
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))

    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(JSON.parse(mock.history.put[0].data)).toMatchObject({ bankAccount: '' })
  })

  it('accepts a saved account plus IFSC without a UPI ID, but not once it is removed', async () => {
    const bankOnly = { ...withBank, payoutUpi: null }
    mock.onGet('/owner/profile').reply(200, bankOnly)
    mock.onPut('/owner/profile/payout').reply(200, bankOnly)
    renderApp('/owner/verification')

    await userEvent.click(await screen.findByLabelText('Remove saved bank account'))
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))
    expect(await screen.findByText('Add a UPI ID, or a bank account with IFSC.')).toBeInTheDocument()
    expect(mock.history.put).toHaveLength(0)

    await userEvent.click(screen.getByLabelText('Remove saved bank account'))
    await userEvent.click(screen.getByRole('button', { name: 'Save payout details' }))
    await waitFor(() => expect(mock.history.put).toHaveLength(1))
    expect(JSON.parse(mock.history.put[0].data)).not.toHaveProperty('bankAccount')
  })
})
