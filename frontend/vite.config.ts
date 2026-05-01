import { defineConfig, createLogger } from 'vite';
import react from '@vitejs/plugin-react';

const logger = createLogger();
const _error = logger.error.bind(logger);
logger.error = (msg, opts) => {
  if (msg.includes('ws proxy socket error')) return;
  _error(msg, opts);
};

export default defineConfig({
  customLogger: logger,
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        configure: (proxy) => { proxy.on('error', () => {}); },
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        configure: (proxy) => { proxy.on('error', () => {}); },
      },
    },
  },
});
