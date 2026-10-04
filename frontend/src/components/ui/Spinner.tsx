import { Loader2 } from 'lucide-react'
import clsx from 'clsx'

export function Spinner({ className }: { className?: string }) {
  return <Loader2 aria-hidden className={clsx('animate-spin', className ?? 'h-5 w-5')} />
}
