import { useRef, useState, type DragEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import clsx from 'clsx'
import { ChevronLeft, ChevronRight, ImagePlus, Star, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { errorMessage, toProblem } from '../../lib/errors'
import { deletePhoto, reorderPhotos, uploadPhoto, useRefreshListing, type ListingDetail, type Photo } from '../../lib/owner'
import { Button } from '../ui/Button'

const MAX_PHOTOS = 8

type Props = { listingId: number; photos: Photo[]; readOnly?: boolean }

const tileButton =
  'inline-flex h-9 w-9 items-center justify-center rounded-lg bg-white/90 text-slate-800 shadow-sm transition hover:bg-white focus-visible:outline-2 focus-visible:outline-brand-600 disabled:cursor-not-allowed disabled:opacity-40'

function move<T>(items: T[], from: number, to: number): T[] {
  const next = items.slice()
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

export function PhotoManager({ listingId, photos, readOnly = false }: Props) {
  const queryClient = useQueryClient()
  const refresh = useRefreshListing(listingId)
  const key = ['owner', 'listing', listingId]
  const fileInput = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [uploading, setUploading] = useState<{ name: string; pct: number } | null>(null)
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const [dragOver, setDragOver] = useState<number | null>(null)
  const locked = busy || readOnly

  function writePhotos(next: Photo[]) {
    queryClient.setQueryData<ListingDetail>(key, (old) => (old ? { ...old, photos: next } : old))
  }

  async function reorder(from: number, to: number) {
    if (locked || from === to || to < 0 || to >= photos.length) return
    const previous = photos
    const next = move(photos, from, to).map((p, i) => ({ ...p, sortOrder: i }))
    writePhotos(next)
    setBusy(true)
    try {
      await reorderPhotos(listingId, next.map((p) => p.id))
    } catch (error) {
      writePhotos(previous)
      toast.error(errorMessage(error))
    } finally {
      setBusy(false)
      await refresh()
    }
  }

  async function remove(photo: Photo) {
    if (locked) return
    setBusy(true)
    try {
      await deletePhoto(listingId, photo.id)
      writePhotos(photos.filter((p) => p.id !== photo.id).map((p, i) => ({ ...p, sortOrder: i })))
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setBusy(false)
      await refresh()
    }
  }

  async function upload(files: File[]) {
    const room = MAX_PHOTOS - photos.length
    if (files.length > room) toast.error(`A listing can have at most ${MAX_PHOTOS} photos`)
    const batch = files.slice(0, Math.max(room, 0))
    if (batch.length === 0) return
    setBusy(true)
    try {
      for (const file of batch) {
        setUploading({ name: file.name, pct: 0 })
        try {
          const photo = await uploadPhoto(listingId, file, (pct) => setUploading({ name: file.name, pct }))
          queryClient.setQueryData<ListingDetail>(key, (old) => (old ? { ...old, photos: [...old.photos, photo] } : old))
        } catch (error) {
          toast.error(`${file.name}: ${errorMessage(error)}`)
          if (toProblem(error).code === 'PHOTO_LIMIT') break
        }
      }
    } finally {
      setUploading(null)
      setBusy(false)
      await refresh()
    }
  }

  function onDragStart(e: DragEvent, index: number) {
    if (locked) return e.preventDefault()
    setDragFrom(index)
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move'
      e.dataTransfer.setData('text/plain', String(index))
    }
  }

  function onDrop(e: DragEvent, index: number) {
    e.preventDefault()
    const from = dragFrom
    setDragFrom(null)
    setDragOver(null)
    if (from !== null) void reorder(from, index)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-slate-600 dark:text-slate-400">
          {photos.length} of {MAX_PHOTOS} photos. The first photo is the cover; drag tiles to reorder.
        </p>
        <Button
          type="button"
          variant="secondary"
          disabled={locked || photos.length >= MAX_PHOTOS}
          onClick={() => fileInput.current?.click()}
        >
          <ImagePlus aria-hidden className="h-4 w-4" />
          Add photos
        </Button>
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          aria-label="Photo files"
          accept="image/jpeg,image/png,image/webp"
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            e.target.value = ''
            void upload(files)
          }}
        />
      </div>

      {uploading && (
        <p className="text-sm text-slate-600 dark:text-slate-400" aria-live="polite">
          Uploading {uploading.name}… {uploading.pct}%
        </p>
      )}

      {photos.length === 0 ? (
        <p className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700">
          Add at least one photo of the entrance and the parking area.
        </p>
      ) : (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {photos.map((photo, i) => {
            const n = i + 1
            return (
              <li
                key={photo.id}
                aria-label={`Photo ${n}`}
                draggable={!locked}
                onDragStart={(e) => onDragStart(e, i)}
                onDragOver={(e) => {
                  e.preventDefault()
                  if (dragFrom !== null) setDragOver(i)
                }}
                onDragLeave={() => setDragOver((current) => (current === i ? null : current))}
                onDrop={(e) => onDrop(e, i)}
                onDragEnd={() => {
                  setDragFrom(null)
                  setDragOver(null)
                }}
                className={clsx(
                  'group relative aspect-[4/3] overflow-hidden rounded-xl border bg-slate-100 dark:bg-slate-800',
                  dragOver === i && dragFrom !== i ? 'border-brand-600 ring-2 ring-brand-600/40' : 'border-slate-200 dark:border-slate-700',
                  dragFrom === i && 'opacity-50',
                )}
              >
                <img src={photo.url} alt={`Parking photo ${n}`} draggable={false} className="h-full w-full object-cover" />
                {i === 0 && (
                  <span className="absolute left-2 top-2 rounded-full bg-brand-600 px-2 py-0.5 text-xs font-semibold text-white">
                    Cover
                  </span>
                )}
                <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-1 bg-gradient-to-t from-black/60 to-transparent p-2">
                  <div className="flex gap-1">
                    <button type="button" className={tileButton} aria-label={`Move photo ${n} left`} disabled={locked || i === 0} onClick={() => void reorder(i, i - 1)}>
                      <ChevronLeft aria-hidden className="h-4 w-4" />
                    </button>
                    <button type="button" className={tileButton} aria-label={`Move photo ${n} right`} disabled={locked || i === photos.length - 1} onClick={() => void reorder(i, i + 1)}>
                      <ChevronRight aria-hidden className="h-4 w-4" />
                    </button>
                    {i > 0 && (
                      <button type="button" className={tileButton} aria-label={`Make photo ${n} the cover`} disabled={locked} onClick={() => void reorder(i, 0)}>
                        <Star aria-hidden className="h-4 w-4" />
                      </button>
                    )}
                  </div>
                  <button type="button" className={clsx(tileButton, 'text-red-600')} aria-label={`Delete photo ${n}`} disabled={locked} onClick={() => void remove(photo)}>
                    <Trash2 aria-hidden className="h-4 w-4" />
                  </button>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
