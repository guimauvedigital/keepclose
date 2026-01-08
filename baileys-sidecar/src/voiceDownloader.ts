import { WASocket, downloadMediaMessage, proto } from '@whiskeysockets/baileys';
import fs from 'fs';
import path from 'path';
import pino from 'pino';

const logger = pino({ level: 'info' });

interface DownloadProgress {
  total: number;
  downloaded: number;
  failed: number;
  status: 'idle' | 'running' | 'paused' | 'completed';
  currentChat?: string;
  totalChats?: number;
  processedChats?: number;
}

let downloadProgress: DownloadProgress = {
  total: 0,
  downloaded: 0,
  failed: 0,
  status: 'idle',
};

// Délai aléatoire entre min et max (en ms)
function randomDelay(min: number, max: number): Promise<void> {
  const delay = Math.floor(Math.random() * (max - min + 1)) + min;
  logger.debug(`Waiting ${delay}ms before next action...`);
  return new Promise(resolve => setTimeout(resolve, delay));
}

// Télécharge un message vocal et le sauvegarde
async function downloadVoiceMessage(
  sock: WASocket,
  message: proto.IWebMessageInfo,
  outputDir: string
): Promise<boolean> {
  try {
    const audioMessage = message.message?.audioMessage;
    if (!audioMessage || !audioMessage.url) {
      logger.debug('Message does not contain downloadable audio');
      return false;
    }

    // Télécharger le média
    const buffer = await downloadMediaMessage(
      message as any,
      'buffer',
      {},
      {
        logger: pino({ level: 'silent' }),
        reuploadRequest: sock.updateMediaMessage,
      }
    );

    if (!buffer) {
      logger.warn('Failed to download media buffer');
      return false;
    }

    // Créer le nom de fichier avec timestamp
    const timestamp = message.messageTimestamp?.toString() || Date.now().toString();
    const from = message.key?.remoteJid?.split('@')[0] || 'unknown';
    const fileName = `${from}_${timestamp}.ogg`;
    const filePath = path.join(outputDir, fileName);

    // Sauvegarder le fichier
    fs.writeFileSync(filePath, buffer as Buffer);

    logger.info(`✅ Downloaded: ${fileName} (${(buffer as Buffer).length} bytes)`);
    return true;
  } catch (error: any) {
    logger.error(`Failed to download voice:`, error.message);
    return false;
  }
}

