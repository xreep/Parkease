import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { toast } from 'sonner'
import { FormError } from '../../../components/AuthCard'
import { LocationPicker, type LatLng } from '../../../components/owner/LocationPicker'
import { Button } from '../../../components/ui/Button'
import { Select } from '../../../components/ui/Select'
import { Spinner } from '../../../components/ui/Spinner'
import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import { toProblem, errorMessage } from '../../../lib/errors'
import { LISTING_TYPE_LABELS } from '../../../lib/format'
import { useStateDetail, useStates, type StateSummary } from '../../../lib/locations'
import { createListing, updateBasics, type BasicsBody, type ListingDetail, type ListingType } from '../../../lib/owner'
import { isReadOnly } from './types'

const INDIA_CENTER: LatLng = { lat: 22.5, lng: 79 }
const NOMINATIM = 'https://nominatim.openstreetmap.org/search'

const schema = z.object({
  stateSlug: z.string().min(1, 'Choose a state'),
  cityId: z.string().min(1, 'Choose a city'),
  title: z.string().trim().min(1, 'Enter a title').max(120, 'Use at most 120 characters'),
  listingType: z.enum(Object.keys(LISTING_TYPE_LABELS) as [ListingType, ...ListingType[]]),
  address: z.string().trim().min(1, 'Enter the address').max(300, 'Use at most 300 characters'),
  pincode: z.string().trim().regex(/^[1-9][0-9]{5}$/, 'Enter a valid 6-digit PIN code'),
  description: z.string().trim().max(2000, 'Use at most 2000 characters'),
  pin: z
    .object({ lat: z.number(), lng: z.number() })
    .nullable()
    .refine((v) => v !== null, 'Place the pin on the map'),
})
type Input = z.input<typeof schema>
type Values = z.output<typeof schema>
type FieldName = keyof Input

const FIELD_NAMES: readonly string[] = ['cityId', 'title', 'listingType', 'address', 'pincode', 'description']
const round6 = (n: number) => Math.round(n * 1e6) / 1e6

type Props = { listing?: ListingDetail; onSaved?: (next?: number) => void }

