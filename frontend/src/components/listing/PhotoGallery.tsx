import { useEffect, useState } from 'react'
import { Car, ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'

type Photo = { id: number; url: string }

const MAX_THUMBNAILS = 4

function Lightbox({ photos, title, index, onIndex, onClose }: {
  photos: Photo[]
  title: string
  index: number
  onIndex: (i: number) => void
  onClose: () => void
}) {
  const count = photos.length
  const step = (by: number) => onIndex((index + by + count) % count)

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'ArrowLeft') onIndex((index - 1 + count) % count)
      else if (e.key === 'ArrowRight') onIndex((index + 1) % count)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [index, count, onIndex])

  return (
    <Dialog open title={`${title} photos`} onClose={onClose} wide>
      <div className="space-y-3">
        <img
          src={photos[index].url}
          alt={`${title} photo ${index + 1}`}
          className="max-h-[65vh] w-full rounded-lg bg-slate-100 object-contain dark:bg-slate-800"
        />
        <div className="flex items-center justify-between gap-2">
          {count > 1 ? (
            <Button type="button" variant="secondary" onClick={() => step(-1)}>
              <ChevronLeft aria-hidden className="h-4 w-4" />
              Previous
            </Button>
          ) : (
            <span />
          )}
          <span className="text-sm text-slate-600 dark:text-slate-400">{`${index + 1} of ${count}`}</span>
          {count > 1 ? (
            <Button type="button" variant="secondary" onClick={() => step(1)}>
              Next
              <ChevronRight aria-hidden className="h-4 w-4" />
            </Button>
          ) : (
            <span />
          )}
        </div>
        <div className="flex justify-end">
          <Button type="button" variant="ghost" onClick={onClose}>Close</Button>
        </div>
      </div>
    </Dialog>
  )
}

/** A large cover photo with thumbnails; any photo opens a lightbox with Previous/Next. */
export function PhotoGallery({ photos, title }: { photos: Photo[]; title: string }) {
  const [open, setOpen] = useState<number | null>(null)

  if (photos.length === 0) {
    return (
      <div className="grid h-56 place-items-center rounded-2xl bg-slate-100 text-slate-500 sm:h-72 dark:bg-slate-800">
        <div className="flex flex-col items-center gap-2">
          <Car aria-hidden className="h-10 w-10" />
          <p className="text-sm">No photos yet</p>
        </div>
      </div>
    )
  }

  const thumbs = photos.slice(1, 1 + MAX_THUMBNAILS)
  const hidden = photos.length - 1 - thumbs.length

  return (
    <>
      <div className="grid gap-2 sm:grid-cols-[minmax(0,3fr)_minmax(0,1fr)]">
        <button
          type="button"
          aria-label="Open photo 1"
          onClick={() => setOpen(0)}
          className="block h-56 overflow-hidden rounded-2xl bg-slate-100 sm:h-80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600 dark:bg-slate-800"
        >
          <img src={photos[0].url} alt={`${title} photo 1`} className="h-full w-full object-cover" />
        </button>
        {thumbs.length > 0 && (
          <ul className="grid grid-cols-4 gap-2 sm:h-80 sm:grid-cols-1 sm:grid-rows-4">
            {thumbs.map((p, i) => {
              const n = i + 2
              const more = i === thumbs.length - 1 && hidden > 0
              return (
                <li key={p.id} className="relative min-h-0">
                  <button
                    type="button"
                    aria-label={`View photo ${n}`}
                    onClick={() => setOpen(n - 1)}
                    className="block h-16 w-full overflow-hidden rounded-xl bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600 sm:h-full dark:bg-slate-800"
                  >
                    <img src={p.url} alt="" loading="lazy" className="h-full w-full object-cover" />
                    {more && (
                      <span className="absolute inset-0 grid place-items-center rounded-xl bg-slate-950/55 text-sm font-semibold text-white">
                        {`+${hidden}`}
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
      {open !== null && (
        <Lightbox photos={photos} title={title} index={open} onIndex={setOpen} onClose={() => setOpen(null)} />
      )}
    </>
  )
}
