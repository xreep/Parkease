import { useEffect, useState } from 'react'

/** The current time (ms), re-read every `ms` while `active`, so deadlines and holds are noticed as they pass. */
export function useNow(active: boolean, ms: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const timer = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(timer)
  }, [active, ms])
  return now
}
