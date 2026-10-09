import { useState } from 'react'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Spinner } from '../../components/ui/Spinner'
import { VehicleForm } from '../../components/vehicles/VehicleForm'
import { errorMessage } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS } from '../../lib/format'
import { useVehicleMutations, useVehicles, type VehicleDto } from '../../lib/vehicles'

function DeleteDialog({ vehicle, onClose }: { vehicle: VehicleDto; onClose: () => void }) {
  const { remove } = useVehicleMutations()
  const [formError, setFormError] = useState<string | null>(null)

  async function confirm() {
    setFormError(null)
    try {
      await remove.mutateAsync(vehicle.id)
      toast.success('Vehicle deleted')
      onClose()
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <Dialog open title={`Delete ${vehicle.plateNumber}?`} onClose={onClose} busy={remove.isPending}>
      <div className="space-y-4">
        <FormError message={formError} />
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Your past bookings keep this number plate. You can add the vehicle again later.
        </p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" disabled={remove.isPending} onClick={onClose}>Cancel</Button>
          <Button type="button" variant="danger" loading={remove.isPending} onClick={() => void confirm()}>Delete vehicle</Button>
        </div>
      </div>
    </Dialog>
  )
}

function VehicleRow({ vehicle, onEdit, onDelete }: { vehicle: VehicleDto; onEdit: () => void; onDelete: () => void }) {
  const { update } = useVehicleMutations()

  async function makeDefault() {
    try {
      await update.mutateAsync({
        id: vehicle.id,
        body: { type: vehicle.type, plateNumber: vehicle.plateNumber, makeModel: vehicle.makeModel ?? undefined, isDefault: true },
      })
      toast.success(`${vehicle.plateNumber} is now your default vehicle`)
    } catch (error) {
      toast.error(errorMessage(error))
    }
  }

  return (
    <li>
      <article
        aria-label={vehicle.plateNumber}
        className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 sm:flex-row sm:items-center sm:justify-between dark:border-slate-800 dark:bg-slate-900"
      >
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-lg font-semibold tracking-wide">{vehicle.plateNumber}</span>
            {vehicle.isDefault && (
              <span className="inline-flex items-center rounded-full bg-emerald-100 px-2.5 py-0.5 text-xs font-semibold text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
                Default
              </span>
            )}
          </div>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            {VEHICLE_TYPE_LABELS[vehicle.type]}
            {vehicle.makeModel && ` · ${vehicle.makeModel}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {!vehicle.isDefault && (
            <Button type="button" variant="secondary" loading={update.isPending} onClick={() => void makeDefault()}>Make default</Button>
          )}
          <Button type="button" variant="secondary" onClick={onEdit}>Edit</Button>
          <Button type="button" variant="ghost" onClick={onDelete}>Delete</Button>
        </div>
      </article>
    </li>
  )
}

export function VehiclesPage() {
  const { data: vehicles, error, isPending } = useVehicles()
  const [editing, setEditing] = useState<VehicleDto | null>(null)
  const [deleting, setDeleting] = useState<VehicleDto | null>(null)

  return (
    <div className="grid gap-8 lg:grid-cols-[1fr_22rem]">
      <section aria-labelledby="vehicles-heading" className="space-y-4">
        <h2 id="vehicles-heading" className="text-xl font-semibold">Your vehicles</h2>
        {isPending ? (
          <Spinner className="h-6 w-6 text-brand-600" />
        ) : error ? (
          <FormError message={errorMessage(error)} />
        ) : vehicles.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-slate-300 p-6 text-sm text-slate-600 dark:border-slate-700 dark:text-slate-400">
            Add your vehicle to start booking.
          </p>
        ) : (
          <ul className="space-y-3">
            {vehicles.map((v) => (
              <VehicleRow key={v.id} vehicle={v} onEdit={() => setEditing(v)} onDelete={() => setDeleting(v)} />
            ))}
          </ul>
        )}
      </section>

      <section
        aria-labelledby="add-vehicle-heading"
        className="h-fit rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900"
      >
        <h2 id="add-vehicle-heading" className="mb-4 text-lg font-semibold">Add a vehicle</h2>
        <VehicleForm submitLabel="Add vehicle" onSaved={() => toast.success('Vehicle added')} />
      </section>

      {editing && (
        <Dialog open title={`Edit ${editing.plateNumber}`} onClose={() => setEditing(null)}>
          <VehicleForm
            vehicle={editing}
            submitLabel="Save changes"
            onCancel={() => setEditing(null)}
            onSaved={() => {
              toast.success('Vehicle updated')
              setEditing(null)
            }}
          />
        </Dialog>
      )}
      {deleting && <DeleteDialog vehicle={deleting} onClose={() => setDeleting(null)} />}
    </div>
  )
}
