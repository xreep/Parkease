import clsx from 'clsx'
import { useState } from 'react'
import { toast } from 'sonner'
import { FormError } from '../components/AuthCard'
import { NotificationItem } from '../components/notifications/NotificationItem'
import { Button } from '../components/ui/Button'
import { Pagination } from '../components/ui/Pagination'
import { Spinner } from '../components/ui/Spinner'
import { panelId, tabId } from '../components/ui/tabIds'
import { ViewTabs } from '../components/ui/ViewTabs'
import { errorMessage } from '../lib/errors'
import { useNotificationActions, useNotifications, useUnreadCount } from '../lib/notifications'
import { useNow } from '../lib/useNow'
import { usePageTitle } from '../lib/usePageTitle'

type Filter = 'all' | 'unread'

const TABS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'unread', label: 'Unread' },
]

export function NotificationsPage() {
  usePageTitle('Notifications')
  const [filter, setFilter] = useState<Filter>('all')
  const [page, setPage] = useState(0)
  const { data, error, isPending, isPlaceholderData } = useNotifications(page, 20)
  const { data: unread = 0 } = useUnreadCount()
  const { markAllRead } = useNotificationActions()
  const [marking, setMarking] = useState(false)
  const now = useNow(true, 30_000)

  async function readAll() {
    setMarking(true)
    try {
      await markAllRead()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setMarking(false)
    }
  }

  const items = (data?.content ?? []).filter((n) => filter === 'all' || !n.read)

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold">Notifications</h1>
        <Button type="button" variant="secondary" loading={marking} disabled={unread === 0} onClick={() => void readAll()}>
          Mark all as read
        </Button>
      </div>
      <ViewTabs idPrefix="notifications" label="Notification filter" items={TABS} value={filter} onChange={setFilter} />

      <div role="tabpanel" id={panelId('notifications', filter)} aria-labelledby={tabId('notifications', filter)} className="space-y-4">
        {isPending ? (
          <div className="flex justify-center py-12">
            <Spinner className="h-8 w-8 text-brand-600" />
          </div>
        ) : error ? (
          <FormError message={errorMessage(error)} />
        ) : items.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
            <p className="text-slate-600 dark:text-slate-400">
              {filter === 'unread' ? "You're all caught up." : 'No notifications yet.'}
            </p>
          </div>
        ) : (
          <ul
            aria-busy={isPlaceholderData}
            className={clsx(
              'divide-y divide-slate-100 overflow-hidden rounded-2xl border border-slate-200 bg-white transition-opacity dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-900',
              isPlaceholderData && 'opacity-60',
            )}
          >
            {items.map((n) => (
              <NotificationItem key={n.id} notification={n} now={now} />
            ))}
          </ul>
        )}
        {data && <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />}
      </div>
    </div>
  )
}
