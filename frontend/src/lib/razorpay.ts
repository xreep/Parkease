const CHECKOUT_SRC = 'https://checkout.razorpay.com/v1/checkout.js'

export type RazorpaySuccess = {
  razorpay_payment_id: string
  razorpay_order_id: string
  razorpay_signature: string
}

export type RazorpayOptions = {
  key: string
  /** In paise. */
  amount: number
  currency: string
  order_id: string
  name: string
  description?: string
  prefill?: { name?: string; email?: string; contact?: string }
  theme?: { color?: string }
}

/** The customer closed the Checkout window without paying. */
export class RazorpayDismissedError extends Error {
  readonly kind = 'dismissed'
  constructor() {
    super('Payment cancelled')
    this.name = 'RazorpayDismissedError'
  }
}

/** Razorpay reported `payment.failed`; `description` is its customer-facing reason. */
export class RazorpayFailedError extends Error {
  readonly kind = 'failed'
  readonly description: string
  constructor(description: string) {
    super(description)
    this.description = description
    this.name = 'RazorpayFailedError'
  }
}

type RazorpayInstance = {
  open: () => void
  close: () => void
  on: (event: 'payment.failed', handler: (response: { error?: { description?: string } }) => void) => void
}
type RazorpayConstructor = new (
  options: RazorpayOptions & { handler: (response: RazorpaySuccess) => void; modal: { ondismiss: () => void } },
) => RazorpayInstance

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor
  }
}

let loading: Promise<boolean> | null = null

/** Loads Razorpay's Checkout.js once. Resolves false when it can't be loaded (offline, blocked). */
export function loadRazorpay(): Promise<boolean> {
  if (typeof window === 'undefined') return Promise.resolve(false)
  if (window.Razorpay) return Promise.resolve(true)
  loading ??= new Promise<boolean>((resolve) => {
    const script = document.createElement('script')
    script.src = CHECKOUT_SRC
    script.async = true
    script.onload = () => resolve(Boolean(window.Razorpay))
    script.onerror = () => {
      script.remove()
      loading = null
      resolve(false)
    }
    document.body.appendChild(script)
  })
  return loading
}

/**
 * Opens Razorpay Checkout. Resolves with the signed payment details, rejects with a
 * {@link RazorpayDismissedError} when closed, or a {@link RazorpayFailedError} when the payment fails.
 * Call {@link loadRazorpay} first.
 */
export function openRazorpay(options: RazorpayOptions): Promise<RazorpaySuccess> {
  return new Promise((resolve, reject) => {
    if (!window.Razorpay) {
      reject(new RazorpayFailedError('Payment window could not be loaded'))
      return
    }
    const checkout = new window.Razorpay({
      ...options,
      handler: resolve,
      modal: { ondismiss: () => reject(new RazorpayDismissedError()) },
    })
    checkout.on('payment.failed', (response) => {
      reject(new RazorpayFailedError(response.error?.description ?? 'Unknown error'))
      // Close the window (after settling, so its dismiss callback is ignored): a retry inside it
      // could otherwise succeed after this promise has already settled.
      checkout.close()
    })
    checkout.open()
  })
}
