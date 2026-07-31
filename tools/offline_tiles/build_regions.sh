#!/usr/bin/env bash
# Builds one self-hosted vector-tile .mbtiles per Italian region (regions.json)
# from a single Italy-wide OSM extract: osmium clips each region's bounding
# box out of the extract, then Planetiler builds OpenMapTiles-schema tiles
# from that clip. Run by .github/workflows/offline_tiles.yml; also runnable
# locally (see README.md's "esecuzione manuale" section) as long as
# `osmium` (osmium-tool) and Java 21+ are installed.
set -euo pipefail

REGIONS_JSON="$1"
OSM_PATH="$2"
PLANETILER_JAR="$3"
OUTPUT_DIR="$4"

mkdir -p "$OUTPUT_DIR"
CLIP_DIR="$(mktemp -d)"
trap 'rm -rf "$CLIP_DIR"' EXIT

count=$(jq '.regions | length' "$REGIONS_JSON")
for i in $(seq 0 $((count - 1))); do
  region=$(jq -c ".regions[$i]" "$REGIONS_JSON")
  name=$(echo "$region" | jq -r '.name')
  minLat=$(echo "$region" | jq -r '.minLat')
  maxLat=$(echo "$region" | jq -r '.maxLat')
  minLng=$(echo "$region" | jq -r '.minLng')
  maxLng=$(echo "$region" | jq -r '.maxLng')

  clip_path="$CLIP_DIR/$name.osm.pbf"
  output_path="$OUTPUT_DIR/$name.mbtiles"

  echo "=== $name: clipping bbox $minLng,$minLat,$maxLng,$maxLat ==="
  osmium extract --bbox "$minLng,$minLat,$maxLng,$maxLat" "$OSM_PATH" -o "$clip_path" --overwrite

  echo "=== $name: building .mbtiles ==="
  java -Xmx6g -jar "$PLANETILER_JAR" \
    --osm-path="$clip_path" \
    --output="$output_path" \
    --download \
    --force

  rm -f "$clip_path"
  echo "=== $name: done ($(du -h "$output_path" | cut -f1)) ==="
done
