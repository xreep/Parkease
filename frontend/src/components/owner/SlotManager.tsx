import { useState } from 'react'
import { useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { toProblem } from '../../lib/errors'
import { SLOT_SIZE_LABELS, VEHICLE_TYPE_LABELS } from '../../lib/format'
import {
  addSlot,
  addSlotsBulk,
  deleteSlot,
  updateSlot,
  useRefreshListing,
  type Slot,
  type SlotBody,
  type SlotSize,
  type VehicleType,
} from '../../lib/owner'
import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Select } from '../ui/Select'
import { TextField } from '../ui/TextField'

const vehicleTypes = Object.keys(VEHICLE_TYPE_LABELS) as [VehicleType, ...VehicleType[]]
const sizes = Object.keys(SLOT_SIZE_LABELS) as [SlotSize, ...SlotSize[]]

const labelSchema = z
  .string()
  .trim()
  .min(1, 'Enter a slot label')
  .max(20, 'Use at most 20 characters')
  .regex(/^[A-Za-z0-9][A-Za-z0-9 -]*$/, 'Use letters, numbers, spaces and hyphens only')

const slotSchema = z.object({
  label: labelSchema,
  vehicleType: z.enum(vehicleTypes),
  size: z.enum(sizes),
})
type SlotValues = z.infer<typeof slotSchema>

const intField = (min: number, max: number, message: string) =>
  z.number({ error: 'Enter a number' }).int(message).min(min, message).max(max, message)

const bulkSchema = z.object({
  prefix: z
    .string()
    .trim()
    .min(1, 'Enter a prefix')
    .max(10, 'Use at most 10 characters')
    .regex(/^[A-Za-z0-9-]+$/, 'Use letters, numbers and hyphens only'),
  startNumber: intField(1, 999, 'Start between 1 and 999'),
  count: intField(1, 50, 'Add between 1 and 50 slots at a time'),
  vehicleType: z.enum(vehicleTypes),
  size: z.enum(sizes),
})
type BulkValues = z.infer<typeof bulkSchema>

/** Mirrors the server: numbers are zero-padded to 2 digits, or 3 when the last number reaches 100. */
function bulkPreview(prefix: string, start: number, count: number): string | null {
  if (!prefix.trim() || !Number.isInteger(start) || !Number.isInteger(count) || start < 1 || count < 1) return null
  const last = start + count - 1
  const width = last >= 100 ? 3 : 2
  const label = (n: number) => `${prefix.trim()}${String(n).padStart(width, '0')}`
  return count === 1 ? `Creates ${label(start)}` : `Creates ${label(start)} to ${label(last)}`
}

type TypeAndSizeProps = {
  vehicleType: UseFormRegisterReturn
  size: UseFormRegisterReturn
  errors: { vehicleType?: { message?: string }; size?: { message?: string } }
}

function TypeAndSize({ vehicleType, size, errors }: TypeAndSizeProps) {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Select label="Vehicle type" error={errors.vehicleType?.message} {...vehicleType}>
        {Object.entries(VEHICLE_TYPE_LABELS).map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
      </Select>
      <Select label="Size" error={errors.size?.message} {...size}>
        {Object.entries(SLOT_SIZE_LABELS).map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
      </Select>
    </div>
  )
}

function AddOneForm({ listingId, onChanged }: { listingId: number; onChanged: () => Promise<void> }) {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } = useForm<SlotValues>({
    resolver: zodResolver(slotSchema),
    defaultValues: { label: '', vehicleType: 'FOUR_WHEELER', size: 'MEDIUM' },
  })

  async function onSubmit(values: SlotValues) {
    setFormError(null)
    try {
      await addSlot(listingId, values)
      reset({ ...values, label: '' })
      await onChanged()
    } catch (error) {
      const problem = toProblem(error)
      const label = problem.fieldErrors.find((f) => f.field === 'label')
      if (label) setError('label', { message: label.message })
      else setFormError(problem.detail)
    }
  }

  return (
    <form aria-labelledby="add-one-slot" onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <h3 id="add-one-slot" className="font-semibold">Add one slot</h3>
      <FormError message={formError} />
      <TextField label="Slot label" autoComplete="off" error={errors.label?.message} {...register('label')} />
      <TypeAndSize vehicleType={register('vehicleType')} size={register('size')} errors={errors} />
      <Button type="submit" loading={isSubmitting}>Add slot</Button>
    </form>
  )
}

function AddManyForm({ listingId, onChanged }: { listingId: number; onChanged: () => Promise<void> }) {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, formState: { errors, isSubmitting } } = useForm<BulkValues>({
    resolver: zodResolver(bulkSchema),
    defaultValues: { prefix: 'A-', startNumber: 1, count: 10, vehicleType: 'FOUR_WHEELER', size: 'MEDIUM' },
  })
  const [prefix, startNumber, count] = useWatch({ control, name: ['prefix', 'startNumber', 'count'] })
  const preview = bulkPreview(prefix, startNumber, count)

  async function onSubmit(values: BulkValues) {
    setFormError(null)
    try {
      const created = await addSlotsBulk(listingId, { ...values, prefix: values.prefix.trim() })
      toast.success(`Added ${created.length || values.count} slots`)
      await onChanged()
    } catch (error) {
      setFormError(toProblem(error).detail)
    }
  }

  return (
    <form aria-labelledby="add-many-slots" onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <h3 id="add-many-slots" className="font-semibold">Add many slots</h3>
      <FormError message={formError} />
      <div className="grid gap-4 sm:grid-cols-3">
        <TextField label="Label prefix" autoComplete="off" error={errors.prefix?.message} {...register('prefix')} />
        <TextField label="Start number" type="number" inputMode="numeric" error={errors.startNumber?.message} {...register('startNumber', { valueAsNumber: true })} />
        <TextField label="How many" type="number" inputMode="numeric" error={errors.count?.message} {...register('count', { valueAsNumber: true })} />
      </div>
      <TypeAndSize vehicleType={register('vehicleType')} size={register('size')} errors={errors} />
      <p className="min-h-5 text-sm text-slate-600 dark:text-slate-400" aria-live="polite">{preview}</p>
      <Button type="submit" loading={isSubmitting}>Add slots</Button>
    </form>
  )
}

