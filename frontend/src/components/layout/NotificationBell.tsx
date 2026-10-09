import clsx from 'clsx'
import { Bell } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { errorMessage } from '../../lib/errors'
import { useNotificationActions, useNotifications, useUnreadCount } from '../../lib/notifications'
import { useNow } from '../../lib/useNow'
import { NotificationItem } from '../notifications/NotificationItem'
import { Spinner } from '../ui/Spinner'

/** The bell in the navbar: an unread badge, and a popover with the latest ten notifications. */
export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const { data: unread = 0 } = useUnreadCount()
  const { data, isPending, error } = useNotifications(0, 10, open)
  const { markAllRead } = useNotificationActions()
  const now = useNow(open, 30_000)

  useEffect(() => {
    if (!open) return
    panelRef.current?.focus()
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    function onPointerDown(e: MouseEvent) {
      if (!wrapperRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  const items = data?.content ?? []

  return (
    <div
      ref={wrapperRef}
      className="sm:relative"
      onBlur={(e) => {
        // Tabbing out of the popover closes it.
        if (open && e.relatedTarget && !wrapperRef.current?.contains(e.relatedTarget as Node)) setOpen(false)
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        aria-label={`Notifications (${unread} unread)`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((o) => !o)}
        className="relative rounded-lg p-2 hover:bg-slate-100 dark:hover:bg-slate-800"
      >
        <Bell aria-hidden className="h-5 w-5" />
        {unread > 0 && (
          <span
            aria-hidden
            data-testid="unread-badge"
            className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold leading-none text-white"
          >
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="dialog"
          aria-label="Notifications"
          tabIndex={-1}
          className="absolute inset-x-4 top-full z-50 mt-1 max-h-[80vh] overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl outline-none dark:border-slate-800 dark:bg-slate-900 sm:inset-x-auto sm:right-0 sm:w-96"
        >
          <div className="flex items-center justify-between gap-2 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
            <h2 className="text-sm font-semibold">Notifications</h2>
            <button
              type="button"
              disabled={unread === 0}
              onClick={() => markAllRead().catch((err: unknown) => toast.error(errorMessage(err)))}
              className="text-sm font-semibold text-brand-700 hover:underline disabled:cursor-not-allowed disabled:opacity-50 disabled:no-underline dark:text-brand-400"
            >
              Mark all as read
            </button>
          </div>
          <div className="max-h-[60vh] overflow-y-auto">
            {isPending ? (
              <div className="flex justify-center py-8">
                <Spinner className="h-6 w-6 text-brand-600" />
              </div>
            ) : error ? (
              <p role="alert" className="px-4 py-6 text-center text-sm text-red-600 dark:text-red-400">{errorMessage(error)}</p>
            ) : items.length === 0 ? (
              <p className="px-4 py-8 text-center text-sm text-slate-600 dark:text-slate-400">You&apos;re all caught up.</p>
            ) : (
              <ul className={clsx('divide-y divide-slate-100 dark:divide-slate-800')}>
                {items.map((n) => (
                  <NotificationItem key={n.id} notification={n} now={now} onOpen={() => setOpen(false)} />
                ))}
              </ul>
            )}
          </div>
          <div className="border-t border-slate-200 px-4 py-2.5 text-center dark:border-slate-800">
            <Link
              to="/notifications"
              onClick={() => setOpen(false)}
              className="text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400"
            >
              View all
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}
