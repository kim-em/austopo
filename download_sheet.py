#!/usr/bin/env python3
"""Download individual Getlost Maps sheets from Google Drive.

Usage:
    # Search for sheets by name pattern:
    python3 download_sheet.py search feathertop
    python3 download_sheet.py search kosciuszko
    python3 download_sheet.py search "blue mountains"

    # Download a specific sheet by name (case-insensitive substring match):
    python3 download_sheet.py download feathertop [output_dir]

    # List all available catalogues:
    python3 download_sheet.py catalogues

    # Refresh the catalogue cache (re-fetches from Google Drive):
    python3 download_sheet.py refresh

Requires: gdown (install with: uv tool install gdown)

The script maintains a local cache of sheet catalogues in ~/.cache/getlost-maps/.
On first use, it fetches the folder listings from Google Drive (~30-60s per catalogue).
"""

import json
import os
import subprocess
import sys

CACHE_DIR = os.path.expanduser("~/.cache/getlost-maps")

# Google Drive folder IDs for GeoTIFF downloads
CATALOGUES = {
    "vic-25k": {
        "name": "Victoria 25k (V16b)",
        "folder_id": "1CC5IPK1FYWW8MjmyeZvy9y3z27pdXNck",
    },
    "vic-75k": {
        "name": "Victoria 75k (V16b)",
        "folder_id": "1h23CVyFNz5JkQY-5YYMKD-n95sRsHuOY",
    },
    "nsw-25k": {
        "name": "NSW 25k (V15)",
        "folder_id": "1bGtfRPUZpx3klcfnyIpBvYzBaGOLGYSu",
    },
    "nsw-75k": {
        "name": "NSW 75k (V15)",
        "folder_id": "1ED3FX3ULwdvEZpEgXXit3jDaS_DwKCwFP",
    },
    "tas-75k": {
        "name": "Tasmania 75k (V15)",
        "folder_id": "1tKukmw_mrUfe1qAbh66cD-NStFyL8DFE",
    },
    "qld-75k": {
        "name": "Queensland 75k (V15)",
        "folder_id": "1EPkV7DM41oJa78-vq8wpchW6hew_4Qe9",
    },
    "sa-75k": {
        "name": "South Australia 75k (V15)",
        "folder_id": "1XaU6iZZBSBgbvxhkeuK3WZxuvRV3pypN",
    },
    "wa-75k": {
        "name": "Western Australia 75k (V15)",
        "folder_id": "1TKtwpLZIgW0DXkzLUQLrENhrm4wAhkIk",
    },
    "nt-75k": {
        "name": "Northern Territory 75k (V14b)",
        "folder_id": "1yYks8toWNGjXzcZ1sykIwm5TNgr5Ldps",
    },
    "aus-250k": {
        "name": "Australia 250k (V15)",
        "folder_id": "1YZoZ7u5NremN95tuwM90xEoB50Ep28Vq",
    },
}


def ensure_cache():
    os.makedirs(CACHE_DIR, exist_ok=True)


def cache_path(cat_id):
    return os.path.join(CACHE_DIR, f"{cat_id}.json")


def fetch_catalogue(cat_id):
    """Fetch file listing from Google Drive folder using gdown."""
    cat = CATALOGUES[cat_id]
    print(f"Fetching {cat['name']} catalogue from Google Drive...", file=sys.stderr)

    url = f"https://drive.google.com/drive/folders/{cat['folder_id']}"
    result = subprocess.run(
        ["gdown", "--folder", url, "-O", "/tmp/gdown-dummy/"],
        capture_output=True, text=True, timeout=120
    )

    # Parse the output for file entries
    files = []
    for line in (result.stdout + result.stderr).splitlines():
        if line.startswith("Processing file "):
            parts = line[len("Processing file "):].split(" ", 1)
            if len(parts) == 2:
                files.append({"id": parts[0], "name": parts[1]})

    if not files:
        print(f"Warning: no files found for {cat_id}", file=sys.stderr)
        return []

    # Cache it
    ensure_cache()
    with open(cache_path(cat_id), "w") as f:
        json.dump(files, f)

    print(f"  Cached {len(files)} files", file=sys.stderr)
    return files


