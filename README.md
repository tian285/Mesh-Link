# BLE Mesh v2 : application Android

Messagerie hors réseau. Les téléphones se parlent en **BLE** (Bluetooth Low Energy) et, quand une passerelle **ESP32 + LoRa** est à portée, le message part aussi en radio longue portée. Firmware associé : `firmware/LoRaMesh.ino`.

## Nouveautés de la v2

| Sujet | Avant | Maintenant |
|---|---|---|
| Relais téléphone → téléphone | 1 seul voisin, choisi au hasard | Plusieurs liens BLE en parallèle, connexion directe au destinataire s'il est à portée |
| Ordre d'envoi | Passerelle d'abord, même si le destinataire était à côté | 1. destinataire direct → 2. passerelle LoRa → 3. inondation vers les téléphones |
| Anti-boucle | Doublons + TTL | Doublons atomiques + TTL + « split horizon », vérifiés par simulation (32 tests unitaires au total) |
| Identité | Code hexadécimal (`0x1A2B`) | **Pseudo** choisi par chacun + surnom local modifiable; l'ID reste caché |
| Positions | Bouton « Voir » vers une autre application | **Carte hors ligne intégrée** (MapLibre + fichier PMTiles), pseudos sur la carte |
| Annonce BLE | Dépassait 31 octets (refusée silencieusement par Android) | UUID dans l'annonce, ID dans la réponse au scan |

## 1. Ouvrir et lancer

1. **Android Studio** Koala (2024.1) ou plus récent, SDK Android 34.
2. `File > Open` → dossier `BleMeshApp`. La première synchronisation télécharge Gradle 8.7, Compose et **MapLibre 11.8.8** (Internet nécessaire une fois).
3. Téléphone Android 8.0+ (API 26), débogage USB activé, **Run**. L'émulateur ne convient pas (pas de vrai Bluetooth). L'APK ne contient que les architectures ARM des vrais téléphones.

## 2. Utilisation

1. **Profil** (en haut) : choisis ton pseudo (24 octets max). L'ID technique est généré tout seul; ne le change qu'en cas de doublon.
2. Onglet **Passerelle** : accorde les autorisations Bluetooth. Le mesh démarre (ensuite il démarre tout seul à chaque lancement). Une passerelle ESP32 proche est connectée automatiquement.
3. Onglet **Contacts** : toutes les personnes entendues, avec leur pseudo. « Renommer » te permet de leur donner ton propre surnom (il reste sur ton téléphone). « Écrire » ouvre le chat vers elles.
4. Onglet **Chat** : bouton « À : … » → choisis le destinataire par son pseudo (recherche par nom ou ID), ou « Tout le monde ». Sous chaque message envoyé : le chemin utilisé (direct BLE, via passerelle LoRa, relayé par les téléphones) et « ✓ reçu » quand l'accusé revient.
5. Onglet **Carte** : « Ma position » (affichée seulement chez toi), « Partager » (envoyée à tout le réseau), et la position de chaque membre avec son pseudo. Touche un point pour voir ses coordonnées ou lui écrire.

## 3. Routage et anti-boucle

### Émission d'un message (téléphone émetteur)

```
destinataire relié en BLE ?  ── oui ──► envoi DIRECT à lui seul
        │ non (mais visible au scan ? → l'appli ouvre un lien direct, 4 s max, puis reteste)
        ▼
passerelle ESP32 connectée ? ── oui ──► envoi à la PASSERELLE (LoRa)
        │ non
        ▼
INONDATION : envoi à tous les téléphones reliés, qui relaient
```

