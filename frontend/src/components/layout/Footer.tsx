const YEAR = new Date().getFullYear()

export function Footer() {
  return (
    <footer className="border-t border-slate-200 py-8 text-sm text-slate-500 dark:border-slate-800">
      <div className="mx-auto flex max-w-7xl flex-col items-center justify-between gap-2 px-4 sm:flex-row sm:px-6">
        <p>© {YEAR} ParkEase. Parking made simple across India.</p>
        <p>Built for commuters near metros, offices and markets.</p>
      </div>
    </footer>
  )
}
