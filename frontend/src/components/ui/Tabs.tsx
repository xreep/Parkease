import { NavLink } from 'react-router-dom'
import clsx from 'clsx'

export type TabItem = { to: string; label: string; end?: boolean }

export function Tabs({ items }: { items: TabItem[] }) {
  return (
    <nav aria-label="Sections" className="-mx-4 overflow-x-auto border-b border-slate-200 px-4 dark:border-slate-800 sm:mx-0 sm:px-0">
      <ul className="flex min-w-max gap-1">
        {items.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                clsx(
                  'block whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition',
                  isActive
                    ? 'border-brand-600 text-brand-700 dark:border-brand-400 dark:text-brand-400'
                    : 'border-transparent text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white',
                )
              }
            >
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