Une diffusion (`Tout le monde`, position, annonces) part sur tous les liens. Si l'accusé (ACK) n'arrive pas, 2 nouvelles tentatives (20 s d'écart) partent sur **tous** les liens, avec le même identifiant.

### Téléphone non destinataire (relais)

À la réception, dans cet ordre (`data/mesh/MeshRouter.kt`) :

1. **Écho** : le paquet vient de moi → ignoré.
2. **Identifiant déjà vu** : la clé `(src_id, msg_id)` (le « hash id » du message, 48 bits) est dans le cache → ignoré **et pas relayé**. Le test et l'enregistrement sont atomiques : deux copies arrivant en même temps par deux liens ne passent pas toutes les deux.
3. **Pas pour moi** : le message n'est pas affiché (il n'apparaît que chez le destinataire, ou chez tous pour une diffusion).
4. **TTL** : si TTL ≤ 1, on s'arrête. Sinon TTL − 1 puis relais : directement au destinataire s'il est relié, sinon à la passerelle + aux autres téléphones, **jamais vers le lien d'arrivée** (« split horizon ») ni vers la passerelle d'où vient le paquet.

Ces trois verrous rendent les boucles impossibles : chaque téléphone relaie un message donné **au plus une fois**, et le TTL borne le nombre de sauts même si le cache était vidé. Les tests `MeshRouterTest` le vérifient sur un anneau, un graphe complet de 7 téléphones, deux îlots reliés à la fois en BLE et en LoRa, et une chaîne de 9 téléphones (portée exacte de 5 sauts). L'ESP32 y est simulé à partir du code de `handlePacket()` du firmware.

## 4. Pseudos

L'en-tête binaire (10 octets) ne change pas : l'ID 16 bits reste l'adresse technique, compatible avec l'ESP32. Le pseudo voyage dans le **payload** :

| Paquet | Contenu ajouté | Portée |
|---|---|---|
| HELLO (type 2, toutes les 20 s) | pseudo UTF-8 | voisins directs |
| NAME (type 4, nouveau, toutes les 5 min et à chaque changement) | pseudo UTF-8 | tout le réseau (relayé, TTL 5) |
| GPS (type 1) | pseudo après les 8 octets lat/lon | tout le réseau |

Un ESP32 non mis à jour relaie ces paquets sans les comprendre : il n'y a rien à casser. L'annuaire (pseudo annoncé + surnom local) est enregistré sur le téléphone. En cas d'homonymes, l'ID est ajouté au nom (`Haja #1A2B`).

## 5. Carte hors ligne

L'application utilise **MapLibre Native** et lit une archive **PMTiles** (un seul fichier contenant toutes les tuiles d'une zone). Le style (fond OpenStreetMap Protomaps, libellés en français) et les polices sont embarqués dans l'APK : **aucune connexion n'est utilisée** pendant l'affichage.

### Préparer la carte (une fois, sur un ordinateur connecté)

