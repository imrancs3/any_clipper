import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The built files (dist/) are served by the Spring Boot backend, so normal use is one process.
// `npm run dev` is only for frontend work: it proxies /api to the backend on port 8080.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
