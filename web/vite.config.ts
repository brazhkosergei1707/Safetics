import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Під час розробки Vite переадресовує запити до API на Spring Boot (localhost:8080).
// Для браузера це один сайт, тож cookie з JWT працюватимуть без CORS.
// SAFETICS_API — інший бекенд (автотести Playwright: сервер профілю e2e на 8081).
const api = process.env.SAFETICS_API ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': api,
      '/actuator': api,
    },
  },
})