1. Télécharger l'outil `pmtiles` : <https://github.com/protomaps/go-pmtiles/releases> (Windows_x86_64, Linux ou macOS).
2. Lancer le script fourni dans `outils/` :
   - Windows : `powershell -ExecutionPolicy Bypass -File outils\preparer_carte.ps1 -Pmtiles .\pmtiles.exe`
   - Linux/macOS : `./outils/preparer_carte.sh`

   Par défaut : Antananarivo (`47.40,-19.05,47.65,-18.78`), zoom 15. Autre zone : passe un autre rectangle `lon_min,lat_min,lon_max,lat_max` (par ex. obtenu sur <https://boundingbox.klokantech.com>, format CSV). Seule la zone demandée est téléchargée. Chaque niveau de zoom en plus double à peu près la taille : vise le zoom 14 pour une région, 15 à 16 pour une ville.
3. Copier `carte.pmtiles` sur le téléphone (câble USB, Bluetooth, carte SD…).
4. Appli : onglet **Carte** → **Carte ▾** → **Importer une carte (.pmtiles)**. Le fichier est vérifié (en-tête PMTiles v3) puis copié dans le stockage privé de l'appli; la carte se centre sur la zone.

Sans carte importée, les positions s'affichent quand même sur un fond uni. Une carte d'images (`.mbtiles` produit par un autre outil) se convertit avec `pmtiles convert carte.mbtiles carte.pmtiles`; l'appli gère les deux types (vectoriel et images).

Données : © contributeurs OpenStreetMap (licence ODbL), carte de base Protomaps. Polices : Noto Sans (SIL OFL 1.1), voir `app/src/main/assets/glyphs/NOTICE.txt`.

## 6. Firmware (`firmware/LoRaMesh.ino`)

Modifications, toutes compatibles avec l'ancien format :

- `#define MY_NAME "Passerelle-A"` : pseudo de la passerelle, envoyé dans ses HELLO et dans une annonce NAME toutes les 5 min.
- Doublons détectés sur `(src_id, msg_id)` comme dans l'appli (avant : `msg_id` seul).
- Le Moniteur Série affiche les pseudos reçus (`[PSEUDO]`) et le pseudo joint aux positions GPS.

Donne un `MY_NODE_ID` **et** un `MY_NAME` différents à chaque carte.

## 7. Vérifier sur la maquette

Téléphones Android réels, appli au premier plan, autorisations accordées, pseudos distincts (A, B, C).

1. **Direct** : A et B côte à côte, sans ESP32. A écrit à B → « direct BLE », un seul message chez B, « ✓ reçu » chez A.
2. **Relais par téléphone** : C au milieu, A et B hors de portée l'un de l'autre. A écrit à B → « relayé par les téléphones », rien ne s'affiche chez C, « ✓ reçu » chez A.
3. **Téléphone → ESP32** : C connecté à un ESP32, A sans passerelle. A écrit à un téléphone de l'autre côté du lien LoRa. Le Moniteur Série montre la trame.
4. **Boucle** : 4 téléphones en carré + 2 ESP32. Un message « Tout le monde » doit apparaître **une seule fois** partout.
5. **Carte** : chacun appuie sur « Partager »; les points portent les pseudos.

## 8. Tests et compilation

- Tests unitaires (format binaire, routeur, anti-boucle, pseudos, en-tête PMTiles) : `gradle :app:testDebugUnitTest`, ou dans Android Studio la tâche `app > verification > testDebugUnitTest`.
- Compilation : `Build > Make Project`. Versions : Android Gradle Plugin 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.03, compileSdk 34, MapLibre 11.8.8.

## 9. Structure

```
app/src/main/
├── assets/map/style-vector.json        Style de carte hors ligne (généré depuis @protomaps/basemaps, fr)
├── assets/glyphs/NotoSans-Regular/     Polices de la carte (latin)
└── java/com/example/blemesh/
    ├── domain/model/    MeshPacket (+ clé « hash id »), Contact/DisplayNames, Pseudo, MeshRoute…
    ├── domain/usecase/  Envoi, profil, renommage, localisation…
    ├── data/mesh/       MeshRouter (routage + anti-boucle), SeenCache, MeshTransport  ← sans Android, testé
    ├── data/ble/        PhoneMeshBle (liens téléphone ↔ téléphone), BleClient (passerelle ESP32)
    ├── data/maps/       PmtilesHeader, MapStyles, MapFileStore (import de la carte)
    ├── data/local/      NodeIdStore, ProfileStore, ContactStore
    ├── data/repository/ MeshRepositoryImpl (messages, ACK, annuaire, HELLO/NAME)
    └── ui/              chat/, contacts/, map/ (OfflineMap = MapLibre dans Compose), connect/, components/
outils/                  Scripts de préparation de la carte (PowerShell, bash)
firmware/LoRaMesh.ino    Firmware ESP32 mis à jour
```

## 10. Limites connues

- Historique des messages et positions en mémoire seulement (perdu à la fermeture). L'annuaire, lui, est conservé.
- Pas de service au premier plan : garder l'appli ouverte pendant les essais, Android peut couper le BLE en arrière-plan.
- Android accepte environ 7 connexions BLE simultanées : l'appli ouvre au plus 4 liens sortants (+ les entrants).
- Le BLE téléphone-à-téléphone ne s'accompagne d'aucun calcul de route : c'est une inondation contrôlée. Elle est fiable pour quelques dizaines de téléphones, pas pour des centaines.
- Messages : 230 octets de contenu max. Pseudo : 24 octets.
- Android 11 et moins : la localisation doit être activée pour scanner en BLE.
- Le code Android (BLE, écrans, carte) n'a pas pu être compilé hors Android Studio : en cas d'erreur de compilation, copier le message exact de l'onglet `Build`.
