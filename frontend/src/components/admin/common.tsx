import type { ReactNode } from 'react'
import { Spinner } from '../ui/Spinner'

export function Loading() {
  return (
    <div className="flex justify-center py-12">
      <Spinner className="h-8 w-8 text-brand-600" />
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-slate-300 p-10 text-center dark:border-slate-700">
      <p className="text-slate-600 dark:text-slate-400">{children}</p>
    </div>
  )
}

export const linkClass = 'font-medium text-brand-700 hover:underline dark:text-brand-400'
export const primaryLink =
  'inline-flex items-center justify-center rounded-lg bg-brand-700 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-800'
