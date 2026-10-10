import { useEffect, useRef } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import clsx from 'clsx'
import { FormError } from '../../components/AuthCard'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { errorMessage } from '../../lib/errors'
import { useListing, useRefreshListing } from '../../lib/owner'
import { HoursStep } from './wizard/HoursStep'
import { LocationStep } from './wizard/LocationStep'
import { PhotosStep } from './wizard/PhotosStep'
import { PricingStep } from './wizard/PricingStep'
import { ReviewStep } from './wizard/ReviewStep'
import { SlotsStep } from './wizard/SlotsStep'
import { usePageTitle } from '../../lib/usePageTitle'

const STEPS = ['Location', 'Photos', 'Slots', 'Pricing', 'Hours', 'Review'] as const

function parseStep(raw: string | null): number {
  const n = Number(raw)
  return Number.isInteger(n) && n >= 1 && n <= STEPS.length ? n : 1
}

const stepClass = (current: boolean, enabled: boolean) =>
  clsx(
    'flex items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium transition',
    current
      ? 'bg-brand-700 text-white'
      : enabled
        ? 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800'
        : 'text-slate-400 dark:text-slate-600',
  )

function Stepper({ current, listingId }: { current: number; listingId?: number }) {
  const currentRef = useRef<HTMLLIElement>(null)
  // On narrow screens the stepper scrolls sideways; keep the current step visible.
  useEffect(() => {
    const el = currentRef.current
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [current])
  return (
    <nav aria-label="Listing steps" className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0">
      <ol className="flex min-w-max gap-1">
        {STEPS.map((label, i) => {
          const n = i + 1
          const isCurrent = n === current
          const body = (
            <>
              <span aria-hidden className="text-xs opacity-75">{n}</span>
              {label}
            </>
          )
          return (
            <li key={label} ref={isCurrent ? currentRef : undefined}>
              {listingId === undefined ? (
                <span className={stepClass(isCurrent, false)} aria-current={isCurrent ? 'step' : undefined}>{body}</span>
              ) : (
                <Link
                  to={`/owner/listings/${listingId}/edit?step=${n}`}
                  className={stepClass(isCurrent, true)}
                  aria-current={isCurrent ? 'step' : undefined}
                >
                  {body}
                </Link>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

function Centered({ children }: { children: React.ReactNode }) {
  return <div className="flex justify-center py-12">{children}</div>
}

function NewListingWizard() {
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">New listing</h2>
      <Stepper current={1} />
      <LocationStep />
    </div>
  )
}

function EditListingWizard({ id }: { id: number }) {
  const navigate = useNavigate()
  const refresh = useRefreshListing(id)
  const [params] = useSearchParams()
  const step = parseStep(params.get('step'))
  const { data: listing, error, isPending } = useListing(id)

  if (isPending) {
    return (
      <Centered>
        <Spinner className="h-8 w-8 text-brand-600" />
      </Centered>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />

  async function onSaved(next?: number) {
    await refresh()
    if (next !== undefined) navigate(`/owner/listings/${id}/edit?step=${next}`)
  }

  const stepProps = { listing, onSaved: (next?: number) => void onSaved(next) }
  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold">{listing.title}</h2>
          <StatusBadge kind="listing" status={listing.status} />
        </div>
        {listing.status === 'REJECTED' && listing.rejectionReason && (
          <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">
            Changes requested: {listing.rejectionReason}
          </p>
        )}
        {listing.status === 'SUSPENDED' && (
          <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">
            This listing was suspended by ParkEase and can't be edited.
          </p>
        )}
      </div>
      <Stepper current={step} listingId={id} />
      {step === 1 && <LocationStep {...stepProps} />}
      {step === 2 && <PhotosStep {...stepProps} />}
      {step === 3 && <SlotsStep {...stepProps} />}
      {step === 4 && <PricingStep {...stepProps} />}
      {step === 5 && <HoursStep {...stepProps} />}
      {step === 6 && <ReviewStep {...stepProps} />}
    </div>
  )
}

export function ListingWizardPage() {
  const { id } = useParams()
  usePageTitle(id === undefined ? 'New listing' : 'Edit listing')
  if (id === undefined) return <NewListingWizard />
  const numeric = Number(id)
  if (!Number.isInteger(numeric) || numeric <= 0) return <FormError message="Listing not found" />
  return <EditListingWizard id={numeric} />
}
