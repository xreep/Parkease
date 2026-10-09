import '@testing-library/jest-dom/vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import type { BookingDetailDto, CheckoutDto } from '../../lib/bookings'
import { RazorpayDismissedError, RazorpayFailedError, loadRazorpay, openRazorpay } from '../../lib/razorpay'
import type { PublicListingDto } from '../../lib/search'
import { tokenStore } from '../../lib/tokenStore'
import { REFUND_NOTE } from '../../lib/format'
import { renderApp } from '../../test/renderApp'

vi.mock('../../lib/razorpay', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/razorpay')>()),
  loadRazorpay: vi.fn(),
  openRazorpay: vi.fn(),
}))

const driver = { id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null, role: 'DRIVER', emailVerified: true, avatarUrl: null }
const owner = { ...driver, id: 2, name: 'Ravi Kumar', role: 'OWNER' }

const START = '2026-10-12T04:30:00Z'
const END = '2026-10-12T06:30:00Z'

function booking(holdMs = 9 * 60_000 + 30_000): BookingDetailDto {
  return {
    id: 91, bookingCode: 'PE-8KQ2M4', status: 'PENDING_PAYMENT', listingId: 7, listingTitle: 'Metro Hub Parking', cityName: 'Pune',
    coverPhotoUrl: '/files/a.jpg', startTime: START, endTime: END, vehicleType: 'FOUR_WHEELER', plateNumber: 'MH12AB1234',
    totalAmount: 89.44, createdAt: '2026-10-09T08:00:00Z', address: 'FC Road, Shivajinagar', lat: 18.5, lng: 73.8, slotLabel: 'A-3',
    pricingMode: 'HOURLY', pricingBreakdown: '2 hours', baseAmount: 80, platformFee: 8, gstAmount: 1.44, refundAmount: 0,
    holdExpiresAt: new Date(Date.now() + holdMs).toISOString(), approvalDeadline: null, confirmedAt: null, cancelReason: null,
    cancelledBy: null, paymentStatus: 'CREATED', invoiceNumber: null, autoApprove: true, ownerFirstName: 'Priya', reviewable: false, review: null, events: [],
  }
}

function checkout(provider: 'MOCK' | 'RAZORPAY', holdMs?: number): CheckoutDto {
  return {
    booking: booking(holdMs),
    payment: {
      provider, orderId: 'order_1', amount: 8944, currency: 'INR', keyId: provider === 'RAZORPAY' ? 'rzp_test_abc' : null,
      name: 'ParkEase', description: 'Parking PE-8KQ2M4',
      prefill: { name: 'Rahul Verma', email: 'driver@example.com', contact: null },
    },
  }
}

const listing = { id: 7, cancellationPolicy: 'MODERATE' } as PublicListingDto
const signed = { orderId: 'order_1', paymentId: 'pay_1', signature: 'sig_1' }
const confirmed = { ...booking(), status: 'CONFIRMED' as const }

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

