# mOj's ebook converter

Converts EPUB, FB2, MOBI, and similar books to AZW3, and removes the big divider images that force a Kindle to full-refresh in dark mode.

This version converts **EPUB, HTML, and TXT**, and strips those dividers. If [Calibre](https://calibre-ebook.com/download) is installed, **AZW3** is available too, and MOBI, FB2, AZW, and AZW3 files can be opened. Original files are never overwritten. Each result is saved beside the source as `name_fixed`.

**Export chapters** writes one text file per chapter into a `name_chapters` folder beside the book. Files are named with the chapter number and title, such as `002 The Ridge.txt`. Nothing is sent to a model.

Divider removal is on by default. Turn it off under **Options** when you only want a format change. Covers, photos, maps, and other real figures stay.

## Download

Installers for Mac and Windows are on the [releases page](https://github.com/mojillo/mojs-ebook-converter/releases).

| File | System |
| --- | --- |
| `.dmg` | macOS |
| `.msi` or `.exe` | Windows |

These are desktop apps. They do not run on Android phones or iPhones.

## Build it yourself

You need JDK 23.

```bash
./gradlew :composeApp:run
```

Packaging uses the same sources:

```bash
./gradlew :composeApp:packageDmg
./gradlew :composeApp:packageMsi
./gradlew :composeApp:packageExe
```

`packageDmg` runs on a Mac. `packageMsi` and `packageExe` run on Windows. The Windows EXE build also needs [WiX Toolset 3](https://wixtoolset.org/).

AZW3 shows up in the window after Calibre is installed. Without it, the AZW3 choice stays off and the window explains where to get Calibre.

## Without the app

`scripts/` is the same work as two Python files. Install the libraries once:

```bash
python3 -m pip install -r scripts/requirements.txt
```

Split one EPUB into a single folder of chapter files. Nothing is sent to a model.

```bash
python3 scripts/split_chapters.py /path/to/book.epub
```

That writes `book_chapters/` beside the EPUB, one text file per chapter. With no path, the script lists EPUBs in an `Ebooks` folder next to itself and asks for one number.

Strip divider images and write an AZW3. This needs Calibre.

```bash
python3 scripts/fix_ebook_separators.py /path/to/book.epub
```

## Window

Drop EPUB, HTML, or TXT files onto the window, or use **File → Choose Files…**. Pick EPUB, HTML, or TXT, then **Convert**. **Export chapters** writes that folder of text files beside the book.

- **File:** Choose Files, Clear List, Show Output Folder, Quit.
- **Options:** Remove divider images. Theme: System, Light, or Dark. The choice is remembered.
- **Help:** About.

## Why the dividers go

A wide ornamental image, even a short one, makes many Kindles flash the whole page in dark mode. The app replaces those images with a centered `* * *`. A tall picture whose name or alt text looks like a cover, photo, map, or illustration is left alone.

## License

[MIT](LICENSE)
