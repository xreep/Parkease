import clsx from 'clsx'
import { useState, type FormEvent } from 'react'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { useAdminAudit, type AdminAction, type AuditFilters } from '../../lib/admin'
import { errorMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { stepBackIfEmpty } from '../../lib/paging'
import { usePageTitle } from '../../lib/usePageTitle'

const TARGET_TYPES = ['USER', 'OWNER', 'LISTING', 'REVIEW', 'STATE', 'CITY', 'BOOKING', 'DISPUTE', 'REFUND', 'SETTINGS']
const label = (type: string) => type.charAt(0) + type.slice(1).toLowerCase()

/** What the server records, so the filter can only ask for actions that exist. */
const ACTIONS = [
  'OWNER_VERIFIED', 'OWNER_REJECTED', 'LISTING_APPROVED', 'LISTING_REJECTED', 'LISTING_SUSPENDED', 'LISTING_REINSTATED',
  'USER_SUSPENDED', 'USER_ACTIVATED', 'REVIEW_HIDDEN', 'REVIEW_UNHIDDEN', 'STATE_CREATED', 'STATE_UPDATED', 'CITY_CREATED',
  'CITY_UPDATED', 'BOOKING_CANCELLED', 'REFUND_RETRIED', 'PAYOUT_MARKED_PAID', 'DISPUTE_UNDER_REVIEW', 'DISPUTE_RESOLVED',
  'SETTINGS_UPDATED',
]

/** "USER 7"; settings changes have no target id, shown as a dash. */
const target = (a: AdminAction) => `${a.targetType} ${a.targetId ?? '—'}`

function AuditTable({ actions }: { actions: AdminAction[] }) {
  return (
    <>
      <div className="hidden overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800 md:block">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Audit log</caption>
          <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
            <tr>
              {['When', 'Admin', 'Action', 'Target', 'Details'].map((h) => (
                <th key={h} scope="col" className="px-3 py-2 font-medium">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
            {actions.map((a) => (
              <tr key={a.id} className="align-top">
                <td className="whitespace-nowrap px-3 py-2 text-slate-600 dark:text-slate-400">{formatDateTime(a.createdAt)}</td>
                <td className="px-3 py-2">{a.adminName}</td>
                <td className="px-3 py-2 font-mono text-xs">{a.action}</td>
                <td className="whitespace-nowrap px-3 py-2">{target(a)}</td>
                <td className="break-words px-3 py-2 text-slate-600 dark:text-slate-400">{a.details ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul aria-label="Audit log list" className="space-y-3 md:hidden">
        {actions.map((a) => (
          <li key={a.id} className="space-y-1 rounded-2xl border border-slate-200 bg-white p-4 text-sm dark:border-slate-800 dark:bg-slate-900">
            <p className="break-all font-mono text-xs font-semibold">{a.action}</p>
            <p>{target(a)}</p>
            {a.details && <p className="break-words text-slate-600 dark:text-slate-400">{a.details}</p>}
            <p className="text-xs text-slate-500">{`${a.adminName} · ${formatDateTime(a.createdAt)}`}</p>
          </li>
        ))}
      </ul>
    </>
  )
}

export function AdminAuditPage() {
  usePageTitle('Admin · Audit log')
  const [action, setAction] = useState('')
  const [targetType, setTargetType] = useState('')
  const [applied, setApplied] = useState<AuditFilters>({})
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useAdminAudit(applied, page)
  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  function apply(next: AuditFilters) {
    setApplied(next)
    setPage(0)
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    apply({ ...(action.trim() && { action: action.trim() }), ...(targetType && { targetType }) })
  }

  function clear() {
    setAction('')
    setTargetType('')
    apply({})
  }

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Audit log</h2>

      <form onSubmit={submit} className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_auto_auto]">
        <Select label="Action" value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="">All actions</option>
          {ACTIONS.map((a) => (
            <option key={a} value={a}>{label(a.replace(/_/g, ' '))}</option>
          ))}
        </Select>
        <Select label="Target type" value={targetType} onChange={(e) => setTargetType(e.target.value)}>
          <option value="">All types</option>
          {TARGET_TYPES.map((t) => (
            <option key={t} value={t}>{label(t)}</option>
          ))}
        </Select>
        <Button type="submit">Apply filters</Button>
        <Button type="button" variant="secondary" onClick={clear}>Clear filters</Button>
      </form>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 ? (
        <Empty>No actions match these filters.</Empty>
      ) : data ? (
        <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <AuditTable actions={data.content} />
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </div>
      ) : null}
    </div>
  )
}
