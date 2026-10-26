#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ============================================================================
// CONFIGURATION
// ============================================================================
#define MY_NODE_ID      0x0001   // UNIQUE par carte : 0x0001, 0x0002, ...
#define BROADCAST_ID    0xFFFF

#define MY_NAME         "Passerelle-A"   // NOUVEAU : pseudo affiché dans l'application (24 octets max)

#define BOARD_ESP32CAM  1        // 1 = ESP32-CAM, 0 = TTGO / Heltec V2

#define LORA_FREQ       433E6    // Doit correspondre au module (433E6 / 868E6 / 915E6)
#define LORA_SYNC_WORD  0xF3
#define LORA_TX_POWER   17       // dBm (2..20)

#define SCK_PIN    5
#define MISO_PIN  19
#define MOSI_PIN  27
#define SS_PIN    18
#define RST_PIN   14
#define DIO0_PIN  26

// Types de paquets
#define TYPE_TEXT   0
#define TYPE_GPS    1
#define TYPE_HELLO  2
#define TYPE_ACK    3
#define TYPE_NAME   4   // NOUVEAU : annonce de pseudo (payload = pseudo UTF-8), relayée comme un message

// Paquet
#define HEADER_SIZE      10
#define MAX_PAYLOAD_LEN  230
#define MAX_PACKET_LEN   (HEADER_SIZE + MAX_PAYLOAD_LEN)
#define DEFAULT_TTL      5

// Tables et files
#define MAX_SEEN             100
#define MAX_NEIGHBORS        30
#define NEIGHBOR_TIMEOUT_MS  60000UL
#define HELLO_INTERVAL_MS    20000UL
#define NAME_INTERVAL_MS     300000UL  // NOUVEAU : annonce du pseudo toutes les 5 min
#define MAX_NAME_LEN         24
#define TX_QUEUE_SIZE        16   // (était 8) : un envoi multi-destinations met plusieurs paquets en file
#define BLE_RX_QUEUE         8
#define MAX_PENDING_ACK      16   // (était 8) : un ACK attendu par destination

// --- Envoi multi-destinations ------------------------------------------------
// Nombre maximum de destinataires dans une seule commande.
// Chaque destinataire reçoit sa propre copie du paquet (msg_id distinct),
// donc chaque copie est routée, relayée et acquittée indépendamment.
// Doit rester <= TX_QUEUE_SIZE et <= MAX_PENDING_ACK.
#define MAX_MULTI_DEST       8

// --- Multi-connexion BLE -----------------------------------------------------
// Nombre maximum de téléphones connectés en même temps.
// Le core Arduino ESP32 précompilé supporte 3 connexions BLE simultanées.
// Au-delà, il faut recompiler la pile (ESP-IDF : CONFIG_BT_ACL_CONNECTIONS).
#define MAX_BLE_CLIENTS      3
// 1 = un paquet reçu d'un téléphone est aussi transmis aux autres téléphones
//     du même nœud (broadcast, HELLO, ou destinataire connecté en BLE).
//     L'application doit ignorer les paquets dont src_id est le sien.
#define BLE_FORWARD_TO_PHONES 1
// Vérification périodique de la publicité BLE (filet de sécurité)
#define ADV_WATCHDOG_MS      3000UL

// UUID BLE GATT (identiques à l'application Android)
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_RX_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // téléphone -> ESP32
#define CHARACTERISTIC_TX_UUID "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32 -> téléphone

// ============================================================================
// STRUCTURES
// ============================================================================
#pragma pack(push, 1)
struct MeshPacket {
  uint32_t msg_id;
  uint16_t src_id;
  uint16_t dst_id;
  uint8_t  ttl;      // sauts restants autorisés
  uint8_t  type;
  uint8_t  payload[MAX_PAYLOAD_LEN];
};
#pragma pack(pop)

enum Source { FROM_BLE, FROM_LORA, FROM_LOCAL };