function EditSlotDialog({ listingId, slot, onClose, onChanged }: { listingId: number; slot: Slot; onClose: () => void; onChanged: () => Promise<void> }) {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<SlotValues>({
    resolver: zodResolver(slotSchema),
    defaultValues: { label: slot.label, vehicleType: slot.vehicleType, size: slot.size },
  })

  async function onSubmit(values: SlotValues) {
    setFormError(null)
    try {
      await updateSlot(listingId, slot.id, { ...values, active: slot.active })
      await onChanged()
      onClose()
    } catch (error) {
      setFormError(toProblem(error).detail)
    }
  }

  return (
    <Dialog open title={`Edit slot ${slot.label}`} onClose={onClose} busy={isSubmitting}>
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <FormError message={formError} />
        <TextField label="Slot label" autoComplete="off" error={errors.label?.message} {...register('label')} />
        <TypeAndSize vehicleType={register('vehicleType')} size={register('size')} errors={errors} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={isSubmitting}>Save changes</Button>
        </div>
      </form>
    </Dialog>
  )
}

function DeleteSlotDialog({ listingId, slot, onClose, onChanged }: { listingId: number; slot: Slot; onClose: () => void; onChanged: () => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  async function confirm() {
    setBusy(true)
    setFormError(null)
    try {
      await deleteSlot(listingId, slot.id)
      await onChanged()
      onClose()
    } catch (error) {
      setFormError(toProblem(error).detail)
      setBusy(false)
    }
  }

  return (
    <Dialog open title={`Delete slot ${slot.label}?`} onClose={onClose} busy={busy}>
      <div className="space-y-4">
        <FormError message={formError} />
        <p className="text-sm text-slate-600 dark:text-slate-400">Drivers will no longer be able to book this slot.</p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="button" variant="danger" loading={busy} onClick={() => void confirm()}>Delete slot</Button>
        </div>
      </div>
    </Dialog>
  )
}

type Props = { listingId: number; slots: Slot[]; readOnly?: boolean }

export function SlotManager({ listingId, slots, readOnly = false }: Props) {
  const refresh = useRefreshListing(listingId)
  const [editing, setEditing] = useState<Slot | null>(null)
  const [deleting, setDeleting] = useState<Slot | null>(null)
  const [togglingId, setTogglingId] = useState<number | null>(null)

  const cars = slots.filter((s) => s.vehicleType === 'FOUR_WHEELER').length
  const twoWheelers = slots.length - cars

  async function toggle(slot: Slot) {
    const body: SlotBody = { label: slot.label, vehicleType: slot.vehicleType, size: slot.size, active: !slot.active }
    setTogglingId(slot.id)
    try {
      await updateSlot(listingId, slot.id, body)
    } catch (error) {
      toast.error(toProblem(error).detail)
    } finally {
      setTogglingId(null)
      await refresh()
    }
  }

  return (
    <div className="space-y-6">
      <p className="text-sm font-medium text-slate-700 dark:text-slate-300">
        {slots.length} {slots.length === 1 ? 'slot' : 'slots'} · {cars} car · {twoWheelers} two-wheeler
      </p>

      {slots.length === 0 ? (
        <p className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700">
          Add the individual bays drivers can book.
        </p>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-2xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
          {slots.map((slot) => (
            <li key={slot.id} aria-label={`Slot ${slot.label}`} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{slot.label}</p>
                <p className="text-sm text-slate-500">
                  {VEHICLE_TYPE_LABELS[slot.vehicleType]} · {SLOT_SIZE_LABELS[slot.size]}
                </p>
              </div>
              <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-slate-300"
                  aria-label={`Slot ${slot.label} active`}
                  checked={slot.active}
                  disabled={readOnly || togglingId === slot.id}
                  onChange={() => void toggle(slot)}
                />
                Active
              </label>
              <div className="flex gap-2">
                <Button type="button" variant="secondary" className="px-3 py-1.5" disabled={readOnly} onClick={() => setEditing(slot)}>Edit</Button>
                <Button type="button" variant="ghost" className="px-3 py-1.5 text-red-600 dark:text-red-400" disabled={readOnly} onClick={() => setDeleting(slot)}>Delete</Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <fieldset disabled={readOnly} className="grid gap-4 lg:grid-cols-2">
        <AddOneForm listingId={listingId} onChanged={refresh} />
        <AddManyForm listingId={listingId} onChanged={refresh} />
      </fieldset>

      {editing && (
        <EditSlotDialog listingId={listingId} slot={editing} onClose={() => setEditing(null)} onChanged={refresh} />
      )}
      {deleting && (
        <DeleteSlotDialog listingId={listingId} slot={deleting} onClose={() => setDeleting(null)} onChanged={refresh} />
      )}
    </div>
  )
}
