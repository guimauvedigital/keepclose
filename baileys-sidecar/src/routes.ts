import { Express, Request, Response } from 'express';
import {
  sendTextMessage,
  sendAudioMessage,
  getConnectionStatus,
  getCurrentQR,
  getWhatsAppSocket,
  getVoiceStats,
  listDownloadedVoices,
} from './whatsapp.js';
import {
  downloadVoicesFromChat,
  downloadVoicesFromAllChats,
  getDownloadProgress,
  stopDownload
} from './voiceDownloader.js';
import pino from 'pino';
import QRCode from 'qrcode';

const logger = pino({ level: 'info' });

export function setupRoutes(app: Express) {
  // Health check
  app.get('/health', (req: Request, res: Response) => {
    res.json({ status: 'ok' });
  });

  // Get connection status
  app.get('/status', (req: Request, res: Response) => {
    const status = getConnectionStatus();
    res.json({ status });
  });

  // Get QR code (protected)
  app.get('/qr', (req: Request, res: Response) => {
    // Check API key
    const apiKey = process.env.API_KEY;
    if (apiKey) {
      const providedKey = req.headers['x-api-key'];
      if (!providedKey || providedKey !== apiKey) {
        return res.status(403).json({ error: 'Invalid or missing API key' });
      }
    }

    const qr = getCurrentQR();
    if (qr) {
      res.json({ qr });
    } else {
      res.status(404).json({ error: 'No QR code available' });
    }
  });

  // Get QR code as PNG image (protected)
  app.get('/qr/image', async (req: Request, res: Response) => {
    // Check API key
    const apiKey = process.env.API_KEY;
    if (apiKey) {
      const providedKey = req.headers['x-api-key'];
      if (!providedKey || providedKey !== apiKey) {
        return res.status(403).send('<html><body><h1>Access Denied</h1><p>Invalid or missing API key</p></body></html>');
      }
    }

    try {
      const qr = getCurrentQR();
      if (!qr) {
        return res.status(404).send('<html><body><h1>No QR code available</h1><p>Please wait for WhatsApp connection to initialize...</p></body></html>');
      }

      // Generate QR code as PNG buffer
      const qrImageBuffer = await QRCode.toBuffer(qr, {
        type: 'png',
        width: 400,
        margin: 2,
      });

      res.setHeader('Content-Type', 'image/png');
      res.send(qrImageBuffer);
    } catch (error: any) {
      logger.error('Error generating QR code image:', error);
      res.status(500).json({ error: 'Failed to generate QR code image' });
    }
  });

  // Send text message
  app.post('/send/text', async (req: Request, res: Response) => {
    try {
      const { to, text } = req.body;

      if (!to || !text) {
        return res.status(400).json({
          success: false,
          error: 'Missing required fields: to, text',
        });
      }

      logger.info(`Received text send request: to=${to}`);

      const messageId = await sendTextMessage(to, text);

      res.json({
        success: true,
        messageId,
      });
    } catch (error: any) {
      logger.error('Error sending text message:', error);
      res.status(500).json({
        success: false,
        error: error.message || 'Failed to send text message',
      });
    }
  });

  // Send audio message
  app.post('/send/audio', async (req: Request, res: Response) => {
    try {
      const { to, audioPath } = req.body;

      if (!to || !audioPath) {
        return res.status(400).json({
          success: false,
          error: 'Missing required fields: to, audioPath',
        });
      }

      logger.info(`Received audio send request: to=${to}, audioPath=${audioPath}`);

      const messageId = await sendAudioMessage(to, audioPath);

      res.json({
        success: true,
        messageId,
      });
    } catch (error: any) {
      logger.error('Error sending audio message:', error);
      res.status(500).json({
        success: false,
        error: error.message || 'Failed to send audio message',
      });
    }
  });

  // Download voice messages from a specific chat (human-like progressive download)
  app.post('/download/voices', async (req: Request, res: Response) => {
    try {
      const sock = getWhatsAppSocket();
      if (!sock) {
        return res.status(503).json({
          success: false,
          error: 'WhatsApp not connected',
        });
      }

      const { chatId, messagesLimit = 100, outputDir = './downloaded_voices' } = req.body;

      if (!chatId) {
        return res.status(400).json({
          success: false,
          error: 'chatId is required (format: +33XXXXXXXXX@s.whatsapp.net or +33XXXXXXXXX)',
        });
      }

      // Formater le chatId si nécessaire
      const formattedChatId = chatId.includes('@') ? chatId : `${chatId.replace(/\D/g, '')}@s.whatsapp.net`;

      logger.info(`🎙️ Starting progressive voice download for ${formattedChatId}...`);

      // Start download in background
      downloadVoicesFromChat(sock, formattedChatId, messagesLimit, outputDir)
        .then(progress => {
          logger.info('Download completed:', progress);
        })
        .catch(error => {
          logger.error('Download failed:', error);
        });

      res.json({
        success: true,
        message: `Voice download started for chat: ${formattedChatId}`,
        info: 'Download will proceed slowly to avoid detection (2-8 sec between messages)',
        usage: 'Use /download/progress to track progress',
      });

    } catch (error: any) {
      logger.error('Error starting voice download:', error);
      res.status(500).json({
        success: false,
        error: error.message || 'Failed to start download',
      });
    }
  });

  // Download voice messages from ALL conversations (most recent first)
  app.post('/download/voices/all', async (req: Request, res: Response) => {
    try {
      const sock = getWhatsAppSocket();
      if (!sock) {
        return res.status(503).json({
          success: false,
          error: 'WhatsApp not connected',
        });
      }

      const { messagesLimit = 100, outputDir = './downloaded_voices' } = req.body;

      logger.info(`🎙️ Starting progressive voice download for ALL conversations...`);

      // Start download in background
      downloadVoicesFromAllChats(sock, messagesLimit, outputDir)
        .then(progress => {
          logger.info('All downloads completed:', progress);
        })
        .catch(error => {
          logger.error('Download failed:', error);
        });

      res.json({
        success: true,
        message: 'Voice download started for all conversations',
        info: 'Download will loop through all chats with human-like timing (0.5-2s between messages, 3-8s between chats)',
        usage: 'Use /download/progress to track progress',
      });

    } catch (error: any) {
      logger.error('Error starting voice download:', error);
      res.status(500).json({
        success: false,
        error: error.message || 'Failed to start download',
      });
    }
  });

  // Get download progress
  app.get('/download/progress', (req: Request, res: Response) => {
    const progress = getDownloadProgress();
    res.json(progress);
  });

  // Stop download
  app.post('/download/stop', (req: Request, res: Response) => {
    stopDownload();
    res.json({
      success: true,
      message: 'Download stop requested',
    });
  });

  // Get voice download stats
  app.get('/voices/stats', (req: Request, res: Response) => {
    const stats = getVoiceStats();
    res.json(stats);
  });

  // List all downloaded voices
  app.get('/voices/list', (req: Request, res: Response) => {
    try {
      const result = listDownloadedVoices();
      res.json(result);
    } catch (error: any) {
      logger.error('Error listing voices:', error);
      res.status(500).json({
        success: false,
        error: error.message || 'Failed to list voices',
      });
    }
  });
}