struct Neighbor {
  uint16_t id;
  uint32_t lastBle;   // dernier HELLO reçu par BLE (0 = jamais)
  uint32_t lastLora;  // dernier HELLO reçu par LoRa (0 = jamais)
};

struct TxItem {
  bool     used;
  uint32_t sendAt;
  uint8_t  len;
  uint8_t  data[MAX_PACKET_LEN];
};

struct RxItem {
  uint8_t len;
  uint8_t data[MAX_PACKET_LEN];
};

// ============================================================================
// ÉTAT GLOBAL
// ============================================================================
BLEServer*         pServer           = nullptr;
BLECharacteristic* pTxCharacteristic = nullptr;

// Gestion de la publicité BLE (toujours active tant qu'il reste de la place)
volatile bool      restartAdv   = false;   // une relance est demandée
volatile uint32_t  restartAdvAt = 0;       // à partir de quand
volatile bool      advActive    = false;   // la publicité est en cours

uint64_t seenIds[MAX_SEEN];   // clé (src_id << 32) | msg_id, identique à l'application
uint8_t  seenCount = 0, seenIdx = 0;

Neighbor neighbors[MAX_NEIGHBORS];
uint8_t  neighborCount = 0;

TxItem txQueue[TX_QUEUE_SIZE];

RxItem bleRx[BLE_RX_QUEUE];
volatile uint8_t rxHead = 0, rxTail = 0;

uint32_t pendingAck[MAX_PENDING_ACK];
uint8_t  pendingIdx = 0;

// ============================================================================
// UTILITAIRES
// ============================================================================
uint32_t newMsgId() {
  uint32_t r;
  do { r = esp_random(); } while (r == 0);
  return r;
}

// Nombre de téléphones actuellement connectés
uint8_t bleClients() {
  return (pServer != nullptr) ? (uint8_t)pServer->getConnectedCount() : 0;
}

// Demande une (re)lance de la publicité, si il reste de la place
void scheduleAdvertising(uint32_t delayMs) {
  if (bleClients() < MAX_BLE_CLIENTS) {
    restartAdvAt = millis() + delayMs;
    restartAdv   = true;
  }
}

// --- Cache anti-doublons (tampon circulaire) --------------------------------
// MODIFIÉ : la clé combine src_id et msg_id. Deux nœuds qui tirent le même msg_id
// aléatoire ne se bloquent plus mutuellement.
uint64_t seenKey(const MeshPacket* p) { return ((uint64_t)p->src_id << 32) | p->msg_id; }
bool isSeen(uint64_t id) {
  for (uint8_t i = 0; i < seenCount; i++) if (seenIds[i] == id) return true;
  return false;
}
void markSeen(uint64_t id) {
  seenIds[seenIdx] = id;
  seenIdx = (seenIdx + 1) % MAX_SEEN;
  if (seenCount < MAX_SEEN) seenCount++;
}

// --- Table des voisins (alimentée UNIQUEMENT par les HELLO) -------------------
// Un HELLO n'est jamais relayé : son src_id est donc forcément un voisin direct.
bool freshTs(uint32_t t) { return t != 0 && (millis() - t) < NEIGHBOR_TIMEOUT_MS; }

void cleanNeighbors() {
  uint8_t j = 0;
  for (uint8_t i = 0; i < neighborCount; i++) {
    if (freshTs(neighbors[i].lastBle) || freshTs(neighbors[i].lastLora)) {
      if (i != j) neighbors[j] = neighbors[i];
      j++;
    }
  }
  neighborCount = j;
}

void updateNeighbor(uint16_t id, bool viaBle) {
  if (id == MY_NODE_ID || id == 0 || id == BROADCAST_ID) return;
  uint32_t now = millis(); if (now == 0) now = 1;
  for (uint8_t i = 0; i < neighborCount; i++) {
    if (neighbors[i].id == id) {
      if (viaBle) neighbors[i].lastBle = now; else neighbors[i].lastLora = now;
      return;
    }
  }
  cleanNeighbors();
  if (neighborCount < MAX_NEIGHBORS) {
    Neighbor n = { id, viaBle ? now : 0, viaBle ? 0 : now };
    neighbors[neighborCount++] = n;
    Serial.printf("[VOISIN] 0x%04X détecté via %s\n", id, viaBle ? "BLE" : "LoRa");
  }
}

