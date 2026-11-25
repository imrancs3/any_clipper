import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The built files (dist/) are served by the Spring Boot backend, so normal use is one process.
// `npm run dev` is only for frontend work: it proxies /api to the running backend.
// If the backend runs on another port, set CLIPPER_BACKEND_URL, e.g. http://localhost:8089.
const backendUrl = process.env.CLIPPER_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': {
        target: backendUrl,
        // Without this, an unreachable backend shows up in the browser as a bare "500" with no reason.
        configure: (proxy) => {
          proxy.on('error', (_error, _request, response) => {
            if ('writeHead' in response && !response.headersSent) {
              response.writeHead(502, { 'Content-Type': 'application/json' });
              response.end(JSON.stringify({
                error: `Cannot reach the Clipper backend at ${backendUrl}. Is it running on that port? `
                  + 'If you started it on another port, set CLIPPER_BACKEND_URL before npm run dev.',
              }));
            }
          });
        },
      },
    },
  },
});
