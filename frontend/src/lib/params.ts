/** A positive integer route id, or undefined for anything else. */
export const parseId = (id: string | undefined): number | undefined => {
  const n = Number(id)
  return Number.isInteger(n) && n > 0 ? n : undefined
}
