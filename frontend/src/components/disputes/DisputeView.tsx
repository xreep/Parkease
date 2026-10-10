import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { StatusBadge } from '../ui/StatusBadge'
import { DISPUTE_CATEGORY_LABELS, DISPUTE_RESOLUTION_LABELS, type Dispute, type DisputeSummary } from '../../lib/disputes'
import { formatDateTime, formatINR } from '../../lib/format'

const linkClass = 'font-medium text-brand-700 hover:underline dark:text-brand-400'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <h3 className="font-semibold">{title}</h3>
      <div className="mt-2 space-y-1 text-sm text-slate-700 dark:text-slate-300">{children}</div>
    </section>
  )
}

/**
 * One report, read-only: who raised what, the owner's answer and the outcome. `actions` sit by the title and `children`
 * (a response form, say) go under the owner's answer. Admin notes and the refundable figure only exist for admins.
 */
export function DisputeView({
  dispute,
  title,
  bookingHref,
  actions,
  noResponseText = 'The owner has not responded yet.',
  children,
}: {
  dispute: Dispute
  title: string
  bookingHref?: string
  actions?: ReactNode
  noResponseText?: string
  children?: ReactNode
}) {
  const resolved = dispute.status === 'RESOLVED'
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="text-xl font-semibold">{title}</h2>
            <StatusBadge kind="dispute" status={dispute.status} />
          </div>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            {`${DISPUTE_CATEGORY_LABELS[dispute.category]} · ${dispute.listingTitle} · ${formatDateTime(dispute.createdAt)}`}
          </p>
          {bookingHref && <Link to={bookingHref} className={`text-sm ${linkClass}`}>View booking</Link>}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>

      <Section title="What happened">
        <p className="text-xs text-slate-500">{`Reported by ${dispute.raisedByName}`}</p>
        <p className="whitespace-pre-line break-words">{dispute.description}</p>
      </Section>

      <Section title="Owner response">
        {dispute.ownerResponse ? (
          <>
            <p className="whitespace-pre-line break-words">{dispute.ownerResponse}</p>
            {dispute.ownerRespondedAt && <p className="text-xs text-slate-500">{formatDateTime(dispute.ownerRespondedAt)}</p>}
          </>
        ) : (
          <p>{noResponseText}</p>
        )}
      </Section>

      {children}

      {!resolved && dispute.refundableRemaining !== null && (
        <p className="text-sm font-medium">{`Refundable: ${formatINR(dispute.refundableRemaining)}`}</p>
      )}

      {resolved && dispute.resolution && (
        <Section title="Outcome">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt className="text-slate-500">Decision</dt>
            <dd>{DISPUTE_RESOLUTION_LABELS[dispute.resolution]}</dd>
            {dispute.resolutionAmount !== null && (
              <>
                <dt className="text-slate-500">Refunded</dt>
                <dd>{formatINR(dispute.resolutionAmount)}</dd>
              </>
            )}
            {dispute.resolvedAt && (
              <>
                <dt className="text-slate-500">Resolved</dt>
                <dd>{formatDateTime(dispute.resolvedAt)}</dd>
              </>
            )}
            {dispute.adminNotes && (
              <>
                <dt className="text-slate-500">Admin notes</dt>
                <dd className="whitespace-pre-line break-words">{dispute.adminNotes}</dd>
              </>
            )}
          </dl>
        </Section>
      )}
    </div>
  )
}

/** A list of reports, each a link to its page. */
export function DisputeList({ items, label, href }: { items: DisputeSummary[]; label: string; href: (d: DisputeSummary) => string }) {
  return (
    <ul aria-label={label} className="space-y-3">
      {items.map((d) => (
        <li key={d.id} className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Link to={href(d)} className={linkClass}>{`${DISPUTE_CATEGORY_LABELS[d.category]} · ${d.bookingCode}`}</Link>
            <StatusBadge kind="dispute" status={d.status} />
          </div>
          <p className="mt-1 text-sm">{d.listingTitle}</p>
          <p className="text-xs text-slate-500">{formatDateTime(d.createdAt)}</p>
        </li>
      ))}
    </ul>
  )
}
