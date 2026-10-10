import clsx from 'clsx'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { useAuth } from '../../auth/AuthProvider'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { SearchBox } from '../../components/admin/SearchBox'
import { Button } from '../../components/ui/Button'
import { ReasonDialog } from '../../components/ui/Dialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Badge, StatusBadge } from '../../components/ui/StatusBadge'
import { invalidateAdminActivity } from '../../lib/admin'
import {
  activateUser,
  mapAdminError,
  suspendUser,
  useAdminUsers,
  adminErrorMessage,
  type AdminUser,
  type UserFilters,
  type UserRole,
  type UserStatus,
} from '../../lib/adminManage'
import { errorMessage } from '../../lib/errors'
import { formatDateTime, plural } from '../../lib/format'
import { stepBackIfEmpty, withPageReset } from '../../lib/paging'

const ROLE_LABELS: Record<UserRole, string> = { DRIVER: 'Driver', OWNER: 'Owner', ADMIN: 'Admin' }
const ROLE_TONES = { DRIVER: 'sky', OWNER: 'amber', ADMIN: 'slate' } as const
const fullName = (u: AdminUser) => `${u.firstName} ${u.lastName}`

function UserRow({ user, isSelf, onSuspend, onChanged }: { user: AdminUser; isSelf: boolean; onSuspend: () => void; onChanged: () => Promise<void> }) {
  const [activating, setActivating] = useState(false)
  const protectedAccount = user.role === 'ADMIN' || isSelf

  async function activate() {
    setActivating(true)
    try {
      await activateUser(user.id)
      toast.success(`${fullName(user)} activated`)
      await onChanged()
    } catch (error) {
      toast.error(adminErrorMessage(error))
    } finally {
      setActivating(false)
    }
  }

  return (
    <article aria-label={fullName(user)} className="space-y-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div className="min-w-0 space-y-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-semibold">{fullName(user)}</h3>
            <Badge tone={ROLE_TONES[user.role]}>{ROLE_LABELS[user.role]}</Badge>
            <StatusBadge kind="user" status={user.status} />
          </div>
          <p className="break-all text-sm text-slate-600 dark:text-slate-400">{user.email}</p>
          {user.phone && <p className="text-sm text-slate-600 dark:text-slate-400">{user.phone}</p>}
        </div>
        <div className="space-y-0.5 text-sm text-slate-700 dark:text-slate-300 sm:text-right">
          <p>{`${plural(user.bookingsCount, 'booking', 'bookings')} · ${plural(user.listingsCount, 'listing', 'listings')}`}</p>
          <p className="text-slate-500">{`Joined ${formatDateTime(user.createdAt)}`}</p>
          {!user.emailVerified && <p className="text-slate-500">Email not verified</p>}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {user.status === 'ACTIVE' ? (
          <Button
            type="button"
            variant="ghost"
            className="px-3 py-1.5 text-red-600 dark:text-red-400"
            disabled={protectedAccount}
            title={protectedAccount ? 'Admins and your own account can’t be suspended' : undefined}
            onClick={onSuspend}
          >
            Suspend
          </Button>
        ) : (
          <Button type="button" className="px-3 py-1.5" loading={activating} onClick={() => void activate()}>Activate</Button>
        )}
      </div>
    </article>
  )
}

export function AdminUsersPage() {
  const queryClient = useQueryClient()
  const { user: me } = useAuth()
  const [role, setRole] = useState<UserRole | ''>('')
  const [status, setStatus] = useState<UserStatus | ''>('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [suspending, setSuspending] = useState<AdminUser | null>(null)
  const filters: UserFilters = { ...(role && { role }), ...(status && { status }), ...(q && { q }) }
  const { data, error, isPending, isPlaceholderData } = useAdminUsers(filters, page)

  stepBackIfEmpty(page, setPage, data?.content, isPlaceholderData)

  const refresh = () =>
    Promise.all([queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }), invalidateAdminActivity(queryClient)]).then(() => undefined)

  async function confirmSuspend(reason: string) {
    if (!suspending) return
    try {
      await suspendUser(suspending.id, reason)
    } catch (e) {
      throw mapAdminError(e)
    }
    toast.success(`${fullName(suspending)} suspended`)
    setSuspending(null)
    await refresh()
  }

  const filter = withPageReset(setPage)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold">Users</h2>

      <div className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[10rem_10rem_1fr]">
        <Select label="Role" value={role} onChange={(e) => filter(setRole)(e.target.value as UserRole | '')}>
          <option value="">All roles</option>
          {(Object.keys(ROLE_LABELS) as UserRole[]).map((r) => (
            <option key={r} value={r}>{ROLE_LABELS[r]}</option>
          ))}
        </Select>
        <Select label="Status" value={status} onChange={(e) => filter(setStatus)(e.target.value as UserStatus | '')}>
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
        </Select>
        <SearchBox label="Search users" placeholder="Name or email" onSearch={filter(setQ)} className="sm:col-span-2 lg:col-span-1" />
      </div>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 && page === 0 ? (
        <Empty>No users match these filters.</Empty>
      ) : data ? (
        <>
          <div className={clsx('space-y-3 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
            {data.content.map((u) => (
              <UserRow key={u.id} user={u} isSelf={u.id === me?.id} onSuspend={() => setSuspending(u)} onChanged={refresh} />
            ))}
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      ) : null}

      {suspending && (
        <ReasonDialog
          open
          title={`Suspend ${fullName(suspending)}?`}
          confirmLabel="Suspend"
          maxLength={300}
          helper="They are signed out and told why. Bookings that already exist are honoured."
          onConfirm={confirmSuspend}
          onClose={() => setSuspending(null)}
        />
      )}
    </div>
  )
}
