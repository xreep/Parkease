/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { requireApiUrl } from './buildChecks.ts'
import { publicUrl } from './publicUrl.ts'

export default defineConfig(({ mode }) => {
  // VITE_* values from the environment or .env files; VERCEL and VITE_REQUIRE_API_URL come from the real environment.
  const env = loadEnv(mode, process.cwd(), ['VITE_', 'VERCEL'])
  return {
    plugins: [
      react(),
      tailwindcss(),
      publicUrl(env.VITE_PUBLIC_URL),
      requireApiUrl({ ...process.env, ...env }, env.VITE_API_URL),
    ],
    server: {
      port: 5173,
      proxy: { '/api': 'http://localhost:8080' },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/test/setup.ts',
      css: false,
      testTimeout: 15_000,
    },
  }
})
