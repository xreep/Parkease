import { Search } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button } from '../ui/Button'
import { TextField } from '../ui/TextField'

/** A text search that runs when submitted (Enter or the button), so typing doesn't fire a request per key. */
export function SearchBox({
  label,
  placeholder,
  onSearch,
  className,
}: {
  label: string
  placeholder?: string
  onSearch: (query: string) => void
  className?: string
}) {
  const [draft, setDraft] = useState('')
  function submit(e: FormEvent) {
    e.preventDefault()
    onSearch(draft.trim())
  }
  return (
    <form role="search" onSubmit={submit} className={className}>
      <div className="flex items-end gap-2">
        <TextField className="min-w-0 flex-1" label={label} value={draft} placeholder={placeholder} onChange={(e) => setDraft(e.target.value)} />
        <Button type="submit" variant="secondary">
          <Search aria-hidden className="h-4 w-4" />
          Search
        </Button>
      </div>
    </form>
  )
}
