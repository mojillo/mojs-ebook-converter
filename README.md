# mOj's ebook converter

Converts EPUB, FB2, MOBI, and similar books to AZW3, and removes the big divider images that force a Kindle to full-refresh in dark mode.

This version converts **EPUB, HTML, and TXT**, and strips those dividers. FB2, MOBI, and AZW3 are next. Original files are never overwritten. Each result is saved beside the source as `name_fixed`.

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

## Window

Drop EPUB, HTML, or TXT files onto the window, or use **File → Choose Files…**. Pick EPUB, HTML, or TXT, then **Convert**.

- **File:** Choose Files, Clear List, Show Output Folder, Quit.
- **Options:** Remove divider images. Theme: System, Light, or Dark. The choice is remembered.
- **Help:** About.

## Why the dividers go

A wide ornamental image, even a short one, makes many Kindles flash the whole page in dark mode. The app replaces those images with a centered `* * *`. A tall picture whose name or alt text looks like a cover, photo, map, or illustration is left alone.

## License

[MIT](LICENSE)
