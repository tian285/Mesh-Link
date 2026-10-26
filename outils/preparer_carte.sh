#!/usr/bin/env bash
# Prépare une carte hors ligne (.pmtiles) pour l'application BLE Mesh — Linux / macOS.
# Prérequis : l'outil « pmtiles » (https://github.com/protomaps/go-pmtiles/releases).
# Usage : ./preparer_carte.sh [bbox lon_min,lat_min,lon_max,lat_max] [maxzoom] [sortie]
set -euo pipefail
BBOX="${1:-47.40,-19.05,47.65,-18.78}"   # Antananarivo
MAXZOOM="${2:-15}"
SORTIE="${3:-carte.pmtiles}"
URL=""
for i in $(seq 1 10); do
  JOUR=$(date -d "-$i day" +%Y%m%d 2>/dev/null || date -v-"$i"d +%Y%m%d)
  if curl -sfI "https://build.protomaps.com/$JOUR.pmtiles" >/dev/null; then URL="https://build.protomaps.com/$JOUR.pmtiles"; break; fi
done
[ -n "$URL" ] || { echo "Aucune carte récente trouvée, voir https://maps.protomaps.com/builds" >&2; exit 1; }
echo "Source : $URL"
pmtiles extract "$URL" "$SORTIE" --bbox="$BBOX" --maxzoom="$MAXZOOM"
echo "Terminé : $SORTIE → copie-le sur le téléphone, puis onglet Carte > Carte > Importer."
