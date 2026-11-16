import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@shared': fileURLToPath(new URL('../web-shared', import.meta.url)) } },
  publicDir: '../web-shared/public',
  server: { host: '127.0.0.1', port: 15183, strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:28049', '/media': 'http://127.0.0.1:28049' } },
})