def load_catalogue(cat_id):
    """Load catalogue from cache, fetching if needed."""
    cp = cache_path(cat_id)
    if os.path.exists(cp):
        with open(cp) as f:
            return json.loads(f.read())
    return fetch_catalogue(cat_id)


def search_all(pattern):
    """Search all catalogues for sheets matching pattern."""
    pattern_lower = pattern.lower()
    results = []

    for cat_id in CATALOGUES:
        cp = cache_path(cat_id)
        if not os.path.exists(cp):
            continue
        files = load_catalogue(cat_id)
        for f in files:
            # Match against the name portion (strip extension and version)
            name = f["name"].replace("_GetlostMap_", " ").replace("_", " ")
            if pattern_lower in name.lower():
                results.append((cat_id, f))

    return results


def cmd_search(pattern):
    # First check which catalogues are cached
    ensure_cache()
    uncached = [cid for cid in CATALOGUES if not os.path.exists(cache_path(cid))]
    if uncached:
        print(f"Note: {len(uncached)} catalogues not yet cached. Run 'refresh' to fetch all.",
              file=sys.stderr)
        # Auto-fetch commonly used ones
        for cid in ["vic-25k", "nsw-25k"]:
            if cid in uncached:
                fetch_catalogue(cid)

    results = search_all(pattern)
    if not results:
        print(f"No sheets matching '{pattern}' found.")
        print("Try 'refresh' to fetch all catalogues, or search with a different term.")
        return

    print(f"\nSheets matching '{pattern}':\n")
    for cat_id, f in results:
        cat_name = CATALOGUES[cat_id]["name"]
        name = f["name"]
        print(f"  [{cat_name}] {name}")
        print(f"    gdown {f['id']} -O {name}")
    print(f"\n{len(results)} result(s)")


def cmd_download(pattern, output_dir="."):
    ensure_cache()
    # Auto-fetch common catalogues if not cached
    for cid in ["vic-25k", "nsw-25k"]:
        if not os.path.exists(cache_path(cid)):
            fetch_catalogue(cid)

    results = search_all(pattern)
    if not results:
        print(f"No sheets matching '{pattern}'. Try 'search' first.")
        sys.exit(1)

    if len(results) > 5:
        print(f"Too many matches ({len(results)}). Narrow your search:")
        for _, f in results[:10]:
            print(f"  {f['name']}")
        if len(results) > 10:
            print(f"  ... and {len(results) - 10} more")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    for cat_id, f in results:
        out_path = os.path.join(output_dir, f["name"])
        if os.path.exists(out_path):
            print(f"Already exists: {out_path}")
            continue
        print(f"Downloading {f['name']}...")
        subprocess.run(["gdown", f["id"], "-O", out_path])
        print(f"  Saved to {out_path}")


def cmd_refresh():
    ensure_cache()
    for cat_id in CATALOGUES:
        fetch_catalogue(cat_id)
    print(f"\nAll {len(CATALOGUES)} catalogues refreshed.")


def cmd_catalogues():
    ensure_cache()
    for cat_id, cat in CATALOGUES.items():
        cp = cache_path(cat_id)
        if os.path.exists(cp):
            with open(cp) as f:
                count = len(json.loads(f.read()))
            print(f"  {cat_id:12s}  {cat['name']:30s}  {count} sheets (cached)")
        else:
            print(f"  {cat_id:12s}  {cat['name']:30s}  (not cached)")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)

    cmd = sys.argv[1]

    if cmd == "search":
        if len(sys.argv) < 3:
            print("Usage: download_sheet.py search <pattern>")
            sys.exit(1)
        cmd_search(" ".join(sys.argv[2:]))
    elif cmd == "download":
        if len(sys.argv) < 3:
            print("Usage: download_sheet.py download <pattern> [output_dir]")
            sys.exit(1)
        output_dir = sys.argv[3] if len(sys.argv) > 3 else "."
        cmd_download(" ".join(sys.argv[2:-1]) if len(sys.argv) > 3 else sys.argv[2], output_dir)
    elif cmd == "refresh":
        cmd_refresh()
    elif cmd in ("catalogues", "catalogs", "list"):
        cmd_catalogues()
    else:
        print(f"Unknown command: {cmd}")
        print("Commands: search, download, refresh, catalogues")
        sys.exit(1)


if __name__ == "__main__":
    main()
