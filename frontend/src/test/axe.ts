import axe, { type Result } from 'axe-core'

/**
 * Runs axe-core over the whole document and returns the serious and critical violations only.
 *
 * jsdom has no layout and the test setup loads no CSS, so the rules that need rendered geometry (colour contrast,
 * scrollable regions) cannot give a real answer here; they are switched off. Contrast of the design tokens is
 * checked separately in `contrast.test.ts`.
 */
export async function seriousViolations(root: Element = document.documentElement): Promise<Result[]> {
  document.documentElement.lang = 'en' // index.html sets it; the test document does not load index.html
  const results = await axe.run(root, {
    resultTypes: ['violations'],
    rules: { 'color-contrast': { enabled: false }, 'scrollable-region-focusable': { enabled: false } },
  })
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
}

/** A readable one-line-per-problem summary, so a failing assertion names the rule and the offending markup. */
export function describeViolations(violations: Result[]): string[] {
  return violations.flatMap((v) => v.nodes.map((n) => `${v.id} (${v.impact}): ${n.html.slice(0, 160)}`))
}