function LocationForm({ listing, onSaved, states }: Props & { states: StateSummary[] }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const readOnly = listing ? isReadOnly(listing) : false
  const [formError, setFormError] = useState<string | null>(null)
  const [finding, setFinding] = useState(false)
  const [focus, setFocus] = useState<LatLng | null>(listing ? { lat: listing.lat, lng: listing.lng } : null)

  const { register, handleSubmit, control, getValues, setValue, setError, formState: { errors, isSubmitting } } = useForm<Input, unknown, Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      stateSlug: states.find((s) => s.name === listing?.stateName)?.slug ?? '',
      cityId: listing ? String(listing.cityId) : '',
      title: listing?.title ?? '',
      listingType: listing?.listingType ?? 'OFFICE',
      address: listing?.address ?? '',
      pincode: listing?.pincode ?? '',
      description: listing?.description ?? '',
      pin: listing ? { lat: listing.lat, lng: listing.lng } : null,
    },
  })

  const stateSlug = useWatch({ control, name: 'stateSlug' })
  const cityId = useWatch({ control, name: 'cityId' })
  const pin = useWatch({ control, name: 'pin' })
  const { data: stateDetail } = useStateDetail(stateSlug)
  const cities = stateDetail?.cities ?? []
  const city = cities.find((c) => String(c.id) === cityId)
  const center = focus ?? (city ? { lat: city.lat, lng: city.lng } : INDIA_CENTER)
  const zoom = focus || city ? 13 : 5

  function placePin(pos: LatLng) {
    if (readOnly) return
    setValue('pin', { lat: round6(pos.lat), lng: round6(pos.lng) }, { shouldValidate: true, shouldDirty: true })
  }

  async function findAddress() {
    const address = getValues('address').trim()
    if (!address || !city) {
      toast.error('Enter the address and choose a city first')
      return
    }
    setFinding(true)
    try {
      const q = `${address}, ${city.name}, ${stateDetail?.name ?? ''}`
      const res = await fetch(`${NOMINATIM}?${new URLSearchParams({ format: 'json', limit: '1', countrycodes: 'in', q })}`)
      if (!res.ok) throw new Error('lookup failed')
      const results = (await res.json()) as { lat: string; lon: string }[]
      const hit = results[0] ? { lat: Number(results[0].lat), lng: Number(results[0].lon) } : null
      if (!hit || !Number.isFinite(hit.lat) || !Number.isFinite(hit.lng)) {
        toast.error('Address not found — place the pin manually')
        return
      }
      placePin(hit)
      setFocus(hit)
    } catch {
      toast.error('Could not look up the address — place the pin manually')
    } finally {
      setFinding(false)
    }
  }

  async function onSubmit(values: Values) {
    setFormError(null)
    const body: BasicsBody = {
      cityId: Number(values.cityId),
      title: values.title,
      description: values.description,
      address: values.address,
      pincode: values.pincode,
      lat: values.pin!.lat,
      lng: values.pin!.lng,
      listingType: values.listingType,
    }
    try {
      if (listing) {
        await updateBasics(listing.id, body)
        onSaved?.(2)
      } else {
        const created = await createListing(body)
        queryClient.setQueryData(['owner', 'listing', created.id], created)
        void queryClient.invalidateQueries({ queryKey: ['owner', 'listings'] })
        navigate(`/owner/listings/${created.id}/edit?step=2`, { replace: true })
      }
    } catch (error) {
      const problem = toProblem(error)
      let mapped = false
      for (const fe of problem.fieldErrors) {
        const name = fe.field === 'lat' || fe.field === 'lng' ? 'pin' : fe.field
        if (name === 'pin' || FIELD_NAMES.includes(name)) {
          setError(name as FieldName, { message: fe.message })
          mapped = true
        }
      }
      if (!mapped) setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-5">
      <fieldset disabled={readOnly} className="space-y-5">
        <FormError message={formError} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Select
            label="State"
            error={errors.stateSlug?.message}
            {...register('stateSlug', {
              onChange: () => {
                setValue('cityId', '')
                setFocus(null)
              },
            })}
          >
            <option value="">Select a state</option>
            {states.map((s) => (
              <option key={s.id} value={s.slug}>{s.name}</option>
            ))}
          </Select>
          <Select
            label="City"
            error={errors.cityId?.message}
            value={cityId}
            {...register('cityId', {
              onChange: (e: { target: { value: string } }) => {
                const picked = cities.find((c) => String(c.id) === e.target.value)
                if (picked) setFocus({ lat: picked.lat, lng: picked.lng })
              },
            })}
          >
            <option value="">{stateSlug && !stateDetail ? 'Loading cities…' : 'Select a city'}</option>
            {cities.map((c) => (
              <option key={c.id} value={String(c.id)}>{c.name}</option>
            ))}
          </Select>
        </div>
        <TextField label="Listing title" error={errors.title?.message} {...register('title')} />
        <Select label="Parking type" error={errors.listingType?.message} {...register('listingType')}>
          {Object.entries(LISTING_TYPE_LABELS).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField label="Address" className="sm:col-span-2" autoComplete="street-address" error={errors.address?.message} {...register('address')} />
          <TextField label="PIN code" inputMode="numeric" autoComplete="postal-code" error={errors.pincode?.message} {...register('pincode')} />
        </div>
        <TextArea label="Description (optional)" rows={4} error={errors.description?.message} {...register('description')} />

        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-medium text-slate-700 dark:text-slate-300">Pin the entrance on the map</p>
            <Button type="button" variant="secondary" loading={finding} onClick={() => void findAddress()}>
              Find address on map
            </Button>
          </div>
          <LocationPicker value={pin} center={center} zoom={zoom} onChange={placePin} />
          {errors.pin?.message && <p className="text-sm text-red-600 dark:text-red-400">{errors.pin.message}</p>}
        </div>

        <div className="flex justify-end">
          <Button type="submit" loading={isSubmitting}>Save and continue</Button>
        </div>
      </fieldset>
    </form>
  )
}

export function LocationStep({ listing, onSaved }: Props) {
  const { data: states, error, isPending } = useStates()
  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />
  return <LocationForm listing={listing} onSaved={onSaved} states={states} />
}
