# KeepClose API - Exemples d'utilisation

## Configuration ElevenLabs TTS

### Paramètres disponibles

Tous les paramètres TTS sont **optionnels**. Si non spécifiés, les valeurs par défaut suivantes seront utilisées :

| Paramètre | Valeur par défaut | Description |
|-----------|-------------------|-------------|
| `modelId` | `eleven_multilingual_v2` | Modèle ElevenLabs à utiliser |
| `speed` | `1.12` | Vitesse de lecture (0.5 à 2.0) |
| `stability` | `0.8` (80%) | Stabilité de la voix |
| `similarityBoost` | `1.0` (100%) | Augmentation de similarité |
| `style` | `0.6` (60%) | Style de la voix |
| `speakerBoost` | `true` | Amplification du haut-parleur |
| `voiceId` | Configuré dans .env | ID de la voix ElevenLabs |

### Exemple minimal (utilise toutes les valeurs par défaut)

```bash
curl -X POST http://localhost:8080/api/v1/messages \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "to": "+33621962379",
    "type": "AUDIO",
    "audio": {
      "source": "TTS",
      "tts": {
        "text": "Bonjour ! Ceci est un message vocal de test."
      }
    }
  }'
```

### Exemple avec tous les paramètres personnalisés

```bash
curl -X POST http://localhost:8080/api/v1/messages \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "to": "+33621962379",
    "type": "AUDIO",
    "audio": {
      "source": "TTS",
      "tts": {
        "text": "Yo yo yo j'\''espère tu vas bien !",
        "voiceId": "CUSTOM_VOICE_ID",
        "modelId": "eleven_multilingual_v2",
        "speed": 1.12,
        "stability": 0.8,
        "similarityBoost": 1.0,
        "style": 0.6,
        "speakerBoost": true
      }
    }
  }'
```

### Exemple pour Customer.io (webhook Stripe)

```json
{
  "to": "{{ data.object.phone }}",
  "type": "AUDIO",
  "audio": {
    "source": "TTS",
    "tts": {
      "text": "Yo yo yo {{ data.object.name }}, j'espère tu vas bien, j'ai vu que tu as pris l'essai gratuit sur ControlResell, n'hésite pas si tu as des questions !",
      "voiceId": "YOUR_VOICE_ID",
      "speed": 1.12,
      "stability": 0.8,
      "similarityBoost": 1.0,
      "style": 0.6,
      "speakerBoost": true
    }
  }
}
```

**Headers à configurer dans Customer.io :**
```
Content-Type: application/json
X-API-Key: YOUR_API_KEY_HERE
```

## Modèles ElevenLabs disponibles

- `eleven_monolingual_v1` - Anglais uniquement
- `eleven_multilingual_v1` - Multilingue v1
- `eleven_multilingual_v2` - Multilingue v2 (par défaut, recommandé)
- `eleven_turbo_v2` - Version rapide

## Ajustement de la vitesse

La vitesse peut être ajustée entre `0.5` (50%) et `2.0` (200%).

- `1.0` = vitesse normale
- `1.12` = 12% plus rapide (valeur par défaut)
- `0.8` = 20% plus lent

## Notes importantes

- L'API key est **obligatoire** pour toutes les requêtes
- Le numéro de téléphone doit être au format international avec `+`
- Les fichiers audio sont automatiquement convertis en format OGG Opus pour WhatsApp
- Les vocaux envoyés et reçus sont automatiquement sauvegardés dans `/data/voices/`

---

## Contacts iCloud (CardDAV)

Créer des contacts directement sur votre iPhone via iCloud. Les contacts apparaissent instantanément après synchronisation.

### Configuration requise

Dans votre `.env` :
```bash
ICLOUD_EMAIL=votre_apple_id@icloud.com
ICLOUD_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx
```

> **Important** : Créez un mot de passe spécifique à l'app sur https://appleid.apple.com (Sécurité → Mots de passe pour applications)

### Champs disponibles

| Champ | Requis | Description |
|-------|--------|-------------|
| `userId` | ✅ | Identifiant utilisateur (ajouté dans les notes) |
| `phoneNumber` | ✅ | Numéro au format international (+33...). Le 0 après l'indicatif est supprimé automatiquement. |
| `displayName` | ❌ | Nom affiché (défaut: userId) |
| `email` | ❌ | Adresse email |
| `organization` | ❌ | Nom de l'organisation |
| `title` | ❌ | Titre/fonction |
| `urls` | ❌ | Liste d'URLs avec labels (ex: Stripe, LinkedIn, Dashboard) |
| `notes` | ❌ | Notes supplémentaires |

#### Format des URLs

```json
"urls": [
  { "url": "https://dashboard.stripe.com/customers/cus_xxx", "label": "Stripe" },
  { "url": "https://linkedin.com/in/jean", "label": "LinkedIn" }
]
```

Le `label` est optionnel. Sans label, l'URL apparaît sans étiquette dans la fiche contact.

### Exemple minimal

```bash
curl -X POST http://localhost:8080/api/v1/contacts \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "userId": "user_abc123",
    "phoneNumber": "+33612345678"
  }'
```

**Réponse (création) :**
```json
{
  "success": true,
  "contactId": "550e8400-e29b-41d4-a716-446655440000",
  "updated": false
}
```

### Exemple complet avec tous les champs

```bash
curl -X POST http://localhost:8080/api/v1/contacts \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "userId": "cus_abc123",
    "phoneNumber": "+33612345678",
    "displayName": "Jean Dupont",
    "email": "jean.dupont@email.com",
    "organization": "ControlResell",
    "title": "Client Premium",
    "urls": [
      { "url": "https://dashboard.stripe.com/customers/cus_abc123", "label": "Stripe" },
      { "url": "https://controlresell.com/admin/users/cus_abc123", "label": "Dashboard" }
    ],
    "notes": "Client depuis janvier 2025"
  }'
```

### Upsert automatique (même numéro = mise à jour)

Si vous envoyez une requête avec un numéro de téléphone existant, le contact est **mis à jour** au lieu d'être créé :

```bash
# Première requête → création
curl -X POST ... -d '{"userId": "user_001", "phoneNumber": "+33612345678"}'
# Réponse: {"success": true, "updated": false}

# Deuxième requête avec même numéro → mise à jour
curl -X POST ... -d '{"userId": "user_001", "phoneNumber": "+33612345678", "displayName": "Nouveau nom"}'
# Réponse: {"success": true, "updated": true}
```

### Exemple pour Customer.io (webhook Stripe)

```json
{
  "userId": "{{ data.object.id }}",
  "phoneNumber": "{{ data.object.phone }}",
  "displayName": "{{ data.object.name }}",
  "email": "{{ data.object.email }}",
  "organization": "ControlResell",
  "title": "{{ data.object.metadata.plan }}",
  "urls": [
    { "url": "https://dashboard.stripe.com/customers/{{ data.object.id }}", "label": "Stripe" }
  ]
}
```

### Supprimer un contact

```bash
curl -X DELETE http://localhost:8080/api/v1/contacts/{contactId} \
  -H "X-API-Key: YOUR_API_KEY"
```
