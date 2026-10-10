/// <reference types="node" />
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const at = (path: string) => resolve(process.cwd(), path)
const config = JSON.parse(readFileSync(at('vercel.json'), 'utf8')) as {
  rewrites: { source: string; destination: string }[]
  headers: { source: string; headers: { key: string; value: string }[] }[]
}
const html = readFileSync(at('index.html'), 'utf8')

const allHeaders = Object.fromEntries(config.headers.find((h) => h.source === '/(.*)')!.headers.map((h) => [h.key, h.value]))
const csp = Object.fromEntries(
  allHeaders['Content-Security-Policy'].split(';').map((d) => d.trim().split(/\s+/)).filter((d) => d[0]).map(([name, ...values]) => [name, values]),
) as Record<string, string[]>

describe('vercel.json', () => {
  it('rewrites every path to the single-page app (files on disk are served first)', () => {
    expect(config.rewrites).toContainEqual({ source: '/(.*)', destination: '/index.html' })
  })

  it('sends the security headers on every route', () => {
    expect(allHeaders['X-Content-Type-Options']).toBe('nosniff')
    expect(allHeaders['X-Frame-Options']).toBe('DENY')
    expect(allHeaders['Referrer-Policy']).toBe('strict-origin-when-cross-origin')
    expect(allHeaders['Strict-Transport-Security']).toMatch(/max-age=\d{7,}/)
    expect(allHeaders['Permissions-Policy']).toMatch(/camera=\(\)/)
  })

  it('caches the fingerprinted assets for a year', () => {
    const assets = config.headers.find((h) => h.source === '/assets/(.*)')
    expect(assets?.headers).toContainEqual({ key: 'Cache-Control', value: 'public, max-age=31536000, immutable' })
  })

  describe('Content-Security-Policy', () => {
    it('is locked down by default', () => {
      expect(csp['default-src']).toEqual(["'self'"])
      expect(csp['object-src']).toEqual(["'none'"])
      expect(csp['base-uri']).toEqual(["'self'"])
      expect(csp['frame-ancestors']).toEqual(["'none'"])
      expect(csp['script-src']).not.toContain("'unsafe-inline'")
      expect(csp['script-src']).not.toContain("'unsafe-eval'")
    })

    it('allows the inline theme script in index.html by its hash, so a changed script cannot ship with a stale policy', () => {
      const script = /<script>([\s\S]*?)<\/script>/.exec(html)![1]
      const hash = `'sha256-${createHash('sha256').update(script).digest('base64')}'`
      expect(csp['script-src']).toContain(hash)
    })

    it('lets Razorpay Checkout load, frame and report', () => {
      expect(csp['script-src']).toContain('https://checkout.razorpay.com')
      expect(csp['frame-src']).toEqual(expect.arrayContaining(['https://api.razorpay.com', 'https://checkout.razorpay.com']))
      expect(csp['connect-src']).toEqual(expect.arrayContaining(['https://api.razorpay.com', 'https://lumberjack.razorpay.com']))
    })

    it('lets the page show Cloudinary photos, OpenStreetMap tiles and the backend\'s own uploads', () => {
      expect(csp['img-src']).toEqual(expect.arrayContaining(["'self'", 'data:', 'blob:', 'https://res.cloudinary.com', 'https://*.tile.openstreetmap.org']))
    })

    it('lets the page call the API on Render and the Nominatim lookup, and nothing else', () => {
      expect(csp['connect-src']).toEqual(
        expect.arrayContaining(["'self'", 'https://*.onrender.com', 'https://nominatim.openstreetmap.org']),
      )
      expect(csp['connect-src']).not.toContain('https:')
      expect(csp['connect-src']).not.toContain('*')
    })

    it('allows the Google Fonts the page uses', () => {
      expect(csp['style-src']).toEqual(expect.arrayContaining(['https://fonts.googleapis.com']))
      expect(csp['font-src']).toEqual(expect.arrayContaining(['https://fonts.gstatic.com']))
    })
  })
})
