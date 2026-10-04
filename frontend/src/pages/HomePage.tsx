import { Link } from 'react-router-dom'
import { CalendarCheck, Car, IndianRupee, MapPin, ShieldCheck, Timer } from 'lucide-react'
import { Spinner } from '../components/ui/Spinner'
import { useStates, type StateSummary } from '../lib/locations'

const steps = [
  { Icon: MapPin, title: 'Search near your destination', body: 'Metro stations, offices, malls and markets across India.' },
  { Icon: CalendarCheck, title: 'Reserve in advance', body: 'Pick your time and vehicle. Pay securely with UPI or card.' },
  { Icon: Car, title: 'Park and go', body: 'Show your booking code on arrival. No circling, no stress.' },
]

const perks = [
  { Icon: Timer, label: 'Save time searching' },
  { Icon: IndianRupee, label: 'Transparent pricing' },
  { Icon: ShieldCheck, label: 'Verified owners' },
]

function StateGrid({ title, states }: { title: string; states: StateSummary[] }) {
  return (
    <div>
      <h3 className="text-lg font-semibold">{title}</h3>
      <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {states.map((s) => (
          <Link
            key={s.id}
            to={`/in/${s.slug}`}
            className="group rounded-xl border border-slate-200 bg-white p-4 transition hover:-translate-y-0.5 hover:border-brand-500 hover:shadow-md dark:border-slate-800 dark:bg-slate-900"
          >
            <span className="block font-semibold group-hover:text-brand-700 dark:group-hover:text-brand-400">{s.name}</span>
            <span className="mt-1 block text-xs text-slate-500">
              Capital: {s.capitalName} · {s.cityCount} {s.cityCount === 1 ? 'city' : 'cities'}
            </span>
          </Link>
        ))}
      </div>
    </div>
  )
}

export function HomePage() {
  const { data: states, isLoading, isError } = useStates()
  const stateList = states?.filter((s) => s.type === 'STATE') ?? []
  const utList = states?.filter((s) => s.type === 'UT') ?? []
  const cityTotal = states?.reduce((sum, s) => sum + s.cityCount, 0) ?? 0

  return (
    <>
      <section className="relative overflow-hidden bg-gradient-to-br from-brand-700 via-brand-600 to-emerald-500 text-white">
        <div className="mx-auto max-w-7xl px-4 py-20 sm:px-6 sm:py-28">
          <p className="inline-flex rounded-full bg-white/15 px-3 py-1 text-xs font-semibold uppercase tracking-wider">
            Private parking, shared smartly
          </p>
          <h1 className="mt-4 max-w-3xl text-4xl font-extrabold tracking-tight sm:text-6xl">
            Parking, reserved before you arrive.
          </h1>
          <p className="mt-4 max-w-2xl text-lg text-brand-50">
            Book unused private parking near metro stations, office parks and markets — or earn money from the space you are not using.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <a href="#browse" className="rounded-lg bg-white px-5 py-3 font-semibold text-brand-700 shadow hover:bg-brand-50">
              Find parking
            </a>
            <Link to="/register?role=OWNER" className="rounded-lg border border-white/60 px-5 py-3 font-semibold hover:bg-white/10">
              List your space
            </Link>
          </div>
          <ul className="mt-10 flex flex-wrap gap-6 text-sm text-brand-50">
            {perks.map(({ Icon, label }) => (
              <li key={label} className="flex items-center gap-2"><Icon className="h-4 w-4" />{label}</li>
            ))}
          </ul>
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-4 py-16 sm:px-6">
        <h2 className="text-2xl font-bold tracking-tight">How it works</h2>
        <div className="mt-6 grid gap-6 md:grid-cols-3">
          {steps.map(({ Icon, title, body }, i) => (
            <div key={title} className="rounded-2xl border border-slate-200 p-6 dark:border-slate-800">
              <span className="grid h-10 w-10 place-items-center rounded-lg bg-brand-100 text-brand-700 dark:bg-brand-900/50 dark:text-brand-300">
                <Icon className="h-5 w-5" />
              </span>
              <p className="mt-4 text-xs font-semibold text-slate-400">STEP {i + 1}</p>
              <h3 className="mt-1 font-semibold">{title}</h3>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{body}</p>
            </div>
          ))}
        </div>
      </section>

      <section id="browse" className="scroll-mt-20 bg-slate-50 py-16 dark:bg-slate-900/40">
        <div className="mx-auto max-w-7xl space-y-8 px-4 sm:px-6">
          <div>
            <h2 className="text-2xl font-bold tracking-tight">Browse across India</h2>
            {states && (
              <p className="mt-1 text-sm text-slate-500">
                {stateList.length} states, {utList.length} union territories and {cityTotal} cities.
              </p>
            )}
          </div>
          {isLoading && <Spinner className="h-6 w-6 text-brand-600" />}
          {isError && <p className="text-sm text-red-600">Could not load locations. Please refresh.</p>}
          {states && (
            <>
              <StateGrid title="States" states={stateList} />
              <StateGrid title="Union territories" states={utList} />
            </>
          )}
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-4 py-16 sm:px-6">
        <div className="flex flex-col items-start justify-between gap-6 rounded-3xl bg-slate-900 p-8 text-white sm:flex-row sm:items-center sm:p-12 dark:bg-slate-800">
          <div>
            <h2 className="text-2xl font-bold">Have an empty parking spot?</h2>
            <p className="mt-2 text-slate-300">List it in minutes, set your own prices and get paid for every booking.</p>
          </div>
          <Link to="/register?role=OWNER" className="rounded-lg bg-brand-500 px-5 py-3 font-semibold hover:bg-brand-400">
            Start earning
          </Link>
        </div>
      </section>
    </>
  )
}