// Télécharge les vocaux d'un chat spécifique
export async function downloadVoicesFromChat(
  sock: WASocket,
  chatId: string,
  messagesLimit: number = 100,
  outputDir: string = './downloaded_voices'
): Promise<DownloadProgress> {

  if (downloadProgress.status === 'running') {
    throw new Error('Download already in progress');
  }

  downloadProgress = {
    total: 0,
    downloaded: 0,
    failed: 0,
    status: 'running',
    currentChat: chatId,
  };

  try {
    // Créer le dossier de sortie
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').split('T')[0];
    const chatName = chatId.split('@')[0];
    const outputPath = path.join(outputDir, `${chatName}_${timestamp}`);

    if (!fs.existsSync(outputPath)) {
      fs.mkdirSync(outputPath, { recursive: true });
    }

    logger.info(`📁 Saving to: ${outputPath}`);
    logger.info(`📥 Fetching messages from ${chatId}...`);

    // Récupérer l'historique des messages
    const result: any = await sock.fetchMessageHistory(messagesLimit, undefined as any, undefined as any);

    if (!result || !Array.isArray(result) || result.length === 0) {
      logger.warn(`⚠️  No cached messages found. Feature requires message history sync.`);
      logger.info(`💡 Tip: Send yourself a voice message first, then try again!`);
      downloadProgress.status = 'completed';
      return downloadProgress;
    }

    logger.info(`Found ${result.length} cached messages`);

    // Filtrer les messages vocaux du chat spécifique
    const voiceMessages = result.filter(
      (msg: any) =>
        msg.key?.remoteJid === chatId &&
        msg.message?.audioMessage?.ptt === true
    );

    logger.info(`Found ${voiceMessages.length} voice messages 🎙️`);
    downloadProgress.total = voiceMessages.length;

    if (voiceMessages.length === 0) {
      downloadProgress.status = 'completed';
      return downloadProgress;
    }

    // Télécharger chaque vocal avec délais humains
    for (let i = 0; i < voiceMessages.length; i++) {
      if (downloadProgress.status === 'paused') {
        logger.warn('Download paused by user');
        break;
      }

      const msg = voiceMessages[i];

      // Délai rapide mais naturel entre chaque téléchargement (0.5-2 secondes)
      if (i > 0) {
        await randomDelay(500, 2000);
      }

      const success = await downloadVoiceMessage(sock, msg, outputPath);

      if (success) {
        downloadProgress.downloaded++;
      } else {
        downloadProgress.failed++;
      }

      // Pause moyenne toutes les 20 messages (5-10 secondes)
      if ((i + 1) % 20 === 0 && i < voiceMessages.length - 1) {
        logger.info(`💤 Quick break after ${i + 1} downloads...`);
        await randomDelay(5000, 10000);
      }

      // Micro-pause toutes les 5 messages (1-3 secondes)
      else if ((i + 1) % 5 === 0 && i < voiceMessages.length - 1) {
        await randomDelay(1000, 3000);
      }
    }

    downloadProgress.status = 'completed';
    logger.info(`\n🎉 Download completed!`);
    logger.info(`Total: ${downloadProgress.total} | Downloaded: ${downloadProgress.downloaded} | Failed: ${downloadProgress.failed}`);
    logger.info(`Files saved in: ${outputPath}`);

    return downloadProgress;

  } catch (error: any) {
    logger.error('Error during voice download:', error.message || error);
    logger.error('Error stack:', error.stack);
    downloadProgress.status = 'idle';
    throw error;
  }
}