describe('checkout', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    tokenStore.set('a', 'r')
    mock = new MockAdapter(api)
    mock.onGet('/me').reply(200, driver)
    mock.onGet('/listings/7').reply(200, listing)
    vi.mocked(loadRazorpay).mockReset().mockResolvedValue(true)
    vi.mocked(openRazorpay).mockReset()
  })

  afterEach(() => mock.restore())

  const verifyCalls = () => mock.history.post.filter((r) => r.url === '/payments/verify')

  it('shows the cancellation policy with the refund note', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    renderApp('/checkout/91')
    expect(await screen.findByText(/Full refund up to 24 hours before start/)).toBeInTheDocument()
    expect(screen.getByText(REFUND_NOTE)).toBeInTheDocument()
  })

  it('summarises the reservation with a countdown and the total to pay', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    renderApp('/checkout/91')

    expect(await screen.findByRole('heading', { name: 'Metro Hub Parking' })).toBeInTheDocument()
    expect(screen.getByText('FC Road, Shivajinagar')).toBeInTheDocument()
    expect(screen.getByText('2 hours')).toBeInTheDocument()
    expect(screen.getByText('MH12AB1234')).toBeInTheDocument()
    expect(screen.getByText(/A-3/)).toBeInTheDocument()
    expect(screen.getByText('Parking (2 hours)').nextElementSibling).toHaveTextContent('₹80')
    expect(screen.getByText('Platform fee').nextElementSibling).toHaveTextContent('₹8')
    expect(screen.getByText('GST on fee').nextElementSibling).toHaveTextContent('₹1.44')
    expect(screen.getByText('Total').nextElementSibling).toHaveTextContent('₹89.44')
    expect(screen.getByText(/^Slot held for 09:(29|30)$/)).not.toHaveClass('text-red-600')
    expect(await screen.findByText(/full refund up to 24 hours before start/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
  })

  it('turns the countdown red in the last two minutes', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK', 90_000))
    renderApp('/checkout/91')

    expect(await screen.findByText(/^Slot held for 01:(29|30)$/)).toHaveClass('text-red-600')
  })

  it('announces two minutes left, then shows the expired state and focuses its heading at zero', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    try {
      mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK', 125_000))
      renderApp('/checkout/91')

      expect(await screen.findByText(/^Slot held for 02:0[45]$/)).toBeInTheDocument()
      expect(screen.queryByText('Two minutes left to pay.')).not.toBeInTheDocument()

      await act(async () => {
        vi.advanceTimersByTime(6_000)
      })
      expect(screen.getByText('Two minutes left to pay.')).toBeInTheDocument()
      expect(screen.getByRole('status', { name: '' })).toBeInTheDocument()

      await act(async () => {
        vi.advanceTimersByTime(125_000)
      })
      const heading = screen.getByRole('heading', { name: 'Your reservation expired' })
      expect(heading).toHaveFocus()
      expect(screen.queryByRole('button', { name: /^Pay/ })).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Search again' }).getAttribute('href')).toMatch(/^\/listings\/7\?start=/)
    } finally {
      vi.useRealTimers()
    }
  })

  it('pays with the test payment dialog: mock/pay, then verify with the returned signature', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    mock.onPost('/payments/mock/pay').reply(200, signed)
    mock.onPost('/payments/verify').reply(200, confirmed)
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    const dialog = within(screen.getByRole('dialog', { name: 'Test payment' }))
    expect(dialog.getByText("Razorpay keys aren't configured, so this simulates a payment.")).toBeInTheDocument()
    expect(dialog.getByText('₹89.44')).toBeInTheDocument()
    expect(openRazorpay).not.toHaveBeenCalled()
    await userEvent.click(dialog.getByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings/91?new=1'))
    expect(JSON.parse(mock.history.post.find((r) => r.url === '/payments/mock/pay')!.data)).toEqual({ bookingId: 91 })
    expect(JSON.parse(verifyCalls()[0].data)).toEqual({ bookingId: 91, orderId: 'order_1', paymentId: 'pay_1', signature: 'sig_1' })
  })

  it('simulates a failed test payment without calling the server', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Simulate failure' }))

    expect(await screen.findByText('Payment failed: Simulated failure')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mock.history.post).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
  })

  it('shows a verification error from the server inside the test dialog', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    mock.onPost('/payments/mock/pay').reply(200, signed)
    mock.onPost('/payments/verify').reply(400, { code: 'PAYMENT_VERIFICATION_FAILED', detail: 'Payment verification failed' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Payment verification failed')
  })

  it('pays with Razorpay Checkout, then verifies the signed response', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').reply(200, confirmed)
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig_1' })
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings/91?new=1'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(openRazorpay).toHaveBeenCalledWith({
      key: 'rzp_test_abc', amount: 8944, currency: 'INR', order_id: 'order_1', name: 'ParkEase', description: 'Parking PE-8KQ2M4',
      prefill: { name: 'Rahul Verma', email: 'driver@example.com', contact: undefined }, theme: { color: '#059669' },
    })
    expect(JSON.parse(verifyCalls()[0].data)).toEqual({ bookingId: 91, orderId: 'order_1', paymentId: 'pay_1', signature: 'sig_1' })
    expect(mock.history.post.some((r) => r.url === '/payments/mock/pay')).toBe(false)
  })

  it('stays on the page when the Razorpay window is dismissed', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    vi.mocked(openRazorpay).mockRejectedValue(new RazorpayDismissedError())
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByText('Payment cancelled. You can try again while your slot is held.')).toBeInTheDocument()
    expect(verifyCalls()).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
  })

  it('shows Razorpay\'s reason when the payment fails', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    vi.mocked(openRazorpay).mockRejectedValue(new RazorpayFailedError('Card declined by bank'))
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByText('Payment failed: Card declined by bank')).toBeInTheDocument()
    expect(verifyCalls()).toHaveLength(0)
  })

  it('says so when the Razorpay script cannot be loaded', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    vi.mocked(loadRazorpay).mockResolvedValue(false)
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't load the payment window/i)
    expect(openRazorpay).not.toHaveBeenCalled()
  })

  it('lets the driver retry the confirmation when verifying fails after a successful payment', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').replyOnce(500, { code: 'INTERNAL', detail: 'Something went wrong' }).onPost('/payments/verify').reply(200, confirmed)
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig_1' })
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
    expect(screen.queryByRole('button', { name: 'Pay ₹89.44' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm my payment' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings/91?new=1'))
    expect(openRazorpay).toHaveBeenCalledTimes(1)
    expect(verifyCalls()).toHaveLength(2)
  })

  it('keeps Pay available and shows the server detail after a 400 verification failure (Razorpay)', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').reply(400, { code: 'PAYMENT_VERIFICATION_FAILED', detail: 'Payment verification failed' })
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 'bad' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Payment verification failed')
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Confirm my payment' })).not.toBeInTheDocument()
  })

  it('keeps Pay available after a 409 verification response too', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').reply(409, { code: 'INVALID_STATUS', detail: 'This booking can no longer be paid for' })
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 's' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('This booking can no longer be paid for')
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
  })

  it('opens the booking page, not the success flow, when verify returns a CANCELLED booking (slot taken, refunded)', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').reply(200, { ...booking(), status: 'CANCELLED', refundAmount: 89.44 })
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 's' })
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent(/^\/driver\/bookings\/91$/))
    expect(screen.queryByRole('button', { name: 'Confirm my payment' })).not.toBeInTheDocument()
  })

  it('keeps the page and the confirmation button when the hold lapses while a payment is unconfirmed', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY', 1_500))
    mock.onPost('/payments/verify').reply(503, { code: 'UNAVAILABLE', detail: 'Service unavailable' })
    vi.mocked(openRazorpay).mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 's' }), 2_200)),
    )
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    // The hold lapses while the payment window is still open: the page stays.
    await new Promise((r) => setTimeout(r, 1_800))
    expect(screen.queryByRole('heading', { name: 'Your reservation expired' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Pay/ })).toBeDisabled()

    expect(await screen.findByRole('button', { name: 'Confirm my payment' }, { timeout: 3_000 })).toBeEnabled()
    expect(screen.getByText("Your payment is being confirmed. If it went through, we'll confirm or refund it automatically.")).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Your reservation expired' })).not.toBeInTheDocument()
  }, 10_000)

  it('shows the expired view once the in-flight payment ends without a signed payment', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY', 1_200))
    vi.mocked(openRazorpay).mockImplementation(() => new Promise((_, reject) => setTimeout(() => reject(new RazorpayDismissedError()), 1_800)))
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await new Promise((r) => setTimeout(r, 1_500))
    expect(screen.queryByRole('heading', { name: 'Your reservation expired' })).not.toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: 'Your reservation expired' }, { timeout: 3_000 })).toBeInTheDocument()
  }, 10_000)

  it('offers only the confirmation retry after a 503 (the outcome is unknown)', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').reply(503, { code: 'UNAVAILABLE', detail: 'Service unavailable' })
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 's' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable')
    expect(screen.getByRole('button', { name: 'Confirm my payment' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Pay ₹89.44' })).not.toBeInTheDocument()
  })

  it('offers the confirmation retry after a network error too', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    mock.onPost('/payments/verify').networkError()
    vi.mocked(openRazorpay).mockResolvedValue({ razorpay_order_id: 'order_1', razorpay_payment_id: 'pay_1', razorpay_signature: 's' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    expect(await screen.findByRole('button', { name: 'Confirm my payment' })).toBeEnabled()
  })

  it('opens the booking page when a test-payment verify returns a CANCELLED booking', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    mock.onPost('/payments/mock/pay').reply(200, signed)
    mock.onPost('/payments/verify').reply(200, { ...booking(), status: 'CANCELLED', refundAmount: 89.44 })
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent(/^\/driver\/bookings\/91$/))
  })

  it('names Razorpay in the footer only for the Razorpay provider', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    renderApp('/checkout/91')

    expect(await screen.findByText(/Payments are processed securely by Razorpay\./)).toBeInTheDocument()
    expect(screen.queryByText(/Test mode/)).not.toBeInTheDocument()
  })

  it('says no real money is charged in the footer for the mock provider', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    renderApp('/checkout/91')

    expect(await screen.findByText(/Test mode — no real money is charged\./)).toBeInTheDocument()
    expect(screen.queryByText(/Razorpay\./)).not.toBeInTheDocument()
  })

  it('surfaces a test-payment verification error on the page once the dialog is closed', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    mock.onPost('/payments/mock/pay').reply(200, signed)
    mock.onPost('/payments/verify').reply(400, { code: 'PAYMENT_VERIFICATION_FAILED', detail: 'Payment verification failed' })
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.click(dialog.getByRole('button', { name: 'Pay ₹89.44' }))
    await dialog.findByRole('alert')
    await userEvent.click(dialog.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('Payment verification failed')
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
  })

  it('returns to the page with Pay available when the test dialog is cancelled', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    renderApp('/checkout/91')

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('Payment cancelled. You can try again while your slot is held.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pay ₹89.44' })).toBeEnabled()
    expect(mock.history.post).toHaveLength(0)
  })

  it('opens Razorpay once when Pay is double-clicked', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('RAZORPAY'))
    vi.mocked(openRazorpay).mockReturnValue(new Promise(() => {}))
    renderApp('/checkout/91')

    await userEvent.dblClick(await screen.findByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(openRazorpay).toHaveBeenCalledTimes(1))
    expect(loadRazorpay).toHaveBeenCalledTimes(1)
  })

  it('sends one test payment when Pay is double-clicked in the dialog', async () => {
    mock.onGet('/bookings/91/checkout').reply(200, checkout('MOCK'))
    mock.onPost('/payments/mock/pay').reply(() => new Promise((resolve) => setTimeout(() => resolve([200, signed]), 50)))
    mock.onPost('/payments/verify').reply(200, confirmed)
    renderApp('/checkout/91', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pay ₹89.44' }))
    await userEvent.dblClick(within(screen.getByRole('dialog')).getByRole('button', { name: 'Pay ₹89.44' }))

    await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/driver/bookings/91?new=1'))
    expect(mock.history.post.filter((r) => r.url === '/payments/mock/pay')).toHaveLength(1)
    expect(verifyCalls()).toHaveLength(1)
  })

  it('shows the expired state for an expired hold (410) and links back to the listing with the same times', async () => {
    mock.onGet('/bookings/91/checkout').reply(410, { code: 'HOLD_EXPIRED', detail: 'Your reservation expired' })
    mock.onGet('/bookings/91').reply(200, { ...booking(), status: 'EXPIRED' })
    renderApp('/checkout/91')

    expect(await screen.findByRole('heading', { name: 'Your reservation expired' })).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Search again' })).toHaveAttribute(
        'href',
        `/listings/7?start=${encodeURIComponent(START)}&end=${encodeURIComponent(END)}&vehicle=FOUR_WHEELER`,
      ),
    )
  })

  it('falls back to search when the expired booking cannot be loaded', async () => {
    mock.onGet('/bookings/91/checkout').reply(410, { code: 'HOLD_EXPIRED', detail: 'Your reservation expired' })
    mock.onGet('/bookings/91').reply(404, { code: 'NOT_FOUND', detail: 'Booking not found' })
    renderApp('/checkout/91')

    await screen.findByRole('heading', { name: 'Your reservation expired' })
    expect(screen.getByRole('link', { name: 'Search again' })).toHaveAttribute('href', '/search')
  })

  it('explains a booking that can no longer be paid for', async () => {
    mock.onGet('/bookings/91/checkout').reply(409, { code: 'INVALID_STATUS', detail: 'This booking can no longer be paid for (CONFIRMED)' })
    renderApp('/checkout/91')

    expect(await screen.findByRole('alert')).toHaveTextContent('This booking can no longer be paid for (CONFIRMED)')
    expect(screen.getByRole('link', { name: 'View my bookings' })).toHaveAttribute('href', '/driver/bookings')
  })

  it('is for drivers only', async () => {
    mock.onGet('/me').reply(200, owner)
    mock.onGet('/owner/profile').reply(200, { verificationStatus: 'UNSUBMITTED' })
    mock.onGet('/owner/listings').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    renderApp('/checkout/91')

    expect(await screen.findByRole('heading', { name: 'Owner dashboard' })).toBeInTheDocument()
    expect(mock.history.get.some((r) => r.url === '/bookings/91/checkout')).toBe(false)
  })
})
