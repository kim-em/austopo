#!/usr/bin/env python3
"""Convert GeoTIFF to JPEG + JSON metadata for TopoView Android app.

Usage:
    python3 convert_geotiff.py input.tif [output_dir]

Requires: gdal_translate, gdalinfo (from GDAL)

Output:
    output_dir/name.jpg     - JPEG image
    output_dir/name.json    - Metadata sidecar
"""

import json
import os
import subprocess
import sys


def run(cmd):
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error running {' '.join(cmd)}:", file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        sys.exit(1)
    return result.stdout


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    input_path = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(input_path) or "."

    if not os.path.exists(input_path):
        print(f"Error: {input_path} not found", file=sys.stderr)
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # Get metadata from gdalinfo
    info_json = run(["gdalinfo", "-json", input_path])
    info = json.loads(info_json)

    # Extract geotransform: [originX, pixelSizeX, rotX, originY, rotY, pixelSizeY]
    gt = info["geoTransform"]
    origin_x, pixel_size_x, rot_x = gt[0], gt[1], gt[2]
    origin_y, rot_y, pixel_size_y = gt[3], gt[4], gt[5]

    if abs(rot_x) > 1e-10 or abs(rot_y) > 1e-10:
        print("Warning: image has rotation, GPS overlay may be inaccurate", file=sys.stderr)

    width = info["size"][0]
    height = info["size"][1]

    # Check CRS
    crs = "EPSG:3857"  # assume Web Mercator
    crs_info = info.get("coordinateSystem", {}).get("wkt", "")
    if "3857" in crs_info or "Pseudo-Mercator" in crs_info or "Web Mercator" in crs_info:
        crs = "EPSG:3857"
    elif "4326" in crs_info:
        crs = "EPSG:4326"
        print("Warning: CRS is EPSG:4326, coordinate conversion may need adjustment",
              file=sys.stderr)
    else:
        print(f"Warning: CRS not recognized as EPSG:3857, assuming Web Mercator",
              file=sys.stderr)

    # Generate output name from input filename
    base_name = os.path.splitext(os.path.basename(input_path))[0]
    # Clean up name for display
    display_name = base_name.replace("_", " ").replace("-", " ")

    jpg_path = os.path.join(output_dir, base_name + ".jpg")
    json_path = os.path.join(output_dir, base_name + ".json")

    # Check if source is already JPEG compressed
    compression = ""
    for band in info.get("bands", []):
        meta = band.get("metadata", {}).get("", {})
        if "COMPRESSION" in str(info.get("metadata", {})):
            break
    source_meta = info.get("metadata", {}).get("", {})
    compression = source_meta.get("COMPRESSION", "").upper()

    quality = "85"
    if compression == "JPEG":
        quality = "90"  # higher quality since source is already JPEG
        print(f"Source is JPEG-compressed, using quality={quality}")

    # Convert to JPEG
    print(f"Converting {input_path} -> {jpg_path}")
    run(["gdal_translate", "-of", "JPEG", "-co", f"QUALITY={quality}",
         input_path, jpg_path])

    # Write metadata JSON
    meta = {
        "name": display_name,
        "image": os.path.basename(jpg_path),
        "crs": crs,
        "origin_x": origin_x,
        "origin_y": origin_y,
        "pixel_size_x": pixel_size_x,
        "pixel_size_y": pixel_size_y,
        "width": width,
        "height": height
    }

    with open(json_path, "w") as f:
        json.dump(meta, f, indent=2)

    print(f"Metadata written to {json_path}")
    jpg_size_mb = os.path.getsize(jpg_path) / (1024 * 1024)
    print(f"JPEG size: {jpg_size_mb:.0f} MB")
    print(f"\nTo push to phone:")
    print(f"  adb push {jpg_path} /sdcard/TopoMaps/")
    print(f"  adb push {json_path} /sdcard/TopoMaps/")


if __name__ == "__main__":
    main()