bool isBleNeighbor(uint16_t id) {
  for (uint8_t i = 0; i < neighborCount; i++)
    if (neighbors[i].id == id && freshTs(neighbors[i].lastBle)) return true;
  return false;
}

void printNeighbors() {
  cleanNeighbors();
  Serial.printf("[VOISINS] %u nœud(s)\n", neighborCount);
  for (uint8_t i = 0; i < neighborCount; i++) {
    Serial.printf("  0x%04X  BLE:%s  LoRa:%s\n", neighbors[i].id,
                  freshTs(neighbors[i].lastBle) ? "oui" : "non",
                  freshTs(neighbors[i].lastLora) ? "oui" : "non");
  }
}

// --- ACK en attente (messages envoyés par ce nœud) ---------------------------
void addPendingAck(uint32_t id) {
  pendingAck[pendingIdx] = id;
  pendingIdx = (pendingIdx + 1) % MAX_PENDING_ACK;
}
bool takePendingAck(uint32_t id) {
  for (uint8_t i = 0; i < MAX_PENDING_ACK; i++)
    if (pendingAck[i] == id) { pendingAck[i] = 0; return true; }
  return false;
}

// ============================================================================
// TRANSMISSION
// ============================================================================
// notify() envoie à TOUS les téléphones connectés qui ont activé les notifications.
void sendToBLE(const MeshPacket* p, size_t len) {
  if (bleClients() > 0 && pTxCharacteristic != nullptr) {
    pTxCharacteristic->setValue((uint8_t*)p, len);
    pTxCharacteristic->notify();
    delay(8); // évite de saturer la pile BLE lors de notifications rapprochées
  }
}

// File d'envoi LoRa NON bloquante : le délai aléatoire anti-collision (jitter)
// est planifié, la boucle principale continue d'écouter pendant l'attente.
void queueLoRa(const MeshPacket* p, size_t len, uint32_t dmin, uint32_t dmax) {
  for (uint8_t i = 0; i < TX_QUEUE_SIZE; i++) {
    if (!txQueue[i].used) {
      txQueue[i].used   = true;
      txQueue[i].sendAt = millis() + random(dmin, dmax);
      txQueue[i].len    = (uint8_t)len;
      memcpy(txQueue[i].data, p, len);
      return;
    }
  }
  Serial.println("[LORA] File d'envoi pleine, paquet perdu");
}

void serviceTxQueue() {
  uint32_t now = millis();
  for (uint8_t i = 0; i < TX_QUEUE_SIZE; i++) {
    if (txQueue[i].used && (int32_t)(now - txQueue[i].sendAt) >= 0) {
      LoRa.beginPacket();
      LoRa.write(txQueue[i].data, txQueue[i].len);
      LoRa.endPacket();
      LoRa.receive();            // retour immédiat en écoute
      txQueue[i].used = false;
      return;                    // un envoi par tour de boucle
    }
  }
}

// ============================================================================
// ROUTAGE
// ============================================================================
void handlePacket(MeshPacket* p, size_t len, Source src);