// Télécharge les vocaux de TOUTES les conversations
export async function downloadVoicesFromAllChats(
  sock: WASocket,
  messagesLimit: number = 100,
  outputDir: string = './downloaded_voices'
): Promise<DownloadProgress> {

  if (downloadProgress.status === 'running') {
    throw new Error('Download already in progress');
  }

  downloadProgress.total = 0;
  downloadProgress.downloaded = 0;
  downloadProgress.failed = 0;
  downloadProgress.status = 'running';
  downloadProgress.totalChats = 0;
  downloadProgress.processedChats = 0;

  try {
    logger.info(`🔍 Fetching all conversations...`);

    // Récupérer tous les messages depuis le store
    let result: any[] = [];

    try {
      const historyResult = await sock.fetchMessageHistory(messagesLimit, undefined as any, undefined as any);
      if (historyResult && Array.isArray(historyResult)) {
        result = historyResult;
        logger.info(`Fetched ${result.length} messages from history`);
      }
    } catch (historyError: any) {
      logger.warn(`Could not fetch message history: ${historyError.message}`);
      logger.info(`This feature requires WhatsApp to sync message history first.`);
      logger.info(`Please wait a few minutes for WhatsApp to sync, then try again.`);
    }

    if (result.length === 0) {
      logger.warn(`⚠️  No cached messages found. Feature requires message history sync.`);
      logger.info(`💡 Tip: Wait for WhatsApp history sync to complete, or send yourself a voice message first!`);
      downloadProgress.status = 'completed';
      return downloadProgress;
    }

    logger.info(`Found ${result.length} cached messages`);

    // Extraire tous les chats uniques qui contiennent des vocaux
    const chatsWithVoices = new Map<string, any[]>();

    result.forEach((msg: any) => {
      if (msg.message?.audioMessage?.ptt === true && msg.key?.remoteJid) {
        const chatId = msg.key.remoteJid;
        if (!chatsWithVoices.has(chatId)) {
          chatsWithVoices.set(chatId, []);
        }
        chatsWithVoices.get(chatId)!.push(msg);
      }
    });

    const chatList = Array.from(chatsWithVoices.entries());
    downloadProgress.totalChats = chatList.length;

    logger.info(`📊 Found ${chatList.length} conversations with voice messages`);

    // Calculer le nombre total de vocaux
    const totalVoices = chatList.reduce((sum, [_, msgs]) => sum + msgs.length, 0);
    downloadProgress.total = totalVoices;

    logger.info(`🎙️  Total voice messages to download: ${totalVoices}`);

    // Créer le dossier de sortie principal
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').split('T')[0];
    const mainOutputPath = path.join(outputDir, `all_voices_${timestamp}`);

    if (!fs.existsSync(mainOutputPath)) {
      fs.mkdirSync(mainOutputPath, { recursive: true });
    }

    // Loop sur chaque conversation
    for (let chatIndex = 0; chatIndex < chatList.length; chatIndex++) {
      const currentStatus = downloadProgress.status as DownloadProgress['status'];
      if (currentStatus === 'paused') {
        logger.warn('Download paused by user');
        break;
      }

      const [chatId, voiceMessages] = chatList[chatIndex];
      const chatName = chatId.split('@')[0];

      downloadProgress.currentChat = chatId;
      downloadProgress.processedChats = chatIndex;

      logger.info(`\n📱 [${chatIndex + 1}/${chatList.length}] Processing chat: ${chatName} (${voiceMessages.length} voices)`);

      // Créer un dossier par conversation
      const chatOutputPath = path.join(mainOutputPath, chatName);
      if (!fs.existsSync(chatOutputPath)) {
        fs.mkdirSync(chatOutputPath, { recursive: true });
      }

      // Télécharger chaque vocal de cette conversation
      for (let i = 0; i < voiceMessages.length; i++) {
        const voiceStatus = downloadProgress.status as DownloadProgress['status'];
        if (voiceStatus === 'paused') {
          break;
        }

        const msg = voiceMessages[i];

        // Délai rapide entre chaque téléchargement (0.5-2 secondes)
        if (i > 0) {
          await randomDelay(500, 2000);
        }

        const success = await downloadVoiceMessage(sock, msg, chatOutputPath);

        if (success) {
          downloadProgress.downloaded++;
        } else {
          downloadProgress.failed++;
        }

        // Pause toutes les 20 messages
        if ((i + 1) % 20 === 0 && i < voiceMessages.length - 1) {
          logger.info(`💤 Quick break after ${i + 1} downloads...`);
          await randomDelay(5000, 10000);
        }
        // Micro-pause toutes les 5 messages
        else if ((i + 1) % 5 === 0 && i < voiceMessages.length - 1) {
          await randomDelay(1000, 3000);
        }
      }

      // Délai entre chaque conversation (3-8 secondes) pour rester naturel
      const nextStatus = downloadProgress.status as DownloadProgress['status'];
      if (chatIndex < chatList.length - 1 && nextStatus !== 'paused') {
        logger.info(`⏸️  Moving to next conversation...`);
        await randomDelay(3000, 8000);
      }
    }

    downloadProgress.processedChats = chatList.length;
    downloadProgress.status = 'completed';

    logger.info(`\n🎉 All downloads completed!`);
    logger.info(`Conversations processed: ${downloadProgress.processedChats}/${downloadProgress.totalChats}`);
    logger.info(`Total: ${downloadProgress.total} | Downloaded: ${downloadProgress.downloaded} | Failed: ${downloadProgress.failed}`);
    logger.info(`Files saved in: ${mainOutputPath}`);

    return downloadProgress;

  } catch (error: any) {
    logger.error('Error during voice download:', error.message || error);
    logger.error('Error stack:', error.stack);
    downloadProgress.status = 'idle';
    throw error;
  }
}

export function getDownloadProgress(): DownloadProgress {
  return { ...downloadProgress };
}

export function stopDownload() {
  if (downloadProgress.status === 'running') {
    downloadProgress.status = 'paused';
    logger.warn('Download paused by user');
  }
}
