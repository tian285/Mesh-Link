# Prépare une carte hors ligne (.pmtiles) pour l'application BLE Mesh — Windows PowerShell.
# Prérequis : pmtiles.exe (https://github.com/protomaps/go-pmtiles/releases, archive Windows_x86_64).
# Exemple : .\preparer_carte.ps1 -Pmtiles .\pmtiles.exe -Bbox "47.40,-19.05,47.65,-18.78" -MaxZoom 15
param(
    [string]$Pmtiles = ".\pmtiles.exe",
    [string]$Bbox = "47.40,-19.05,47.65,-18.78",   # lon_min,lat_min,lon_max,lat_max (Antananarivo)
    [int]$MaxZoom = 15,                              # 14 = quartier lisible, 15 = rues, 16 = détails (x2 par niveau)
    [string]$Sortie = "carte.pmtiles"
)
$url = $null
for ($i = 1; $i -le 10; $i++) {
    $jour = (Get-Date).AddDays(-$i).ToString("yyyyMMdd")
    $essai = "https://build.protomaps.com/$jour.pmtiles"
    try { Invoke-WebRequest -Method Head -Uri $essai -UseBasicParsing | Out-Null; $url = $essai; break } catch { }
}
if (-not $url) { throw "Aucune carte mondiale récente trouvée. Voir la liste sur https://maps.protomaps.com/builds" }
Write-Host "Source : $url"
# Seules les tuiles de la zone sont téléchargées (requêtes HTTP partielles), pas le monde entier.
& $Pmtiles extract $url $Sortie "--bbox=$Bbox" "--maxzoom=$MaxZoom"
Write-Host "Terminé : $Sortie. Copie ce fichier sur le téléphone puis, dans l'appli : onglet Carte > Carte > Importer."
