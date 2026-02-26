import makeWASocket, {
  DisconnectReason,
  useMultiFileAuthState,
  WASocket,
  delay,
  MessageType,
  WAMessage,
  downloadMediaMessage,
  proto
} from '@whiskeysockets/baileys';
import { Boom } from '@hapi/boom';
import pino from 'pino';
import qrcode from 'qrcode-terminal';
import fs from 'fs';
import path from 'path';

const logger = pino({ level: 'info' });
const AUTH_PATH = process.env.BAILEYS_AUTH_PATH || './auth_info';
const VOICES_PATH = process.env.VOICES_PATH || './downloaded_voices';

let sock: WASocket | null = null;
let currentQR: string | null = null;
let connectionStatus: 'connected' | 'connecting' | 'disconnected' | 'qr_pending' = 'disconnected';
let reconnectAttempts = 0;
const MAX_RECONNECT_DELAY = 60_000; // 1 minute max

// Stats for downloaded voices
let voiceStats = {
  total: 0,
  lastDownloaded: null as string | null,
};

// Ensure voices directory exists
if (!fs.existsSync(VOICES_PATH)) {
  fs.mkdirSync(VOICES_PATH, { recursive: true });
  logger.info(`Created voices directory: ${VOICES_PATH}`);
}

// Auto-download voice message
async function autoDownloadVoice(message: proto.IWebMessageInfo) {
  try {
    const audioMessage = message.message?.audioMessage;
    if (!audioMessage || !audioMessage.ptt || !audioMessage.url) {
      return; // Not a voice message
    }

    const from = message.key?.remoteJid?.split('@')[0] || 'unknown';
    const timestamp = message.messageTimestamp?.toString() || Date.now().toString();
    const date = new Date(parseInt(timestamp) * 1000);
    const dateFolder = date.toISOString().split('T')[0]; // YYYY-MM-DD

    // Create folder structure: downloaded_voices/YYYY-MM-DD/contact_number/
    const chatFolder = path.join(VOICES_PATH, dateFolder, from);
    if (!fs.existsSync(chatFolder)) {
      fs.mkdirSync(chatFolder, { recursive: true });
    }

    // Download the voice message
    const buffer = await downloadMediaMessage(
      message as any,
      'buffer',
      {},
      {
        logger: pino({ level: 'silent' }),
        reuploadRequest: sock!.updateMediaMessage,
      }
    );

    if (!buffer) {
      logger.warn(`Failed to download voice from ${from}`);
      return;
    }

    // Save file with timestamp and direction (sent/received)
    const direction = message.key?.fromMe ? 'sent' : 'received';
    const fileName = `${direction}_voice_${timestamp}.ogg`;
    const filePath = path.join(chatFolder, fileName);
    fs.writeFileSync(filePath, buffer as Buffer);

    voiceStats.total++;
    voiceStats.lastDownloaded = new Date().toISOString();

    logger.info(`🎙️ Auto-saved voice: ${from}/${fileName} (${(buffer as Buffer).length} bytes)`);
  } catch (error: any) {
    logger.error(`Error auto-downloading voice: ${error.message}`);
  }
}

export async function initializeWhatsApp() {
  const { state, saveCreds } = await useMultiFileAuthState(AUTH_PATH);

  sock = makeWASocket({
    auth: state,
    printQRInTerminal: true,
    logger: pino({ level: 'info' }),
  });

  sock.ev.on('creds.update', saveCreds);

  sock.ev.on('connection.update', async (update) => {
    const { connection, lastDisconnect, qr } = update;

    logger.info('Connection update:', JSON.stringify(update, null, 2));

    if (qr) {
      currentQR = qr;
      connectionStatus = 'qr_pending';
      logger.info('QR Code received, scan with WhatsApp app');
      qrcode.generate(qr, { small: true });
    }

    if (connection === 'close') {
      const statusCode = (lastDisconnect?.error as Boom)?.output?.statusCode;
      const shouldReconnect = statusCode !== DisconnectReason.loggedOut;

      logger.info(`Connection closed (status: ${statusCode}). Reconnecting: ${shouldReconnect}`);
      connectionStatus = 'disconnected';

      if (shouldReconnect) {
        reconnectAttempts++;
        const backoffDelay = Math.min(5000 * Math.pow(2, reconnectAttempts - 1), MAX_RECONNECT_DELAY);
        logger.info(`Reconnecting in ${backoffDelay}ms (attempt ${reconnectAttempts})...`);
        connectionStatus = 'connecting';
        await delay(backoffDelay);
        await initializeWhatsApp();
      }
    } else if (connection === 'open') {
      logger.info('WhatsApp connection opened successfully');
      connectionStatus = 'connected';
      currentQR = null;
      reconnectAttempts = 0;
    } else if (connection === 'connecting') {
      connectionStatus = 'connecting';
      logger.info('Connecting to WhatsApp...');
    }
  });

  sock.ev.on('messages.upsert', async ({ messages }) => {
    // Auto-download ALL voice messages (both sent and received)
    for (const msg of messages) {
      if (msg.message) {
        const direction = msg.key.fromMe ? '📤 Sent' : '📨 Received';
        logger.info(`${direction} message from ${msg.key.remoteJid}`);

        // Auto-download if it's a voice message
        if (msg.message.audioMessage?.ptt) {
          logger.info(`📥 Voice message detected (${msg.key.fromMe ? 'sent' : 'received'}) from ${msg.key.remoteJid}, downloading...`);
          await autoDownloadVoice(msg);
        }
      }
    }
  });
}

