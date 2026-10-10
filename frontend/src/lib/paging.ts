/**
 * Called during render: when a later page turns up empty (its last row was handled elsewhere) go back one page instead
 * of showing nothing. Data kept from the previous query (`isPlaceholderData`) says nothing about this page, so it never
 * triggers the step back.
 */
export function stepBackIfEmpty(page: number, setPage: (page: number) => void, content: unknown[] | undefined, isPlaceholderData = false) {
  if (content !== undefined && !isPlaceholderData && content.length === 0 && page > 0) setPage(page - 1)
}

/** Wraps a filter setter so changing the filter returns to the first page. */
export const withPageReset =
  (setPage: (page: number) => void) =>
  <T,>(set: (value: T) => void) =>
  (value: T) => {
    set(value)
    setPage(0)
  }
