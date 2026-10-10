import { zodResolver } from '@hookform/resolvers/zod'
import clsx from 'clsx'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '../../components/AuthCard'
import { Empty, Loading } from '../../components/admin/common'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Badge } from '../../components/ui/StatusBadge'
import { TextField } from '../../components/ui/TextField'
import {
  CITY_TIER_LABELS,
  createCity,
  createState,
  invalidateLocations,
  updateCity,
  updateState,
  type StateBody,
  useAdminCities,
  useAdminStates,
  type AdminCity,
  type AdminState,
  type CityBody,
  type CityTier,
} from '../../lib/adminManage'
import { errorMessage, toProblem } from '../../lib/errors'
import { usePageTitle } from '../../lib/usePageTitle'

const inRange = (min: number, max: number) => (v: string) => {
  if (v.trim() === '') return false
  const n = Number(v)
  return Number.isFinite(n) && n >= min && n <= max
}

const schema = z.object({
  name: z.string().trim().min(1, 'Enter the city name').min(2, 'Use at least 2 characters').max(100, 'Use at most 100 characters'),
  // The server only takes places inside India's bounding box.
  lat: z.string().refine(inRange(6, 38), 'Latitude must be between 6 and 38'),
  lng: z.string().refine(inRange(68, 98), 'Longitude must be between 68 and 98'),
  tier: z.enum(['1', '2', '3']),
  active: z.boolean(),
})
type Values = z.infer<typeof schema>

const FIELDS: readonly string[] = ['name', 'lat', 'lng', 'tier']
const TIERS = [1, 2, 3] as const

function CityForm({ state, city, onDone, onClose }: { state: AdminState; city: AdminCity | null; onDone: () => void; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: city
      ? { name: city.name, lat: String(city.lat), lng: String(city.lng), tier: String(city.tier) as Values['tier'], active: city.active }
      : { name: '', lat: '', lng: '', tier: '3', active: true },
  })

  async function submit(values: Values) {
    setFormError(null)
    const body: CityBody = {
      name: values.name,
      lat: Number(values.lat),
      lng: Number(values.lng),
      tier: Number(values.tier) as CityTier,
      active: values.active,
    }
    try {
      if (city) await updateCity(city.id, body)
      else await createCity(state.id, body)
      toast.success(`${body.name} ${city ? 'updated' : 'added'}`)
      await invalidateLocations(queryClient)
      onDone()
    } catch (error) {
      const problem = toProblem(error)
      const general: string[] = []
      for (const fe of problem.fieldErrors) {
        if (FIELDS.includes(fe.field)) setError(fe.field as keyof Values, { message: fe.message })
        else general.push(fe.message)
      }
      if (problem.fieldErrors.length === 0 || general.length > 0) setFormError(general.length > 0 ? general.join(' ') : problem.detail)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <TextField label="Name" error={errors.name?.message} {...register('name')} />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Latitude" type="number" step="any" inputMode="decimal" error={errors.lat?.message} {...register('lat')} />
        <TextField label="Longitude" type="number" step="any" inputMode="decimal" error={errors.lng?.message} {...register('lng')} />
      </div>
      <Select label="Tier" hint="Decides which price guideline owners see" error={errors.tier?.message} {...register('tier')}>
        {TIERS.map((t) => (
          <option key={t} value={t}>{CITY_TIER_LABELS[t]}</option>
        ))}
      </Select>
      <label className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
        <input type="checkbox" className="h-4 w-4 rounded border-slate-300" {...register('active')} />
        Active
      </label>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={isSubmitting}>{city ? 'Save changes' : 'Add city'}</Button>
      </div>
    </form>
  )
}

const stateSchema = z.object({
  name: z.string().trim().min(1, 'Enter the state name').min(2, 'Use at least 2 characters').max(100, 'Use at most 100 characters'),
  code: z.string().trim().regex(/^[A-Za-z]{2,5}$/, 'Use 2 to 5 letters'),
  type: z.enum(['STATE', 'UT']),
  capitalName: z.string().trim().min(1, 'Enter the capital').min(2, 'Use at least 2 characters').max(150, 'Use at most 150 characters'),
})
// Editing leaves the code alone, so its check must not block the form.
const stateEditSchema = stateSchema.extend({ code: z.string() })
type StateValues = z.infer<typeof stateSchema>

