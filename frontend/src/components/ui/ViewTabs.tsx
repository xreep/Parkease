import clsx from 'clsx'

export type ViewTab<T extends string> = { value: T; label: string }

/** A tab strip that switches a view in place (the page keeps the state). Left/right arrows move between tabs. */
export function ViewTabs<T extends string>({
  items,
  value,
  onChange,
  label,
}: {
  items: ViewTab<T>[]
  value: T
  onChange: (value: T) => void
  label: string
}) {
  return (
    <div
      role="tablist"
      aria-label={label}
      className="flex gap-1 overflow-x-auto border-b border-slate-200 dark:border-slate-800"
      onKeyDown={(e) => {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
        const index = items.findIndex((i) => i.value === value)
        const next = items[(index + (e.key === 'ArrowRight' ? 1 : items.length - 1)) % items.length]
        onChange(next.value)
        const tabs = e.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]')
        tabs[items.indexOf(next)]?.focus()
      }}
    >
      {items.map((item) => {
        const selected = item.value === value
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(item.value)}
            className={clsx(
              'whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition',
              selected
                ? 'border-brand-600 text-brand-700 dark:border-brand-400 dark:text-brand-400'
                : 'border-transparent text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white',
            )}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
