import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The built files (dist/) are served by the Spring Boot backend, so normal use is one process.
// `npm run dev` is only for frontend work: it proxies /api to the running backend.
// The backend port is SERVER_PORT, read from the .env file in the project root (or the environment),
// exactly like the backend does; 8080 is only the fallback when it is not set.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '');
  const backendUrl = `http://localhost:${env.SERVER_PORT || '8080'}`;

  return {
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
                    + 'Both read SERVER_PORT from the .env file in the project root.',
                }));
              }
            });
          },
        },
      },
    },
  };
});
