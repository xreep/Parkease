import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { api } from './api'
import type { Page } from './owner'

export type NotificationType =
  | 'BOOKING_CONFIRMED'
  | 'BOOKING_REQUESTED'
  | 'BOOKING_APPROVED'
  | 'BOOKING_DECLINED'
  | 'BOOKING_EXPIRED_REQUEST'
  | 'BOOKING_CANCELLED'
  | 'BOOKING_REFUNDED'
  | 'BOOKING_STARTING_SOON'
  | 'BOOKING_COMPLETED'
  | 'OWNER_NEW_BOOKING'
  | 'OWNER_APPROVAL_NEEDED'
  | 'OWNER_APPROVAL_REMINDER'
  | 'OWNER_BOOKING_CANCELLED'
  | 'OWNER_VERIFIED'
  | 'OWNER_REJECTED'
  | 'LISTING_APPROVED'
  | 'LISTING_REJECTED'
  | 'OWNER_NEW_REVIEW'
  | 'BOOKING_CANCELLED_BY_ADMIN'
  | 'DISPUTE_OPENED'
  | 'DISPUTE_RESPONSE'
  | 'DISPUTE_RESOLVED'
  | 'LISTING_SUSPENDED'
  | 'LISTING_REINSTATED'
  | 'ACCOUNT_SUSPENDED'
  | 'PAYOUT_SENT'

export type NotificationDto = {
  id: number
  type: NotificationType
  title: string
  body: string
  /** An app-relative path, or null when the notification opens nothing. */
  link: string | null
  read: boolean
  createdAt: string
}

/**
 * Only a plain path inside the app: one slash, then anything but a second slash or a backslash (`//host` and `/\host`
 * both make browsers and the router leave the site), with no whitespace or control characters.
 */
export function isSafeAppLink(link: string | null | undefined): link is string {
  if (!link || !/^\/(?![/\\])/.test(link)) return false
  for (const ch of link) {
    const code = ch.codePointAt(0)!
    if (code <= 0x20 || code === 0x7f || /\s/.test(ch)) return false
  }
  return true
}

export const UNREAD_REFRESH_MS = 30_000

export const listNotifications = async (page = 0, size = 20) =>
  (await api.get<Page<NotificationDto>>('/notifications', { params: { page, size } })).data
export const getUnreadCount = async () => (await api.get<{ count: number }>('/notifications/unread-count')).data.count
export const markRead = async (id: number) => {
  await api.post(`/notifications/${id}/read`)
}
export const markAllRead = async () => {
  await api.post('/notifications/read-all')
}

/** Refetches the lists and the unread count (the count's key sits under the same prefix). */
export function invalidateNotifications(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: ['notifications'] })
}

/** The badge number. Polls every 30 s, but only while the tab is visible. */
export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: ['notifications', 'unread'],
    queryFn: getUnreadCount,
    enabled,
    refetchInterval: () => (document.visibilityState === 'visible' ? UNREAD_REFRESH_MS : false),
  })
}

export function useNotifications(page = 0, size = 20, enabled = true) {
  return useQuery({
    queryKey: ['notifications', 'list', page, size],
    queryFn: () => listNotifications(page, size),
    enabled,
    placeholderData: (previous) => previous,
  })
}

/** Mark-as-read calls that refresh the lists and the badge afterwards, success or not. */
export function useNotificationActions() {
  const queryClient = useQueryClient()
  const read = useCallback(
    async (id: number) => {
      try {
        await markRead(id)
      } finally {
        await invalidateNotifications(queryClient)
      }
    },
    [queryClient],
  )
  const readAll = useCallback(async () => {
    try {
      await markAllRead()
    } finally {
      await invalidateNotifications(queryClient)
    }
  }, [queryClient])
  return { markRead: read, markAllRead: readAll }
}
