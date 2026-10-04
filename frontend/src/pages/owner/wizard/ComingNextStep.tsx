/** Temporary stand-in for wizard steps 4-6; Task 15 replaces it with the real steps. */
export function ComingNextStep({ label }: { label: string }) {
  return (
    <p className="rounded-xl border border-dashed border-slate-300 p-6 text-sm text-slate-500 dark:border-slate-700">
      {label}: coming next.
    </p>
  )
}
