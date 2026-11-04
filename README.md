# mOj's ebook converter

Converts EPUB, FB2, MOBI, and similar books to AZW3, and removes the big divider images that force a Kindle to full-refresh in dark mode.

This version converts **EPUB, HTML, and TXT**, and strips those dividers. FB2, MOBI, and AZW3 are next. Original files are never overwritten. Each result is saved beside the source as `name_fixed`.

Divider removal is on by default. Turn it off under **Options** when you only want a format change. Covers, photos, maps, and other real figures stay.

## Run

You need JDK 17 or newer.

```bash
./gradlew :composeApp:run
```

On a Mac you can also package a disk image:

```bash
./gradlew :composeApp:packageDmg
```

On Windows, from the same sources:

```bash
./gradlew :composeApp:packageMsi
```

## Window

Drop EPUB, HTML, or TXT files onto the window, or use **File → Choose Files…**. Pick EPUB, HTML, or TXT, then **Convert**.

- **File:** Choose Files, Clear List, Show Output Folder, Quit.
- **Options:** Remove divider images. Theme: System, Light, or Dark. The choice is remembered.
- **Help:** About.

## Why the dividers go

A wide ornamental image, even a short one, makes many Kindles flash the whole page in dark mode. The app replaces those images with a centered `* * *`. A tall picture whose name or alt text looks like a cover, photo, map, or illustration is left alone.

## License

[MIT](LICENSE)
