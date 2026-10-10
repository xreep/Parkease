import { Link } from 'react-router-dom'
import { usePageTitle } from '../lib/usePageTitle'

export function NotFoundPage() {
  usePageTitle('Page not found')
  return (
    <section className="mx-auto max-w-lg px-4 py-24 text-center">
      <p className="text-6xl font-extrabold text-brand-600">404</p>
      <h1 className="mt-4 text-2xl font-bold">This spot is empty</h1>
      <p className="mt-2 text-slate-500 dark:text-slate-400">The page you were looking for does not exist.</p>
      <Link to="/" className="mt-6 inline-block font-semibold text-brand-700 hover:underline dark:text-brand-400">Back to home</Link>
    </section>
  )
}
