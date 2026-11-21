import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Під час розробки Vite переадресовує запити до API на Spring Boot (localhost:8080).
// Для браузера це один сайт, тож cookie з JWT працюватимуть без CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
