#!/usr/bin/env python3
"""
fix_ebook_separators.py

Replace decorative header / divider images (the ones that force a Kindle
e-ink full refresh) with plain text  * * *  and write Kindle-ready AZW3.

Supported inputs: .epub, .mobi, .azw3
Output: always  original_name_fixed.azw3  (same folder, originals untouched)

Pipeline:
  epub  → fix HTML in epub → Calibre → azw3
  mobi  → Calibre → epub → fix → Calibre → azw3
  azw3  → Calibre → epub → fix → Calibre → azw3

Requires Calibre (ebook-convert):
  https://calibre-ebook.com/download

Usage:
    python3 fix_ebook_separators.py /path/to/ebook/folder
    python3 fix_ebook_separators.py /path/to/single_book.azw3
    python3 fix_ebook_separators.py /path/to/book.azw3 --dry-run
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path
from urllib.parse import unquote

from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)

# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------

# Filename / class / id / alt tokens that almost always mean decoration.
SEPARATOR_HINTS = [
    "separator", "divider", "ornament", "flourish", "filigree", "asterism",
    "dinkus", "scenebreak", "scene-break", "scene_break", "rule", "hr_", "_hr",
    "break", "deco", "tribal", "swirl", "fleuron", "vine", "border",
    "chaphead", "chap-head", "chapterhead", "header-orn", "ornamental",
]

# Tokens that mean "leave this image alone" unless it is clearly a thin bar.
PROTECT_HINTS = [
    "cover", "frontispiece", "portrait", "photo", "map", "diagram",
    "illustration", "figure", "comic", "panel", "logo-publisher",
]

REPLACEMENT_HTML = '<p class="separator" style="text-align:center;">* * *</p>'

INPUT_EXTENSIONS = {".epub", ".mobi", ".azw3"}
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"}

KINDLE_OUTPUT_PROFILE = "kindle"
OUTPUT_SUFFIX = "_fixed"
OUTPUT_EXTENSION = ".azw3"

# Pixel heuristics (Kindle refresh is triggered by bitmap images, especially
# wide bars and chapter-header ornaments).
#
# Thin rule / thicker divider: short in height, usually page-width.
# Decorative chapter header: still short vs a real illustration.
DEFAULT_MAX_DIVIDER_HEIGHT = 80
DEFAULT_MAX_HEADER_HEIGHT = 180
DEFAULT_MIN_ASPECT = 3.0          # width / height
DEFAULT_MIN_WIDTH = 120

# ---------------------------------------------------------------------------


def find_ebook_convert():
    candidates = [
        shutil.which("ebook-convert"),
        "/Applications/calibre.app/Contents/MacOS/ebook-convert",
        os.path.expanduser("~/Applications/calibre.app/Contents/MacOS/ebook-convert"),
    ]
    for candidate in candidates:
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


def make_soup(html: str, filename: str) -> BeautifulSoup:
    lower = filename.lower()
    if lower.endswith(".xhtml") or lower.endswith(".opf") or lower.endswith(".ncx"):
        return BeautifulSoup(html, "xml")
    if "xmlns=" in html[:800] and ("xhtml" in html[:800].lower() or lower.endswith(".htm")):
        return BeautifulSoup(html, "xml")
    return BeautifulSoup(html, "lxml")


def parse_px(value):
    """Parse CSS/HTML size like '80', '80px', '60%' → int pixels, or None for %."""
    if value is None:
        return None
    text = str(value).strip().lower()
    if not text or text.endswith("%"):
        return None
    text = text.replace("px", "").replace("pt", "").strip()
    try:
        number = float(text)
    except ValueError:
        return None
    if number <= 0:
        return None
    return int(round(number))


def style_size(style, prop):
    if not style:
        return None
    match = re.search(rf"{prop}\s*:\s*([^;]+)", style, re.I)
    if not match:
        return None
    return parse_px(match.group(1))


def png_size(data: bytes):
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    return None


def gif_size(data: bytes):
    if len(data) >= 10 and data[:6] in (b"GIF87a", b"GIF89a"):
        return struct.unpack("<HH", data[6:10])
    return None


def jpeg_size(data: bytes):
    if not (len(data) >= 4 and data[:2] == b"\xff\xd8"):
        return None
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):
            height, width = struct.unpack(">HH", data[i + 5:i + 9])
            return width, height
        if marker in (0xD8, 0xD9) or marker == 0x01 or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if i + 3 >= len(data):
            break
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 2 + length
    return None


def webp_size(data: bytes):
    if len(data) < 30 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        return None
    kind = data[12:16]
    if kind == b"VP8 " and len(data) >= 30:
        w, h = struct.unpack("<HH", data[26:30])
        return w & 0x3FFF, h & 0x3FFF
    if kind == b"VP8L" and len(data) >= 25:
        bits = struct.unpack("<I", data[21:25])[0]
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if kind == b"VP8X" and len(data) >= 30:
        w = 1 + int.from_bytes(data[24:27], "little")
        h = 1 + int.from_bytes(data[27:30], "little")
        return w, h
    return None


def svg_size(data: bytes):
    try:
        text = data.decode("utf-8", errors="ignore")
    except Exception:
        return None
    view = re.search(r'viewBox\s*=\s*["\']([\d.\s-]+)["\']', text, re.I)
    if view:
        parts = view.group(1).split()
        if len(parts) == 4:
            try:
                return int(float(parts[2])), int(float(parts[3]))
            except ValueError:
                pass
    w = re.search(r'\bwidth\s*=\s*["\']([\d.]+)', text, re.I)
    h = re.search(r'\bheight\s*=\s*["\']([\d.]+)', text, re.I)
    if w and h:
        try:
            return int(float(w.group(1))), int(float(h.group(1)))
        except ValueError:
            return None
    return None


def image_pixel_size(path: Path):
    try:
        data = path.read_bytes()
    except OSError:
        return None
    suffix = path.suffix.lower()
    if suffix == ".png":
        return png_size(data)
    if suffix in (".jpg", ".jpeg"):
        return jpeg_size(data)
    if suffix == ".gif":
        return gif_size(data)
    if suffix == ".webp":
        return webp_size(data)
    if suffix == ".svg":
        return svg_size(data)
    return (
        png_size(data)
        or jpeg_size(data)
        or gif_size(data)
        or webp_size(data)
        or svg_size(data)
    )


def resolve_image_path(html_path: Path, src: str, root: Path):
    if not src:
        return None
    cleaned = unquote(src.split("?")[0].split("#")[0]).strip()
    if not cleaned or cleaned.startswith(("http://", "https://", "data:")):
        return None
    candidate = (html_path.parent / cleaned).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    if candidate.is_file():
        return candidate
    return None


def combined_text(*parts):
    return " ".join(p for p in parts if p).lower()


def has_hint(text: str, hints):
    return any(hint in text for hint in hints)


def html_declared_size(tag):
    style = tag.get("style", "") or ""
    width = parse_px(tag.get("width")) or style_size(style, "width")
    height = parse_px(tag.get("height")) or style_size(style, "height")
    width_is_percent = "width" in style.lower() and "%" in style.lower()
    attr_width = str(tag.get("width") or "")
    if attr_width.endswith("%"):
        width_is_percent = True
    return width, height, width_is_percent


def is_protected_content(text: str):
    return has_hint(text, PROTECT_HINTS)


def is_separator_shape(width, height, width_is_percent, limits):
    if height is None:
        return False
    max_divider = limits["max_divider_height"]
    max_header = limits["max_header_height"]
    min_aspect = limits["min_aspect"]
    min_width = limits["min_width"]

    # Hairline / thick horizontal rule.
    if height <= max_divider:
        if width_is_percent:
            return True
        if width is None:
            return True
        if width >= min_width:
            return True
        if width / max(height, 1) >= min_aspect:
            return True

    # Chapter header ornament / thicker decorative bar.
    if height <= max_header and width is not None:
        if width / max(height, 1) >= min_aspect and width >= min_width:
            return True
        if width_is_percent and height <= max_header:
            return True

    return False


def is_separator_img(tag, html_path: Path, root: Path, image_sizes: dict, limits: dict):
    src = tag.get("src", "") or ""
    alt = (tag.get("alt") or "").strip()
    ident = tag.get("id") or ""

    def class_text(node):
        if not node:
            return ""
        value = node.get("class")
        if isinstance(value, list):
            return " ".join(str(item) for item in value)
        return str(value or "")

    blob = combined_text(src, alt, class_text(tag), ident, class_text(tag.parent))

    if is_protected_content(blob) and not has_hint(blob, SEPARATOR_HINTS):
        return False

    html_w, html_h, html_pct = html_declared_size(tag)

    img_path = resolve_image_path(html_path, src, root)
    pixel_w = pixel_h = None
    if img_path is not None:
        size = image_sizes.get(img_path)
        if size:
            pixel_w, pixel_h = size

    width = html_w or pixel_w
    height = html_h or pixel_h

    if has_hint(blob, SEPARATOR_HINTS):
        # Named decoration: replace unless it is a tall illustration.
        if height is not None and height > limits["max_header_height"] * 1.5:
            return False
        return True

    if is_separator_shape(width, height, html_pct, limits):
        return True

    # Lone empty-alt image: only if we also know it is short, not a real figure.
    if alt == "" and tag.parent and tag.parent.name in ("p", "div", "span", "center"):
        other_text = tag.parent.get_text(strip=True)
        siblings = [
            child for child in tag.parent.children
            if str(child).strip() and getattr(child, "name", None) != "br"
            and child is not tag
        ]
        if not siblings and not other_text:
            if height is not None and height <= limits["max_header_height"]:
                return True
            if width is None and height is None:
                # Unknown size: skip. Too easy to eat real pictures.
                return False

    return False


def replace_separator_node(tag):
    replacement = make_soup(REPLACEMENT_HTML, "fragment.html").find("p")
    parent = tag.parent
    if parent and parent.name in ("p", "div", "span", "center") and not parent.get_text(strip=True):
        only_this = all(
            (getattr(child, "name", None) in (None, "br") and not str(child).strip())
            or child is tag
            for child in parent.children
        )
        if only_this or list(parent.find_all("img")) == [tag]:
            parent.replace_with(replacement)
            return
    tag.replace_with(replacement)


def process_html(content_bytes, html_path: Path, root: Path, image_sizes: dict, limits: dict, verbose=True):
    try:
        html = content_bytes.decode("utf-8")
    except UnicodeDecodeError:
        html = content_bytes.decode("latin-1")

    soup = make_soup(html, html_path.name)
    replacements = 0

    for img in list(soup.find_all("img")):
        if is_separator_img(img, html_path, root, image_sizes, limits):
            replace_separator_node(img)
            replacements += 1

    # Decorative inline SVGs that are wide + short (same Kindle refresh issue).
    for svg in list(soup.find_all("svg")):
        try:
            width = parse_px(svg.get("width"))
            height = parse_px(svg.get("height"))
            if width and height and is_separator_shape(width, height, False, limits):
                replace_separator_node(svg)
                replacements += 1
        except Exception:
            continue

    if replacements and verbose:
        print(f"    [{html_path.name}] replaced {replacements} decorative image(s)")

    return str(soup).encode("utf-8"), replacements


def build_image_size_index(root: Path):
    sizes = {}
    for path in root.rglob("*"):
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS:
            size = image_pixel_size(path)
            if size:
                sizes[path.resolve()] = size
    return sizes


def fix_epub(epub_path: Path, output_path: Path, limits: dict, verbose=True, dry_run=False):
    if verbose:
        print("  → Fixing decorative headers / dividers in EPUB…")

    total_replacements = 0

    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)

        with zipfile.ZipFile(epub_path, "r") as zin:
            zin.extractall(tmpdir)

        image_sizes = build_image_size_index(tmpdir)
        if verbose and image_sizes:
            print(f"  → Indexed {len(image_sizes)} image(s) for pixel-size checks")

        html_files = [
            path for path in tmpdir.rglob("*")
            if path.suffix.lower() in (".html", ".xhtml", ".htm")
        ]

        for fpath in html_files:
            original = fpath.read_bytes()
            fixed, count = process_html(
                original, fpath, tmpdir, image_sizes, limits, verbose=verbose
            )
            total_replacements += count
            if count and not dry_run:
                fpath.write_bytes(fixed)

        if dry_run:
            if verbose:
                print(f"  — Dry run: would replace {total_replacements} decorative image(s)")
            return total_replacements

        with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as zout:
            mimetype = tmpdir / "mimetype"
            if mimetype.exists():
                zout.write(mimetype, "mimetype", compress_type=zipfile.ZIP_STORED)

            for fpath in sorted(tmpdir.rglob("*")):
                if fpath.is_file() and fpath.name != "mimetype":
                    zout.write(fpath, fpath.relative_to(tmpdir))

    if verbose:
        if total_replacements:
            print(f"  ✓ Replaced {total_replacements} decorative header/divider image(s)")
        else:
            print("  — No decorative headers/dividers found")

    return total_replacements


def run_ebook_convert(ebook_convert: str, source: Path, dest: Path):
    cmd = [ebook_convert, str(source), str(dest)]
    if dest.suffix.lower() == OUTPUT_EXTENSION:
        cmd.extend(["--output-profile", KINDLE_OUTPUT_PROFILE])

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        stdout = (result.stdout or "").strip()
        detail = stderr or stdout or f"exit code {result.returncode}"
        raise RuntimeError(f"ebook-convert failed ({source.name} → {dest.name}):\n{detail}")


def output_path_for(book: Path) -> Path:
    return book.parent / f"{book.stem}{OUTPUT_SUFFIX}{OUTPUT_EXTENSION}"


def process_book(book: Path, output_path: Path, ebook_convert: str, limits: dict, dry_run: bool):
    ext = book.suffix.lower()

    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)

        if ext == ".epub":
            source_for_fix = book
        else:
            temp_epub = tmpdir / "source.epub"
            print("  → Converting to EPUB (Calibre)…")
            run_ebook_convert(ebook_convert, book, temp_epub)
            source_for_fix = temp_epub

        if dry_run:
            fix_epub(source_for_fix, tmpdir / "unused.epub", limits, verbose=True, dry_run=True)
            print("  — Dry run: skipped AZW3 write")
            return

        fixed_epub = tmpdir / "fixed.epub"
        fix_epub(source_for_fix, fixed_epub, limits, verbose=True, dry_run=False)

        print(f"  → Converting to AZW3 (Calibre, profile={KINDLE_OUTPUT_PROFILE})…")
        run_ebook_convert(ebook_convert, fixed_epub, output_path)

    print(f"  ✓ Saved → {output_path.name}")


def is_source_book(path: Path) -> bool:
    if path.suffix.lower() not in INPUT_EXTENSIONS:
        return False
    if path.stem.endswith(OUTPUT_SUFFIX):
        return False
    if path.name.endswith(f"{OUTPUT_SUFFIX}{OUTPUT_EXTENSION}"):
        return False
    return True


def collect_ebooks(target: Path):
    if target.is_file():
        if not is_source_book(target):
            print(f"Unsupported or already-processed file: {target}")
            sys.exit(1)
        return [target]

    if target.is_dir():
        books = []
        for ext in sorted(INPUT_EXTENSIONS):
            books.extend(sorted(p for p in target.glob(f"*{ext}") if is_source_book(p)))
        if not books:
            print(f"No supported ebooks found in {target}")
            print(f"Looking for: {', '.join(sorted(INPUT_EXTENSIONS))}")
            sys.exit(1)
        return books

    print(f"Not a valid ebook file or folder: {target}")
    sys.exit(1)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Replace Kindle-flicker header/divider images with * * * and write AZW3."
    )
    parser.add_argument("target", help="Ebook file or folder")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Report matches only; do not write AZW3 files",
    )
    parser.add_argument(
        "--max-divider-height",
        type=int,
        default=DEFAULT_MAX_DIVIDER_HEIGHT,
        help=f"Max pixel height for thin/thick rules (default {DEFAULT_MAX_DIVIDER_HEIGHT})",
    )
    parser.add_argument(
        "--max-header-height",
        type=int,
        default=DEFAULT_MAX_HEADER_HEIGHT,
        help=f"Max pixel height for chapter-header ornaments (default {DEFAULT_MAX_HEADER_HEIGHT})",
    )
    parser.add_argument(
        "--min-aspect",
        type=float,
        default=DEFAULT_MIN_ASPECT,
        help=f"Min width/height ratio to treat as a bar (default {DEFAULT_MIN_ASPECT})",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    target = Path(args.target)
    books = collect_ebooks(target)

    limits = {
        "max_divider_height": args.max_divider_height,
        "max_header_height": args.max_header_height,
        "min_aspect": args.min_aspect,
        "min_width": DEFAULT_MIN_WIDTH,
    }

    ebook_convert = find_ebook_convert()
    if not ebook_convert:
        print("Error: Calibre is required.")
        print("Install from https://calibre-ebook.com/download")
        sys.exit(1)

    print(f"Using Calibre: {ebook_convert}")
    print(f"Found {len(books)} ebook(s) to process.")
    print(
        f"Pixel rules: divider ≤ {limits['max_divider_height']}px, "
        f"header ornament ≤ {limits['max_header_height']}px, "
        f"aspect ≥ {limits['min_aspect']}"
    )
    if args.dry_run:
        print("Mode: dry-run (no files written)")
    print(f"Output format: AZW3 (profile: {KINDLE_OUTPUT_PROFILE})\n")

    succeeded = []
    failed = []

    for index, book in enumerate(books, start=1):
        out = output_path_for(book)
        print(f"\n[{index}/{len(books)}] {book.name}")
        try:
            process_book(book, out, ebook_convert, limits, args.dry_run)
            succeeded.append(book.name)
        except Exception as exc:
            print(f"  ✗ FAILED: {exc}")
            failed.append((book.name, str(exc)))

    print("\n" + "=" * 60)
    print(f"Finished: {len(succeeded)} succeeded, {len(failed)} failed")
    if not args.dry_run:
        print(f"Fixed files: *{OUTPUT_SUFFIX}{OUTPUT_EXTENSION}")
        print("Originals were NOT modified.")

    if failed:
        print("\nFailures:")
        for name, err in failed:
            first_line = err.splitlines()[0] if err else "unknown error"
            print(f"  - {name}: {first_line}")
        sys.exit(1)


if __name__ == "__main__":
    main()
