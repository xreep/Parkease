import clsx from 'clsx'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { errorMessage } from '../../lib/errors'
import { isSafeAppLink, useNotificationActions, type NotificationDto } from '../../lib/notifications'
import { formatRelativeTime } from '../../lib/time'

/** A notification row: unread ones are bold with a dot. Clicking marks it read and opens its link. */
export function NotificationItem({ notification, now, onOpen }: { notification: NotificationDto; now: number; onOpen?: () => void }) {
  const navigate = useNavigate()
  const { markRead } = useNotificationActions()

  function open() {
    if (!notification.read) {
      markRead(notification.id).catch((error: unknown) => toast.error(errorMessage(error)))
    }
    onOpen?.()
    if (isSafeAppLink(notification.link)) navigate(notification.link)
  }

  return (
    <li>
      <button
        type="button"
        onClick={open}
        className="flex w-full items-start gap-3 px-4 py-3 text-left transition hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none dark:hover:bg-slate-800 dark:focus-visible:bg-slate-800"
      >
        <span
          aria-hidden
          className={clsx('mt-1.5 h-2 w-2 shrink-0 rounded-full', notification.read ? 'bg-transparent' : 'bg-brand-600')}
        />
        <span className="min-w-0 flex-1">
          <span className={clsx('block break-words text-sm', notification.read ? 'font-normal' : 'font-bold')}>
            {notification.title}
            {!notification.read && <span className="sr-only"> (unread)</span>}
          </span>
          <span className="mt-0.5 block break-words text-sm text-slate-600 dark:text-slate-400">{notification.body}</span>
          <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">{formatRelativeTime(notification.createdAt, now)}</span>
        </span>
      </button>
    </li>
  )
}
