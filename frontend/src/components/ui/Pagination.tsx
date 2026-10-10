import { Button } from './Button'

export type PaginationProps = { page: number; totalPages: number; onChange: (page: number) => void }

/** Previous / Next controls; renders nothing when there is a single page. */
export function Pagination({ page, totalPages, onChange }: PaginationProps) {
  if (totalPages <= 1) return null
  return (
    <div className="flex items-center justify-between gap-3">
      <Button type="button" variant="secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>Previous</Button>
      <p className="text-sm text-slate-500 dark:text-slate-400">{`Page ${page + 1} of ${totalPages}`}</p>
      <Button type="button" variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</Button>
    </div>
  )
}
