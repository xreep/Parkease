/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { publicUrl } from './publicUrl.ts'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss(), publicUrl(loadEnv(mode, process.cwd(), 'VITE_').VITE_PUBLIC_URL)],
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
}))
