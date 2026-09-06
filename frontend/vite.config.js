import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In local `npm run dev`, proxy the same /api/* paths the production nginx gateway serves,
// so the app code is identical in both environments.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/inventory': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/inventory/, '/api/v1/inventory'),
      },
      '/api/orders': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/orders/, '/api/v1/orders'),
      },
    },
  },
});
