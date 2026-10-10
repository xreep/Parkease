/**
 * Where the API lives. In development it is the Vite proxy path (`/api/v1`); in production `VITE_API_URL` points at the
 * deployed backend (e.g. `https://parkease-api.onrender.com/api/v1`). A trailing slash is dropped. Every request,
 * uploads, receipts and CSV exports included, goes through the axios client built on this.
 */
export function apiBaseUrl(configured: string | undefined): string {
  return configured?.trim().replace(/\/+$/, '') || '/api/v1'
}
