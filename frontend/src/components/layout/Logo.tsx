import { Link } from 'react-router-dom'

export function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2 font-extrabold tracking-tight">
      <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-600 text-lg text-white">P</span>
      <span className="text-lg">
        Smart<span className="text-brand-600">Park</span>
      </span>
    </Link>
  )
}
