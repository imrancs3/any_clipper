import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The built files (dist/) are served by the Spring Boot backend, so normal use is one process.
// `npm run dev` is only for frontend work: it proxies /api to the running backend.
// The backend port comes from the same SERVER_PORT environment variable the backend uses (default 8080).
const backendUrl = `http://localhost:${process.env.SERVER_PORT || '8080'}`;

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
                  + 'Both read the SERVER_PORT environment variable; make sure it is set the same for both.',
              }));
            }
          });
        },
      },
    },
  },
});
