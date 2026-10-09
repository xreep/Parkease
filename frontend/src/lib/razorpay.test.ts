import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadRazorpay, openRazorpay, RazorpayDismissedError, RazorpayFailedError, type RazorpayOptions } from './razorpay'

const options: RazorpayOptions = { key: 'k', amount: 100, currency: 'INR', order_id: 'o', name: 'ParkEase' }

type Config = { handler: (r: unknown) => void; modal: { ondismiss: () => void } }

/** A fake Checkout.js whose instance hands its config and failure listener to the test. */
function fakeCheckout() {
  const instance = { config: undefined as Config | undefined, failed: undefined as ((r: unknown) => void) | undefined, close: vi.fn(), open: vi.fn() }
  window.Razorpay = class {
    constructor(config: Config) {
      instance.config = config
    }
    open = instance.open
    close = instance.close
    on = (_event: string, handler: (r: unknown) => void) => {
      instance.failed = handler
    }
  } as unknown as typeof window.Razorpay
  return instance
}

describe('razorpay', () => {
  afterEach(() => {
    delete window.Razorpay
  })

  it('resolves immediately when Checkout.js is already loaded', async () => {
    fakeCheckout()
    expect(await loadRazorpay()).toBe(true)
  })

  it('resolves with the signed response on success', async () => {
    const checkout = fakeCheckout()
    const result = openRazorpay(options)
    const response = { razorpay_payment_id: 'p', razorpay_order_id: 'o', razorpay_signature: 's' }
    checkout.config!.handler(response)

    await expect(result).resolves.toEqual(response)
    expect(checkout.open).toHaveBeenCalled()
  })

  it('rejects as dismissed when the window is closed', async () => {
    const checkout = fakeCheckout()
    const result = openRazorpay(options)
    checkout.config!.modal.ondismiss()

    await expect(result).rejects.toBeInstanceOf(RazorpayDismissedError)
  })

  it('rejects with the reason on payment.failed and closes the window', async () => {
    const checkout = fakeCheckout()
    const result = openRazorpay(options)
    checkout.failed!({ error: { description: 'Card declined' } })
    checkout.config!.modal.ondismiss()

    await expect(result).rejects.toEqual(expect.objectContaining({ kind: 'failed', description: 'Card declined' }))
    await expect(result).rejects.toBeInstanceOf(RazorpayFailedError)
    expect(checkout.close).toHaveBeenCalled()
  })

  it('rejects when Checkout.js is not loaded', async () => {
    await expect(openRazorpay(options)).rejects.toBeInstanceOf(RazorpayFailedError)
  })
})