void deliverLocal(const MeshPacket* p, size_t payloadLen) {
  switch (p->type) {
    case TYPE_TEXT: {
      char buf[MAX_PAYLOAD_LEN + 1];
      memcpy(buf, p->payload, payloadLen);
      buf[payloadLen] = 0;
      Serial.println("\n=========================================");
      Serial.printf("MESSAGE de 0x%04X -> 0x%04X | id 0x%08lX | TTL %u\n",
                    p->src_id, p->dst_id, (unsigned long)p->msg_id, p->ttl);
      Serial.printf("Contenu : %s\n", buf);
      Serial.println("=========================================");
      break;
    }
    case TYPE_GPS: {
      if (payloadLen >= 8) {
        float lat, lon;
        memcpy(&lat, p->payload, 4);
        memcpy(&lon, p->payload + 4, 4);
        // NOUVEAU : l'application ajoute son pseudo après les 8 octets de coordonnées
        char name[MAX_NAME_LEN + 1] = "";
        size_t n = payloadLen - 8; if (n > MAX_NAME_LEN) n = MAX_NAME_LEN;
        memcpy(name, p->payload + 8, n); name[n] = 0;
        Serial.printf("[GPS] 0x%04X %s : lat %.6f, lon %.6f\n", p->src_id, name, lat, lon);
      }
      break;
    }
    case TYPE_NAME: {   // NOUVEAU
      char name[MAX_NAME_LEN + 1];
      size_t n = payloadLen > MAX_NAME_LEN ? MAX_NAME_LEN : payloadLen;
      memcpy(name, p->payload, n); name[n] = 0;
      Serial.printf("[PSEUDO] 0x%04X = %s\n", p->src_id, name);
      break;
    }
    case TYPE_ACK: {
      if (payloadLen >= 4 && p->dst_id == MY_NODE_ID) {
        uint32_t orig;
        memcpy(&orig, p->payload, 4);
        if (takePendingAck(orig))
          Serial.printf("[ACK] Message 0x%08lX bien reçu par 0x%04X\n", (unsigned long)orig, p->src_id);
      }
      break;
    }
  }
}

void sendAck(uint16_t toNode, uint32_t origMsgId) {
  MeshPacket a;
  a.msg_id = newMsgId();
  a.src_id = MY_NODE_ID;
  a.dst_id = toNode;
  a.ttl    = DEFAULT_TTL;
  a.type   = TYPE_ACK;
  memcpy(a.payload, &origMsgId, 4);
  handlePacket(&a, HEADER_SIZE + 4, FROM_LOCAL);
}

void handlePacket(MeshPacket* p, size_t len, Source src) {
  if (len < HEADER_SIZE) return;
  if (src != FROM_LOCAL && p->src_id == MY_NODE_ID) return;   // écho de mes propres paquets

  // 1. Déduplication : rejet silencieux
  if (isSeen(seenKey(p))) return;
  markSeen(seenKey(p));

  size_t payloadLen = len - HEADER_SIZE;

  // 2. HELLO : met à jour la table des voisins, jamais relayé en LoRa
  if (p->type == TYPE_HELLO) {
    updateNeighbor(p->src_id, src == FROM_BLE);
    if (src == FROM_LORA) sendToBLE(p, len);   // les téléphones voient les voisins LoRa
#if BLE_FORWARD_TO_PHONES
    if (src == FROM_BLE)  sendToBLE(p, len);   // les téléphones se voient entre eux
#endif
    return;
  }

  bool forMe  = (p->dst_id == MY_NODE_ID);
  bool bcast  = (p->dst_id == BROADCAST_ID);
  bool bleLocal = !bcast && !forMe && isBleNeighbor(p->dst_id);

  // 3. Traitement local
  if (forMe || bcast) deliverLocal(p, payloadLen);

  // ACK automatique pour un message texte direct qui m'est destiné
  if (forMe && p->type == TYPE_TEXT) sendAck(p->src_id, p->msg_id);

  // 4. Livraison aux téléphones connectés
  if (src == FROM_LORA) {
    sendToBLE(p, len);                                   // tout ce qui vient de LoRa
  } else if (src == FROM_LOCAL && (bcast || bleLocal)) {
    sendToBLE(p, len);                                   // émission locale vers les téléphones
  }
#if BLE_FORWARD_TO_PHONES
  else if (src == FROM_BLE && (bcast || bleLocal)) {
    sendToBLE(p, len);                                   // téléphone -> autres téléphones du nœud
  }
#endif

  if (forMe) return;   // destination finale atteinte

  // 5. Relais LoRa
  //    - jamais si la destination est un voisin BLE direct (anti-saturation)
  //    - TTL = nombre de sauts restants : relayé tant que ttl > 0
  if (bleLocal) {
    Serial.printf("[ROUTAGE] 0x%04X est voisin BLE : pas de LoRa\n", p->dst_id);
    return;
  }
  if (p->ttl > 0) {
    p->ttl--;
    queueLoRa(p, len, (src == FROM_LORA) ? 50 : 20, (src == FROM_LORA) ? 250 : 120);
  }
}

