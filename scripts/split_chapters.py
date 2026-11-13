#!/usr/bin/env python3
import os
import re
import sys

from bs4 import BeautifulSoup
from ebooklib import ITEM_DOCUMENT, epub

BOOKS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Ebooks")

BANNER = r"""
                 _______________________________
                /                               /|
               /                               / |
              /                               /  |
             /_______________________________/   |
             |  001  The Gate               |    |
             |  002  The Ridge              |    |
             |  003  The Hook               |   /
             |______________________________|  /
                         \  \    \  \         /
                          \  \____\__\_______/
                           \   slice, slice   /
                            \________________/

        one book. many chapters. no model in the room.
"""


def display_name(path):
    name = os.path.splitext(os.path.basename(path))[0]
    name = re.sub(r"\s*\((z-library|1lib|z-lib)\.sk.*$", "", name, flags=re.IGNORECASE)
    name = re.sub(r"\s+", " ", name).strip()
    if len(name) > 78:
        return name[:75] + "..."
    return name


def find_books(folder):
    if not os.path.isdir(folder):
        return []
    books = [
        os.path.join(folder, name)
        for name in os.listdir(folder)
        if name.lower().endswith(".epub") and os.path.isfile(os.path.join(folder, name))
    ]
    return sorted(books, key=lambda path: display_name(path).lower())


def spine_documents(book):
    ordered = []
    for entry in book.spine:
        idref = entry[0] if isinstance(entry, tuple) else entry
        if idref in ("nav", "cover"):
            continue
        item = book.get_item_with_id(idref)
        if item is not None and item.get_type() == ITEM_DOCUMENT:
            ordered.append(item)
    if ordered:
        return ordered
    return [item for item in book.get_items_of_type(ITEM_DOCUMENT)]


def chapter_title(html):
    heading = BeautifulSoup(html, "html.parser").select_one("h1, h2, h3, h4")
    if heading is None:
        return None
    title = heading.get_text(" ", strip=True)
    return title or None


def chapter_text(html):
    soup = BeautifulSoup(html, "html.parser")
    body = soup.body or soup
    blocks = body.select("p, h1, h2, h3, h4, li")
    text = body.get_text("\n", strip=True) if not blocks else "\n\n".join(block.get_text(" ", strip=True) for block in blocks)
    return text.strip()


def sanitize_file_name(name):
    cleaned = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", " ", name)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return cleaned[:80].strip(" .")


def chapter_file_name(number, title):
    prefix = f"{number:03d}"
    cleaned = sanitize_file_name(title) if title else ""
    return f"{prefix}.txt" if not cleaned else f"{prefix} {cleaned}.txt"


def chapter_folder(source):
    folder, filename = os.path.split(source)
    stem = filename.rsplit(".", 1)[0]
    candidate = os.path.join(folder, f"{stem}_chapters")
    number = 2
    while os.path.exists(candidate):
        candidate = os.path.join(folder, f"{stem}_chapters {number}")
        number += 1
    return candidate


def split_book(source):
    book = epub.read_epub(source)
    chapters = []
    for item in spine_documents(book):
        html = item.get_content()
        text = chapter_text(html)
        if text:
            chapters.append((chapter_title(html), text))
    if not chapters:
        raise RuntimeError("That book has no chapter text.")

    folder = chapter_folder(source)
    os.makedirs(folder)
    written = []
    for number, (title, text) in enumerate(chapters, 1):
        name = chapter_file_name(number, title)
        path = os.path.join(folder, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        written.append(name)
    return folder, written


def paint(line="", file=None):
    print(line, file=file)


def choose_book(books):
    paint("\033[36m" + BANNER + "\033[0m")
    paint(f"  Library: {BOOKS_DIR}")
    paint(f"  {len(books)} books. Pick one.\n")
    for number, path in enumerate(books, 1):
        paint(f"  {number:2d}  {display_name(path)}")
    paint("\n  Enter a number, or q to leave.")
    while True:
        try:
            choice = input("  > ").strip().lower()
        except EOFError:
            paint("")
            return None
        if choice in {"q", "quit", "exit"}:
            return None
        if choice.isdigit() and 1 <= int(choice) <= len(books):
            return books[int(choice) - 1]
        paint("  That number is not on the shelf. Try again.")


def main():
    if len(sys.argv) > 2:
        paint("Pass one EPUB path, or no arguments to pick from the library.", file=sys.stderr)
        return 1
    if len(sys.argv) == 2:
        source = os.path.abspath(sys.argv[1])
        if not os.path.isfile(source):
            paint(f"That file is not available: {source}", file=sys.stderr)
            return 1
    else:
        books = find_books(BOOKS_DIR)
        if not books:
            paint(f"No EPUBs in {BOOKS_DIR}", file=sys.stderr)
            return 1
        source = choose_book(books)
        if source is None:
            paint("  Left the shelf untouched.")
            return 0
        paint("")
        paint(f"  Slicing {display_name(source)}")

    try:
        folder, written = split_book(source)
    except Exception as error:
        paint(f"Could not split that book: {error}", file=sys.stderr)
        return 1

    paint(f"  {len(written)} chapters")
    paint(f"  {folder}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n  Stopped.")
        raise SystemExit(130)
