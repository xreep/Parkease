/// <reference types="node" />
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { applyPublicUrl } from '../../publicUrl.ts'

// Vitest runs from the frontend folder.
const at = (path: string) => resolve(process.cwd(), path)
const html = readFileSync(at('index.html'), 'utf8')
const meta = (attr: string, name: string) =>
  html.match(new RegExp(`<meta\\s+${attr}="${name}"\\s+content="([^"]*)"`))?.[1]

describe('index.html', () => {
  it('declares language, description, theme colour and Open Graph / Twitter tags', () => {
    expect(html).toMatch(/<html lang="en"/)
    expect(meta('name', 'description')).toMatch(/parking/i)
        expect(meta('property', 'og:title')).toMatch(/ParkEase/)
    expect(meta('property', 'og:description')).toBeTruthy()
    expect(meta('property', 'og:type')).toBe('website')
    // __PUBLIC_URL__ becomes the site origin (VITE_PUBLIC_URL) at build time, or nothing for a relative URL.
    expect(meta('property', 'og:image')).toBe('__PUBLIC_URL__/og-image.jpg')
    expect(meta('name', 'twitter:image')).toBe('__PUBLIC_URL__/og-image.jpg')
    expect(meta('name', 'twitter:card')).toBe('summary_large_image')
  })

  it('has a dark-mode theme colour next to the light one', () => {
    expect(html).toMatch(/<meta name="theme-color" content="#047857" media="\(prefers-color-scheme: light\)"/)
    expect(html).toMatch(/<meta name="theme-color" content="#064e3b" media="\(prefers-color-scheme: dark\)"/)
  })

  it('links the favicon set and the web manifest', () => {
    expect(html).toMatch(/rel="icon"[^>]*href="\/favicon\.svg"/)
    expect(html).toMatch(/rel="icon"[^>]*sizes="32x32"[^>]*href="\/favicon-32.png"/)
    expect(html).toMatch(/rel="apple-touch-icon"[^>]*href="\/apple-touch-icon\.png"/)
    expect(html).toMatch(/rel="manifest"[^>]*href="\/manifest\.webmanifest"/)
  })

  it('has a noscript message, so a blocked script does not leave a blank page', () => {
    expect(html).toMatch(/<noscript>[\s\S]*JavaScript[\s\S]*<\/noscript>/)
  })
})

describe('public assets', () => {
  it('ships every file the page and manifest refer to', () => {
    const manifest = JSON.parse(readFileSync(at('public/manifest.webmanifest'), 'utf8'))
    expect(manifest).toMatchObject({ name: 'ParkEase', short_name: 'ParkEase', start_url: '/', display: 'standalone', theme_color: '#047857' })
    const files = ['favicon.svg', 'favicon-32.png', 'apple-touch-icon.png', 'og-image.jpg', ...manifest.icons.map((i: { src: string }) => i.src.replace(/^\//, ''))]
    for (const f of files) expect(existsSync(at(`public/${f}`)), f).toBe(true)
    expect(manifest.icons.map((i: { sizes: string }) => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']))
  })
})

describe('public URL build step', () => {
  it('turns __PUBLIC_URL__ into the configured origin without a trailing slash, or into nothing', () => {
    const page = '<meta property="og:image" content="__PUBLIC_URL__/og-image.jpg" />'
    expect(applyPublicUrl(page, 'https://parkease.example.com/')).toBe('<meta property="og:image" content="https://parkease.example.com/og-image.jpg" />')
    expect(applyPublicUrl(page, 'https://parkease.example.com')).toContain('"https://parkease.example.com/og-image.jpg"')
    expect(applyPublicUrl(page, undefined)).toBe('<meta property="og:image" content="/og-image.jpg" />')
    expect(applyPublicUrl(page, '')).toBe('<meta property="og:image" content="/og-image.jpg" />')
  })
})