// ============================================================================
// ENVOI DEPUIS CE NŒUD (tests Moniteur Série)
// ============================================================================

// --- Envoi vers UNE destination (inchangé) -----------------------------------
void sendText(uint16_t dst, const char* text) {
  MeshPacket p;
  size_t n = strlen(text);
  if (n > MAX_PAYLOAD_LEN) n = MAX_PAYLOAD_LEN;
  p.msg_id = newMsgId();
  p.src_id = MY_NODE_ID;
  p.dst_id = dst;
  p.ttl    = DEFAULT_TTL;
  p.type   = TYPE_TEXT;
  memcpy(p.payload, text, n);
  if (dst != BROADCAST_ID) addPendingAck(p.msg_id);
  Serial.printf("[ENVOI] 0x%04X -> 0x%04X | id 0x%08lX | %s\n",
                MY_NODE_ID, dst, (unsigned long)p.msg_id, text);
  handlePacket(&p, HEADER_SIZE + n, FROM_LOCAL);
}

// --- NOUVEAU : envoi vers PLUSIEURS destinations -----------------------------
// Principe : une copie unicast du message par destinataire, chacune avec son
// propre msg_id. Avantages : aucun changement du format de paquet (l'application
// Android et les autres nœuds restent compatibles), routage/relais/ACK
// indépendants par destinataire, et un destinataire voisin BLE n'utilise pas
// la radio LoRa.
// Si la liste contient BROADCAST_ID, un seul broadcast suffit (il atteint tout
// le monde) : on n'envoie alors que lui.
void sendTextMulti(const uint16_t* dsts, uint8_t count, const char* text) {
  if (count == 0) return;

  for (uint8_t i = 0; i < count; i++) {
    if (dsts[i] == BROADCAST_ID) {
      Serial.println("[MULTI] 0xFFFF présent dans la liste : broadcast unique");
      sendText(BROADCAST_ID, text);
      return;
    }
  }

  Serial.printf("[MULTI] Envoi vers %u destinataire(s)\n", count);
  for (uint8_t i = 0; i < count; i++) {
    sendText(dsts[i], text);   // msg_id, ACK en attente et routage propres à chaque copie
  }
}

// --- NOUVEAU : analyse d'une liste d'ID "0002,0x0003,0005" -------------------
// Séparateurs acceptés : virgule ou point-virgule. Les IDs sont en hexadécimal
// (préfixe 0x facultatif). Ignore : doublons, ID 0, et son propre ID.
// Retourne le nombre de destinataires valides (<= maxOut).
uint8_t parseDestList(const String& list, uint16_t* out, uint8_t maxOut) {
  uint8_t count = 0;
  int start = 0;
  int len = list.length();

  while (start <= len && count < maxOut) {
    int end = start;
    while (end < len && list[end] != ',' && list[end] != ';') end++;

    String tok = list.substring(start, end);
    tok.trim();
    if (tok.length() > 0) {
      uint16_t id = (uint16_t)strtol(tok.c_str(), nullptr, 16);   // accepte "0x0003" et "0003"
      bool valid = (id != 0 && id != MY_NODE_ID);
      if (valid) {
        for (uint8_t k = 0; k < count; k++) if (out[k] == id) { valid = false; break; }
      }
      if (valid) {
        out[count++] = id;
      } else {
        Serial.printf("[MULTI] ID ignoré : '%s' (invalide, doublon ou moi-même)\n", tok.c_str());
      }
    }
    start = end + 1;
  }

  if (start <= len && count >= maxOut) {
    Serial.printf("[MULTI] Limite de %u destinataires atteinte, le reste est ignoré\n", (unsigned)maxOut);
  }
  return count;
}

