import { useState } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { Menu, X } from 'lucide-react'
import clsx from 'clsx'
import { useAuth } from '../../auth/AuthProvider'
import { homeFor } from '../../auth/types'
import { Logo } from './Logo'
import { ThemeToggle } from './ThemeToggle'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  clsx(
    'rounded-lg px-3 py-2 text-sm font-medium transition',
    isActive
      ? 'text-brand-700 dark:text-brand-400'
      : 'text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white',
  )

export function Navbar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)

  function handleLogout() {
    setOpen(false)
    navigate('/')
    void logout()
  }

  const links = (
    <>
      <NavLink to="/" end className={linkClass} onClick={() => setOpen(false)}>Home</NavLink>
      <NavLink to="/search" className={linkClass} onClick={() => setOpen(false)}>Find parking</NavLink>
      {user ? (
        <>
          <NavLink to={homeFor(user.role)} className={linkClass} onClick={() => setOpen(false)}>Dashboard</NavLink>
          <NavLink to="/account" className={linkClass} onClick={() => setOpen(false)}>Account</NavLink>
          <button type="button" onClick={handleLogout} className={linkClass({ isActive: false })}>Log out</button>
        </>
      ) : (
        <>
          <Link
            to="/register?role=OWNER"
            onClick={() => setOpen(false)}
            className="rounded-lg px-3 py-2 text-sm font-medium text-slate-600 transition hover:text-slate-900 dark:text-slate-300 dark:hover:text-white"
          >
            List your space
          </Link>
          <NavLink to="/login" className={linkClass} onClick={() => setOpen(false)}>Log in</NavLink>
          <Link
            to="/register"
            onClick={() => setOpen(false)}
            className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700"
          >
            Sign up
          </Link>
        </>
      )}
    </>
  )

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/85 backdrop-blur dark:border-slate-800 dark:bg-slate-950/85">
      <nav className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
        <Logo />
        <div className="hidden items-center gap-1 md:flex">
          {links}
          <ThemeToggle />
        </div>
        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <button
            type="button"
            aria-label={open ? 'Close menu' : 'Open menu'}
            aria-expanded={open}
            onClick={() => setOpen((o) => !o)}
            className="rounded-lg p-2 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
        </div>
      </nav>
      {open && (
        <div className="flex flex-col gap-1 border-t border-slate-200 px-4 py-3 md:hidden dark:border-slate-800">{links}</div>
      )}
    </header>
  )
}
