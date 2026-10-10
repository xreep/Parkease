import type { Plugin } from 'vite'

/**
 * Fills the `__PUBLIC_URL__` placeholder in index.html (Open Graph / Twitter image URLs) with the site origin, without a
 * trailing slash. Crawlers need absolute URLs for link previews; with no origin configured the placeholder becomes
 * nothing and the URLs stay relative (fine for local development).
 */
export function applyPublicUrl(html: string, origin: string | undefined): string {
  return html.replaceAll('__PUBLIC_URL__', (origin ?? '').replace(/\/+$/, ''))
}

/** Vite plugin that applies `applyPublicUrl` with `VITE_PUBLIC_URL` from the environment or .env files. */
export function publicUrl(origin: string | undefined): Plugin {
  return { name: 'parkease-public-url', transformIndexHtml: (html) => applyPublicUrl(html, origin) }
}