// --- GPS : vers une destination (ou broadcast par défaut) --------------------
void sendGpsTo(uint16_t dst, float lat, float lon) {
  MeshPacket p;
  p.msg_id = newMsgId();
  p.src_id = MY_NODE_ID;
  p.dst_id = dst;
  p.ttl    = DEFAULT_TTL;
  p.type   = TYPE_GPS;
  memcpy(p.payload, &lat, 4);
  memcpy(p.payload + 4, &lon, 4);
  Serial.printf("[ENVOI GPS] -> 0x%04X : %.6f, %.6f\n", dst, lat, lon);
  handlePacket(&p, HEADER_SIZE + 8, FROM_LOCAL);
}

void sendGps(float lat, float lon) {
  sendGpsTo(BROADCAST_ID, lat, lon);
}

// --- NOUVEAU : GPS vers plusieurs destinations -------------------------------
void sendGpsMulti(const uint16_t* dsts, uint8_t count, float lat, float lon) {
  for (uint8_t i = 0; i < count; i++) {
    if (dsts[i] == BROADCAST_ID) { sendGpsTo(BROADCAST_ID, lat, lon); return; }
  }
  for (uint8_t i = 0; i < count; i++) sendGpsTo(dsts[i], lat, lon);
}

// MODIFIÉ : le HELLO transporte le pseudo de la passerelle (les anciens nœuds l'ignorent)
size_t putName(MeshPacket* p) {
  size_t n = strlen(MY_NAME);
  if (n > MAX_NAME_LEN) n = MAX_NAME_LEN;
  memcpy(p->payload, MY_NAME, n);
  return n;
}

void sendHello() {
  MeshPacket p;
  p.msg_id = newMsgId();
  p.src_id = MY_NODE_ID;
  p.dst_id = BROADCAST_ID;
  p.ttl    = 1;
  p.type   = TYPE_HELLO;
  size_t len = HEADER_SIZE + putName(&p);
  queueLoRa(&p, len, 10, 400);   // LoRa
  sendToBLE(&p, len);            // les téléphones apprennent l'ID et le pseudo de ce nœud
}

// NOUVEAU : annonce du pseudo à tout le réseau (relayée, TTL normal)
void sendName() {
  MeshPacket p;
  p.msg_id = newMsgId();
  p.src_id = MY_NODE_ID;
  p.dst_id = BROADCAST_ID;
  p.ttl    = DEFAULT_TTL;
  p.type   = TYPE_NAME;
  size_t len = HEADER_SIZE + putName(&p);
  handlePacket(&p, len, FROM_LOCAL);
}

void printHelp() {
  Serial.println("Commandes :");
  Serial.println("  <texte>                      broadcast à tous");
  Serial.println("  /to <ID> <texte>             vers UNE destination (ID hexa)");
  Serial.println("  /to <ID1>,<ID2>,.. <texte>   vers PLUSIEURS destinations (sans espace dans la liste)");
  Serial.println("  /gps <lat> <lon>             position en broadcast");
  Serial.println("  /gpsto <ID1>,<ID2>,.. <lat> <lon>   position vers des destinations précises");
  Serial.println("  /hello  /nb  /ble  /help");
  Serial.printf ("  Exemple : /to 0002,0003,0005 Rendez-vous au point A   (max %u destinataires)\n",
                 (unsigned)MAX_MULTI_DEST);
}

