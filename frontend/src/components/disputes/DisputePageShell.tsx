import type { ReactNode } from 'react'
import { FormError } from '../AuthCard'
import { Loading } from '../admin/common'
import { errorMessage } from '../../lib/errors'

/** The loading, error and not-found states every single-report page shares. */
export function DisputePageShell<T>({
  id,
  query,
  children,
}: {
  id: string | undefined
  query: { data: T | undefined; error: unknown; isPending: boolean }
  children: (data: T) => ReactNode
}) {
  const numeric = Number(id)
  if (!Number.isInteger(numeric) || numeric <= 0) return <FormError message="Report not found" />
  if (query.isPending) return <Loading />
  if (!query.data) return <FormError message={errorMessage(query.error)} />
  return <>{children(query.data)}</>
}
