import express from 'express';
import { setupRoutes } from './routes.js';
import { initializeWhatsApp } from './whatsapp.js';
import pino from 'pino';

const PORT = process.env.PORT || 3001;
const logger = pino({ level: 'info' });

async function main() {
  const app = express();

  app.use(express.json());
  app.use(express.static('public'));

  logger.info('Initializing WhatsApp connection...');
  await initializeWhatsApp();

  setupRoutes(app);

  app.listen(PORT, () => {
    logger.info(`Baileys sidecar listening on port ${PORT}`);
  });
}

main().catch((error) => {
  logger.error('Failed to start Baileys sidecar:', error);
  process.exit(1);
});