void handleSerialLine(String s) {
  s.trim();
  if (s.length() == 0) return;

  if (s == "/hello") { sendHello(); return; }
  if (s == "/nb")    { printNeighbors(); return; }
  if (s == "/help")  { printHelp(); return; }
  if (s == "/ble") {
    Serial.printf("[BLE] %u/%u téléphone(s) connecté(s), publicité %s\n",
                  bleClients(), (unsigned)MAX_BLE_CLIENTS, advActive ? "active" : "arrêtée");
    return;
  }

  if (s.startsWith("/gpsto ")) {
    // /gpsto <ID1>,<ID2>,... <lat> <lon>
    int sp = s.indexOf(' ', 7);
    if (sp > 0) {
      uint16_t dsts[MAX_MULTI_DEST];
      uint8_t n = parseDestList(s.substring(7, sp), dsts, MAX_MULTI_DEST);
      float lat = 0, lon = 0;
      if (n > 0 && sscanf(s.c_str() + sp + 1, "%f %f", &lat, &lon) == 2) {
        sendGpsMulti(dsts, n, lat, lon);
        return;
      }
    }
    Serial.println("Usage : /gpsto <ID1>,<ID2>,... <latitude> <longitude>");
    return;
  }

  if (s.startsWith("/gps ")) {
    float lat = 0, lon = 0;
    if (sscanf(s.c_str() + 5, "%f %f", &lat, &lon) == 2) sendGps(lat, lon);
    else Serial.println("Usage : /gps <latitude> <longitude>");
    return;
  }

  if (s.startsWith("/to ")) {
    // /to <ID>[,<ID>,...] <texte>
    int sp = s.indexOf(' ', 4);
    if (sp > 0) {
      uint16_t dsts[MAX_MULTI_DEST];
      uint8_t n = parseDestList(s.substring(4, sp), dsts, MAX_MULTI_DEST);
      if (n == 0) {
        Serial.println("Aucun destinataire valide");
        return;
      }
      String text = s.substring(sp + 1);
      if (n == 1) sendText(dsts[0], text.c_str());          // cas classique : une seule destination
      else        sendTextMulti(dsts, n, text.c_str());     // plusieurs destinations
    } else {
      Serial.println("Usage : /to <ID hexa>[,<ID hexa>,...] <texte>");
    }
    return;
  }

  sendText(BROADCAST_ID, s.c_str());
}

// ============================================================================
// CALLBACKS BLE (exécutés dans la tâche BLE : on ne fait que mettre en file)
// ============================================================================
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    // La pile arrête la publicité à chaque connexion : on la relance
    // tant qu'il reste de la place pour un autre téléphone.
    advActive = false;
    Serial.printf("[BLE] Téléphone connecté (%u/%u)\n",
                  (unsigned)s->getConnectedCount(), (unsigned)MAX_BLE_CLIENTS);
    scheduleAdvertising(200);
  }
  void onDisconnect(BLEServer* s) override {
    advActive = false;
    Serial.printf("[BLE] Téléphone déconnecté (%u/%u)\n",
                  (unsigned)s->getConnectedCount(), (unsigned)MAX_BLE_CLIENTS);
    scheduleAdvertising(300);
  }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    size_t len = c->getLength();
    uint8_t* data = c->getData();
    if (len < HEADER_SIZE || len > MAX_PACKET_LEN || data == nullptr) return;
    uint8_t next = (rxHead + 1) % BLE_RX_QUEUE;
    if (next == rxTail) return;               // file pleine
    bleRx[rxHead].len = (uint8_t)len;
    memcpy(bleRx[rxHead].data, data, len);
    rxHead = next;
  }
};

