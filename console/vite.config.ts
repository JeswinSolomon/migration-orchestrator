import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    exclude: ['e2e/**', 'node_modules/**'],
  },
  server: {
    proxy: {
      '/orchestrator': { target: 'http://localhost:8081', rewrite: (p) => p.replace(/^\/orchestrator/, '') },
      '/ai': { target: 'http://localhost:8000', rewrite: (p) => p.replace(/^\/ai/, '') },
    },
  },
})
