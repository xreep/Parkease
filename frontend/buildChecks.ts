import type { Plugin } from 'vite'

type Env = Record<string, string | undefined>

/**
 * Builds that ship to users must know where the API is. That is the case on Vercel (it sets `VERCEL=1`) or when
 * `VITE_REQUIRE_API_URL=true`; local and CI builds, which only prove the code compiles, do not need it.
 */
export function requiresApiUrl(env: Env): boolean {
  return env.VERCEL === '1' || env.VITE_REQUIRE_API_URL === 'true'
}

/**
 * Without `VITE_API_URL` the app calls `/api/v1` on its own host, which on Vercel is the static site: every request
 * would fail in production. Plain http would be blocked by the page's Content-Security-Policy and mixed-content rules.
 */
export function checkProductionApiUrl(apiUrl: string | undefined): void {
  const url = apiUrl?.trim()
  if (!url) {
    throw new Error(
      'VITE_API_URL is not set. Set it (Vercel: Project Settings, Environment Variables) to the backend API, ' +
        'for example https://parkease-api.onrender.com/api/v1, then build again.',
    )
  }
  if (!url.startsWith('https://')) {
    throw new Error(`VITE_API_URL must be an https:// URL for a production build, but it is "${url}".`)
  }
}

/** Fails `vite build` early (before bundling) when the production API URL is missing or unsafe. */
export function requireApiUrl(env: Env, apiUrl: string | undefined): Plugin {
  return {
    name: 'parkease-require-api-url',
    apply: 'build',
    buildStart() {
      if (requiresApiUrl(env)) checkProductionApiUrl(apiUrl)
    },
  }
}