// ============================================================================
// SETUP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.printf("\n=== LoRaMesh | nœud 0x%04X (%s) ===\n", MY_NODE_ID, MY_NAME);

  // LoRa
  SPI.begin(SCK_PIN, MISO_PIN, MOSI_PIN, SS_PIN);
  LoRa.setPins(SS_PIN, RST_PIN, DIO0_PIN);
  if (!LoRa.begin(LORA_FREQ)) {
    Serial.println("ERREUR : module LoRa introuvable (vérifier câblage SPI, 3.3V, GND)");
    while (true) delay(1000);
  }
  LoRa.setSyncWord(LORA_SYNC_WORD);
  LoRa.setTxPower(LORA_TX_POWER);
  LoRa.enableCrc();
  LoRa.receive();
  Serial.println("LoRa OK");

  // BLE
  char bleName[30];
  snprintf(bleName, sizeof(bleName), "ESP32_Mesh_0x%04X", MY_NODE_ID);
  BLEDevice::init(bleName);
  BLEDevice::setMTU(247);   // chaque téléphone doit aussi demander 247 (requestMtu)

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());
  BLEService* svc = pServer->createService(SERVICE_UUID);

  pTxCharacteristic = svc->createCharacteristic(CHARACTERISTIC_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  pTxCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic* rx = svc->createCharacteristic(
      CHARACTERISTIC_RX_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());

  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  advActive = true;
  Serial.printf("BLE OK, jusqu'à %u téléphones simultanés\n", (unsigned)MAX_BLE_CLIENTS);

  sendHello();
  sendName();
  printHelp();
}

// ============================================================================
// BOUCLE PRINCIPALE
// ============================================================================
void loop() {
  static uint32_t nextHello = millis() + HELLO_INTERVAL_MS;
  static uint32_t nextName  = millis() + NAME_INTERVAL_MS;
  static uint32_t lastClean = 0;
  static uint32_t lastAdvCheck = 0;

  // 1. Réception LoRa
  int sz = LoRa.parsePacket();
  if (sz > 0) {
    if (sz >= HEADER_SIZE && sz <= MAX_PACKET_LEN) {
      MeshPacket p;
      uint8_t* raw = (uint8_t*)&p;
      int n = 0;
      while (LoRa.available() && n < sz) raw[n++] = (uint8_t)LoRa.read();
      handlePacket(&p, n, FROM_LORA);
    } else {
      while (LoRa.available()) LoRa.read();   // paquet invalide : on vide
    }
  }

  // 2. Paquets reçus des téléphones (BLE)
  while (rxTail != rxHead) {
    MeshPacket p;
    size_t n = bleRx[rxTail].len;
    memcpy(&p, bleRx[rxTail].data, n);
    rxTail = (rxTail + 1) % BLE_RX_QUEUE;
    handlePacket(&p, n, FROM_BLE);
  }

  // 3. Envois LoRa planifiés
  serviceTxQueue();

  // 4. Commandes série
  if (Serial.available() > 0) {
    handleSerialLine(Serial.readStringUntil('\n'));
  }

  // 5. HELLO périodique (avec jitter)
  if ((int32_t)(millis() - nextHello) >= 0) {
    sendHello();
    nextHello = millis() + HELLO_INTERVAL_MS + random(0, 3000);
  }
  if ((int32_t)(millis() - nextName) >= 0) {          // NOUVEAU
    sendName();
    nextName = millis() + NAME_INTERVAL_MS + random(0, 10000);
  }

  // 6. Publicité BLE : relancée tant qu'il reste de la place pour un téléphone
  if (restartAdv && (int32_t)(millis() - restartAdvAt) >= 0) {
    restartAdv = false;
    if (bleClients() < MAX_BLE_CLIENTS) {
      BLEDevice::startAdvertising();
      advActive = true;
      Serial.printf("[BLE] Publicité active (%u/%u connectés)\n",
                    bleClients(), (unsigned)MAX_BLE_CLIENTS);
    }
  }

  // 6b. Filet de sécurité : si la publicité est arrêtée alors qu'il reste de
  //     la place et qu'aucune relance n'est prévue, on en programme une.
  if (millis() - lastAdvCheck > ADV_WATCHDOG_MS) {
    lastAdvCheck = millis();
    if (!advActive && !restartAdv && bleClients() < MAX_BLE_CLIENTS) {
      scheduleAdvertising(0);
    }
  }

  // 7. Nettoyage des voisins expirés
  if (millis() - lastClean > 10000) {
    cleanNeighbors();
    lastClean = millis();
  }

  delay(2);
}
