import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    // Разрешаем работу через туннели (localtunnel, ngrok и т.д.)
    allowedHosts: true,
    // На всякий случай разрешаем доступ по локальной сети
    host: true,
  }
})