export async function sendTextMessage(to: string, text: string): Promise<string> {
  if (!sock) {
    throw new Error('WhatsApp not initialized');
  }

  if (connectionStatus !== 'connected') {
    throw new Error(`WhatsApp not connected. Status: ${connectionStatus}`);
  }

  const jid = formatJid(to);
  logger.info(`Sending text message to ${jid}`);

  const result = await sock.sendMessage(jid, { text });

  return result?.key?.id || 'unknown';
}

export async function sendAudioMessage(to: string, audioPath: string): Promise<string> {
  if (!sock) {
    throw new Error('WhatsApp not initialized');
  }

  if (connectionStatus !== 'connected') {
    throw new Error(`WhatsApp not connected. Status: ${connectionStatus}`);
  }

  if (!fs.existsSync(audioPath)) {
    throw new Error(`Audio file not found: ${audioPath}`);
  }

  const jid = formatJid(to);
  logger.info(`Sending audio message to ${jid}, file: ${audioPath}`);

  const audioBuffer = fs.readFileSync(audioPath);

  const result = await sock.sendMessage(jid, {
    audio: audioBuffer,
    mimetype: 'audio/ogg; codecs=opus',
    ptt: true, // Push-to-talk (voice message)
  });

  return result?.key?.id || 'unknown';
}

export function getConnectionStatus(): string {
  return connectionStatus;
}

export function getCurrentQR(): string | null {
  return currentQR;
}

export function getWhatsAppSocket(): WASocket | null {
  return sock;
}

function formatJid(phoneNumber: string): string {
  // Remove any non-digit characters
  const cleaned = phoneNumber.replace(/\D/g, '');

  // WhatsApp JID format: number@s.whatsapp.net
  return `${cleaned}@s.whatsapp.net`;
}

export function getVoiceStats() {
  return { ...voiceStats };
}

export function listDownloadedVoices() {
  const voices: any[] = [];

  if (!fs.existsSync(VOICES_PATH)) {
    return { total: 0, voices: [] };
  }

  // Read all date folders
  const dateFolders = fs.readdirSync(VOICES_PATH).filter(f => {
    const fullPath = path.join(VOICES_PATH, f);
    return fs.statSync(fullPath).isDirectory();
  });

  dateFolders.forEach(dateFolder => {
    const datePath = path.join(VOICES_PATH, dateFolder);

    // Read all contact folders in this date
    const contactFolders = fs.readdirSync(datePath).filter(f => {
      const fullPath = path.join(datePath, f);
      return fs.statSync(fullPath).isDirectory();
    });

    contactFolders.forEach(contact => {
      const contactPath = path.join(datePath, contact);

      // Read all voice files
      const files = fs.readdirSync(contactPath).filter(f => f.endsWith('.ogg'));

      files.forEach(file => {
        const filePath = path.join(contactPath, file);
        const stats = fs.statSync(filePath);

        voices.push({
          date: dateFolder,
          contact,
          fileName: file,
          size: stats.size,
          path: filePath,
          downloadedAt: stats.mtime,
        });
      });
    });
  });

  return {
    total: voices.length,
    voices: voices.sort((a, b) => b.downloadedAt.getTime() - a.downloadedAt.getTime()),
  };
}
