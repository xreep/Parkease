import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { TextField } from '../../components/ui/TextField'
import { errorMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { addBlock, deleteBlock, invalidateBlocks, useBlocks, useListing, type Block, type ListingDetail } from '../../lib/owner'
import { usePageTitle } from '../../lib/usePageTitle'

const schema = z
  .object({
    slotId: z.string(),
    startTime: z.string().min(1, 'Choose when the block starts'),
    endTime: z.string().min(1, 'Choose when the block ends'),
    reason: z.string().trim().max(200, 'Use at most 200 characters'),
  })
  .superRefine((v, ctx) => {
    if (v.startTime && v.endTime && new Date(v.endTime) <= new Date(v.startTime)) {
      ctx.addIssue({ code: 'custom', path: ['endTime'], message: "'Until' must be after 'From'" })
    }
  })
type Values = z.infer<typeof schema>

const EMPTY: Values = { slotId: '', startTime: '', endTime: '', reason: '' }

function BlockForm({ listing, onAdded }: { listing: ListingDetail; onAdded: () => Promise<void> }) {
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
  })

  async function onSubmit(values: Values) {
    setFormError(null)
    try {
      await addBlock(listing.id, {
        slotId: values.slotId === '' ? null : Number(values.slotId),
        startTime: new Date(values.startTime).toISOString(),
        endTime: new Date(values.endTime).toISOString(),
        ...(values.reason === '' ? {} : { reason: values.reason }),
      })
      reset(EMPTY)
      toast.success('Time blocked')
      await onAdded()
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <FormError message={formError} />
      <Select label="Applies to" {...register('slotId')}>
        <option value="">Whole listing</option>
        {listing.slots.map((s) => (
          <option key={s.id} value={String(s.id)}>{s.label}</option>
        ))}
      </Select>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="From" type="datetime-local" error={errors.startTime?.message} {...register('startTime')} />
        <TextField label="Until" type="datetime-local" error={errors.endTime?.message} {...register('endTime')} />
      </div>
      <TextField label="Reason (optional)" autoComplete="off" error={errors.reason?.message} {...register('reason')} />
      <Button type="submit" loading={isSubmitting}>Block time</Button>
    </form>
  )
}

function BlockList({ listingId, blocks, canRemove, onRemoved }: { listingId: number; blocks: Block[]; canRemove: boolean; onRemoved: () => Promise<void> }) {
  const [removingId, setRemovingId] = useState<number | null>(null)
  const sorted = useMemo(() => [...blocks].sort((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime) || a.id - b.id), [blocks])

  async function remove(block: Block) {
    setRemovingId(block.id)
    try {
      await deleteBlock(listingId, block.id)
      toast.success('Block removed')
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      await onRemoved()
      setRemovingId(null)
    }
  }

  if (sorted.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:text-slate-400 dark:border-slate-700">
        No blocked times. Your listing follows its weekly hours.
      </p>
    )
  }
  return (
    <ul aria-label="Upcoming blocked times" className="divide-y divide-slate-200 rounded-2xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
      {sorted.map((block, i) => (
        <li key={block.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
          <div className="min-w-0 flex-1 space-y-0.5">
            <p className="font-medium">{`${formatDateTime(block.startTime)} → ${formatDateTime(block.endTime)}`}</p>
            <p className="text-sm text-slate-500 dark:text-slate-400">{block.slotLabel ?? 'Whole listing'}</p>
            {block.reason && <p className="text-sm text-slate-600 dark:text-slate-400">{block.reason}</p>}
          </div>
          {canRemove && (
            <Button
              type="button"
              variant="ghost"
              className="px-3 py-1.5 text-red-600 dark:text-red-400"
              aria-label={`Remove block ${i + 1}`}
              loading={removingId === block.id}
              onClick={() => void remove(block)}
            >
              Remove
            </Button>
          )}
        </li>
      ))}
    </ul>
  )
}

function BlocksContent({ id }: { id: number }) {
  const queryClient = useQueryClient()
  const { data: listing, error, isPending } = useListing(id)
  const blocks = useBlocks(id)
  const refresh = () => invalidateBlocks(queryClient, id)

  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />

  return (
    <div className="space-y-6">
      <Link to="/owner/listings" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">← My listings</Link>
      <h2 className="text-xl font-semibold">{`Blocked times — ${listing.title}`}</h2>
      {listing.status === 'SUSPENDED' ? (
        <FormError message="This listing was suspended by ParkEase and can't be edited." />
      ) : (
        <BlockForm listing={listing} onAdded={refresh} />
      )}
      {blocks.isPending ? (
        <Spinner className="h-5 w-5 text-brand-600" />
      ) : blocks.error ? (
        <FormError message={errorMessage(blocks.error)} />
      ) : (
        <BlockList listingId={id} blocks={blocks.data} canRemove={listing.status !== 'SUSPENDED'} onRemoved={refresh} />
      )}
    </div>
  )
}

export function ListingBlocksPage() {
  usePageTitle('Block dates')
  const { id } = useParams()
  const numeric = Number(id)
  if (!Number.isInteger(numeric) || numeric <= 0) return <FormError message="Listing not found" />
  return <BlocksContent id={numeric} />
}