function StateForm({ state, onDone, onClose }: { state: AdminState | null; onDone: () => void; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<StateValues>({
    resolver: zodResolver(state ? stateEditSchema : stateSchema),
    defaultValues: state
      ? { name: state.name, code: state.code, type: state.type, capitalName: state.capitalName }
      : { name: '', code: '', type: 'STATE', capitalName: '' },
  })

  async function submit(values: StateValues) {
    setFormError(null)
    const body: StateBody = { name: values.name, type: values.type, capitalName: values.capitalName }
    try {
      if (state) await updateState(state.id, body)
      else await createState({ ...body, code: values.code.toUpperCase() })
      toast.success(`${body.name} ${state ? 'updated' : 'added'}`)
      await invalidateLocations(queryClient)
      onDone()
    } catch (error) {
      const problem = toProblem(error)
      const general: string[] = []
      for (const fe of problem.fieldErrors) {
        if (['name', 'code', 'type', 'capitalName'].includes(fe.field)) setError(fe.field as keyof StateValues, { message: fe.message })
        else general.push(fe.message)
      }
      if (problem.fieldErrors.length === 0 || general.length > 0) setFormError(general.length > 0 ? general.join(' ') : problem.detail)
    }
  }

  return (
    <form onSubmit={handleSubmit(submit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <TextField label="Name" error={errors.name?.message} {...register('name')} />
      {!state && <TextField label="Code" hint="2 to 5 letters, e.g. MH" autoComplete="off" error={errors.code?.message} {...register('code')} />}
      <Select label="Type" error={errors.type?.message} {...register('type')}>
        <option value="STATE">State</option>
        <option value="UT">Union territory</option>
      </Select>
      <TextField label="Capital" error={errors.capitalName?.message} {...register('capitalName')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" disabled={isSubmitting} onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={isSubmitting}>{state ? 'Save changes' : 'Add state'}</Button>
      </div>
    </form>
  )
}

function CitiesPanel({ state, onEditState }: { state: AdminState; onEditState: () => void }) {
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<{ city: AdminCity | null } | null>(null)
  const { data, error, isPending, isPlaceholderData } = useAdminCities(state.id, page)

  return (
    <section className="min-w-0 space-y-4" aria-label={`Cities in ${state.name}`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">{`Cities in ${state.name}`}</h3>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" aria-label={`Edit ${state.name}`} onClick={onEditState}>Edit state</Button>
          <Button type="button" onClick={() => setEditing({ city: null })}>Add city</Button>
        </div>
      </div>

      {isPending ? (
        <Loading />
      ) : error && !data ? (
        <FormError message={errorMessage(error)} />
      ) : data && data.content.length === 0 ? (
        <Empty>No cities in this state yet.</Empty>
      ) : data ? (
        <div className={clsx('space-y-4 transition-opacity', isPlaceholderData && 'opacity-60')} aria-busy={isPlaceholderData}>
          <div className="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{`Cities in ${state.name}`}</caption>
              <thead className="bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-400">
                <tr>
                  {['City', 'Slug', 'Coordinates', 'Tier', 'Status', 'Listings', ''].map((h, i) => (
                    <th key={i} scope="col" className="whitespace-nowrap px-3 py-2 font-medium">{h || <span className="sr-only">Actions</span>}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                {data.content.map((c) => (
                  <tr key={c.id}>
                    <th scope="row" className="px-3 py-2 font-medium">{c.name}</th>
                    <td className="px-3 py-2 text-slate-600 dark:text-slate-400">{c.slug}</td>
                    <td className="whitespace-nowrap px-3 py-2 tabular-nums">{`${c.lat}, ${c.lng}`}</td>
                    <td className="whitespace-nowrap px-3 py-2">{`Tier ${c.tier}`}</td>
                    <td className="px-3 py-2"><Badge tone={c.active ? 'emerald' : 'slate'}>{c.active ? 'Active' : 'Inactive'}</Badge></td>
                    <td className="px-3 py-2 tabular-nums">{c.listingsCount}</td>
                    <td className="px-3 py-2 text-right">
                      <Button type="button" variant="secondary" className="px-3 py-1.5" aria-label={`Edit ${c.name}`} onClick={() => setEditing({ city: c })}>Edit</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </div>
      ) : null}

      {editing && (
        <Dialog open title={editing.city ? `Edit ${editing.city.name}` : `Add a city to ${state.name}`} onClose={() => setEditing(null)}>
          <CityForm state={state} city={editing.city} onDone={() => setEditing(null)} onClose={() => setEditing(null)} />
        </Dialog>
      )}
    </section>
  )
}

export function AdminLocationsPage() {
  usePageTitle('Admin · Locations')
  const { data: states, error, isPending } = useAdminStates()
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [stateDialog, setStateDialog] = useState<{ state: AdminState | null } | null>(null)
  const selected = states?.find((s) => s.id === selectedId) ?? states?.[0]

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">Locations</h2>
        <Button type="button" variant="secondary" onClick={() => setStateDialog({ state: null })}>Add state</Button>
      </div>
      {isPending ? (
        <Loading />
      ) : error ? (
        <FormError message={errorMessage(error)} />
      ) : !selected ? (
        <Empty>No states yet.</Empty>
      ) : (
        <div className="grid gap-6 md:grid-cols-[16rem_1fr]">
          <ul aria-label="States" className="max-h-96 space-y-1 overflow-y-auto rounded-2xl border border-slate-200 p-2 dark:border-slate-800 md:max-h-[40rem]">
            {states.map((s) => {
              const current = s.id === selected.id
              return (
                <li key={s.id}>
                  <button
                    type="button"
                    aria-current={current ? 'true' : undefined}
                    onClick={() => setSelectedId(s.id)}
                    className={clsx(
                      'flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm transition',
                      current ? 'bg-brand-50 font-semibold text-brand-700 dark:bg-brand-950 dark:text-brand-300' : 'hover:bg-slate-100 dark:hover:bg-slate-800',
                    )}
                  >
                    <span>{s.name}</span>
                    <span className="text-xs text-slate-500 dark:text-slate-400">{s.citiesCount}</span>
                  </button>
                </li>
              )
            })}
          </ul>
          <CitiesPanel key={selected.id} state={selected} onEditState={() => setStateDialog({ state: selected })} />
        </div>
      )}
      {stateDialog && (
        <Dialog open title={stateDialog.state ? `Edit ${stateDialog.state.name}` : 'Add a state'} onClose={() => setStateDialog(null)}>
          <StateForm state={stateDialog.state} onDone={() => setStateDialog(null)} onClose={() => setStateDialog(null)} />
        </Dialog>
      )}
    </div>
  )
}
