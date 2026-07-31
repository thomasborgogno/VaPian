#!/usr/bin/env bash
# Builds one self-hosted vector-tile .mbtiles per Italian Geofabrik
# macro-zone (zones.json): downloads each zone's already-clipped OSM
# extract directly from Geofabrik, then Planetiler builds OpenMapTiles-
# schema tiles from it — no bounding-box clipping tool needed, unlike the
# 20-region approach this replaced. Run by
# .github/workflows/offline_tiles.yml; also runnable locally (see
# README.md) as long as Java 21+ and `jq` are installed.
set -euo pipefail

ZONES_JSON="$1"
PLANETILER_JAR="$2"
OUTPUT_DIR="$3"

mkdir -p "$OUTPUT_DIR"
DOWNLOAD_DIR="$(mktemp -d)"
trap 'rm -rf "$DOWNLOAD_DIR"' EXIT

count=$(jq '.zones | length' "$ZONES_JSON")
for i in $(seq 0 $((count - 1))); do
  zone=$(jq -c ".zones[$i]" "$ZONES_JSON")
  name=$(echo "$zone" | jq -r '.name')
  slug=$(echo "$zone" | jq -r '.slug')

  extract_path="$DOWNLOAD_DIR/$slug.osm.pbf"
  output_path="$OUTPUT_DIR/$name.mbtiles"

  echo "=== $name: downloading $slug extract ==="
  curl -L -o "$extract_path" "https://download.geofabrik.de/europe/italy/$slug-latest.osm.pbf"

  echo "=== $name: building .mbtiles ==="
  java -Xmx6g -jar "$PLANETILER_JAR" \
    --osm-path="$extract_path" \
    --output="$output_path" \
    --download \
    --force

  rm -f "$extract_path"
  echo "=== $name: done ($(du -h "$output_path" | cut -f1)) ==="
done
