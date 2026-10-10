import { Link } from 'react-router-dom'

export function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2 font-extrabold tracking-tight">
      <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-700 text-lg text-white">P</span>
      <span className="text-lg">
        Park<span className="text-brand-700 dark:text-brand-500">Ease</span>
      </span>
    </Link>
  )
}
