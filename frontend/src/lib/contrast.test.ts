/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { FALLBACK_STYLE, STATUS_STYLE } from '../components/owner/calendarStyle'

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

const darkPage = { 950: '#020617', 900: '#0f172a', 800: '#1e293b' }

describe('colour contrast of the design tokens (WCAG AA, 4.5:1 for text)', () => {
  it.each([
    ['brand-700 link on white (light mode)', token('brand-700'), slate.white],
    ['brand-400 link on slate-950 (dark mode)', token('brand-400'), slate[950]],
    ['slate-500 secondary text on white', slate[500], slate.white],
    ['slate-600 body text on white', slate[600], slate.white],
    ['slate-400 captions on slate-950 (dark mode)', slate[400], darkPage[950]],
    ['slate-400 captions on slate-900 cards (dark mode)', slate[400], darkPage[900]],
    ['slate-400 captions on slate-800 (dark mode)', slate[400], darkPage[800]],
    ['white on brand-700 (primary buttons, navbar sign-up)', slate.white, token('brand-700')],
    ['white on brand-700 hover state (brand-800)', slate.white, token('brand-800')],
    ['slate-950 text on brand-400 (button on the dark call-to-action panel)', slate[950], token('brand-400')],
    ['slate-950 text on brand-300 (its hover state)', slate[950], token('brand-300')],
  ])('%s', (_label, fg, bg) => {
    expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5)
  })
})

/** Tailwind's default palette entries the calendar uses (hex), keyed as in the class names. */
const palette: Record<string, string> = {
  white: '#ffffff',
  'slate-300': '#cbd5e1', 'slate-400': '#94a3b8', 'slate-600': '#475569', 'slate-900': '#0f172a',
  'emerald-300': '#6ee7b7', 'emerald-700': '#047857', 'emerald-950': '#022c22',
  'sky-300': '#7dd3fc', 'sky-700': '#0369a1', 'sky-950': '#082f49',
  'amber-300': '#fcd34d', 'amber-500': '#f59e0b',
}

function colours(classes: string, prefix: '' | 'dark:') {
  const pick = (kind: 'bg' | 'text') => {
    const m = classes.split(/\s+/).find((c) => c.startsWith(`${prefix}${kind}-`))
    const name = m?.slice(prefix.length + kind.length + 1)
    return name ? palette[name] ?? null : null
  }
  return { bg: pick('bg'), text: pick('text') }
}

describe('owner calendar booking bars (white or dark text on the bar colour, light and dark mode)', () => {
  const styles = { ...STATUS_STYLE, FALLBACK: FALLBACK_STYLE }
  it.each(Object.entries(styles))('%s', (_status, classes) => {
    const light = colours(classes as string, '')
    expect(light.bg, `${classes}: light background`).not.toBeNull()
    expect(light.text, `${classes}: light text`).not.toBeNull()
    expect(ratio(light.text!, light.bg!)).toBeGreaterThanOrEqual(4.5)
    // Dark mode: an explicit pair, or none (the light pair is kept).
    const dark = colours(classes as string, 'dark:')
    if (dark.bg || dark.text) {
      expect(dark.bg, `${classes}: dark background`).not.toBeNull()
      expect(dark.text, `${classes}: dark text`).not.toBeNull()
      expect(ratio(dark.text!, dark.bg!)).toBeGreaterThanOrEqual(4.5)
    }
  })
})

describe('muted text carries its own dark-mode colour', () => {
  // slate-500 is 4.25:1 on the dark page, under AA, so every class list that sets it must also set a dark text colour.
  const walk = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? walk(join(dir, e.name)) : /\.tsx$/.test(e.name) && !/\.test\./.test(e.name) ? [join(dir, e.name)] : [],
    )

  it('no class list uses text-slate-500 without a dark:text-* colour', () => {
    const offenders: string[] = []
    for (const file of walk(resolve(process.cwd(), 'src'))) {
      for (const [, literal] of readFileSync(file, 'utf8').matchAll(/(["'`])((?:(?!\1)[^\n])*?\btext-slate-500\b(?:(?!\1)[^\n])*?)\1/g)) {
        if (!/(^|\s)text-slate-500(\s|$)/.test(literal)) continue
        if (!/dark:text-/.test(literal)) offenders.push(`${file.replace(process.cwd() + '/', '')}: ${literal.slice(0, 80)}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('does not restyle slate-500 globally in CSS', () => {
    expect(css).not.toMatch(/--color-slate-500/)
  })
})
