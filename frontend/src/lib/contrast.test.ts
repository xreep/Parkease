/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// jsdom cannot measure rendered colour contrast, so the pairings the design relies on are checked from the tokens.
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

function token(name: string): string {
  const m = css.match(new RegExp(`--color-${name}:\\s*(#[0-9a-fA-F]{6})`))
  if (!m) throw new Error(`token ${name} missing`)
  return m[1]
}

// Tailwind's default slate palette (unchanged in this project).
const slate = { 400: '#94a3b8', 500: '#64748b', 600: '#475569', 900: '#0f172a', 950: '#020617', white: '#ffffff' }

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const darkSlate500 = css.match(/\.dark\s*\{[^}]*--color-slate-500:\s*(#[0-9a-fA-F]{6})/)?.[1] ?? slate[500]
const darkPage = { 950: '#020617', 900: '#0f172a', 800: '#1e293b' }

describe('colour contrast of the design tokens (WCAG AA, 4.5:1 for text)', () => {
  it.each([
    ['brand-700 link on white (light mode)', token('brand-700'), slate.white],
    ['brand-400 link on slate-950 (dark mode)', token('brand-400'), slate[950]],
    ['slate-500 secondary text on white', slate[500], slate.white],
    ['slate-600 body text on white', slate[600], slate.white],
    ['slate-400 secondary text on slate-950 (dark mode)', slate[400], slate[950]],
    ['slate-500 captions on slate-950 (dark mode override)', darkSlate500, darkPage[950]],
    ['slate-500 captions on slate-900 cards (dark mode override)', darkSlate500, darkPage[900]],
    ['slate-500 captions on slate-800 (dark mode override)', darkSlate500, darkPage[800]],
    ['white on brand-700 (primary buttons, navbar sign-up)', slate.white, token('brand-700')],
    ['white on brand-700 hover state (brand-800)', slate.white, token('brand-800')],
    ['slate-950 text on brand-400 (button on the dark call-to-action panel)', slate[950], token('brand-400')],
    ['slate-950 text on brand-300 (its hover state)', slate[950], token('brand-300')],
  ])('%s', (_label, fg, bg) => {
    expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5)
  })
})
