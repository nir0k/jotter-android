# Library fixtures

Library files that Jotter for Android reads and writes back byte for byte, and the three-way merges of synchronisation it must compute alike (`docs/spec.md` section 2). The library format and synchronisation are specified by the [Jotter desktop specification](https://github.com/nir0k/Jotter/blob/main/docs/spec.md) (sections 3 and 12). The files were written by the Jotter desktop application from contents authored by hand, so the bytes are what the desktop writes and are the reference for anything the specification leaves open, such as the order of JSON keys (alphabetical), how Qt writes empty arrays and objects and numbers, and where the editor puts an empty paragraph. They were taken from the desktop project's `tests/fixtures/` at its commit `6d29cae` and are maintained here since.

`FixturesTest`, `CodecTest` and `MergeTest` of `core` check them on every `./gradlew :core:test` run.

## Layout

| Path | Content |
| --- | --- |
| `library/` | A readable library; every file reads and writes back unchanged, and every note body survives the editor unchanged |
| `library/library.json` | Favourites of both kinds, tag colours (a tag colour key, a custom `#rrggbb`, an unknown key, a Cyrillic tag), a tombstone, unknown fields at the top level and in `settings` |
| `library/collections.json` | A three-level tree, icons, colour keys, a custom and an unknown colour, an unknown field |
| `library/rules.json` | Both condition kinds, `add` with two targets and `move`, `any`, `stop`, a disabled rule, an unknown field; a rule of an earlier version with conditions on the title and a regular expression and `caseSensitive`, unsupported and written back unchanged |
| `library/notes/` | Notes grouped by subject, listed below |
| `library/attachments/` | The files the notes show: PNG, JPEG, GIF and WebP images, and attachments that are not images by their bytes (PDF, SVG, a text file named `.png`) |
| `problems/` | A library whose note files cannot be read; `expected.json` lists the problem code each one gives (`malformed` or `newerFormat`). `01a1a316-0000-…` has valid metadata and unsupported body HTML that must remain untouched until explicit normalisation. `readme.html` is not named after an identifier and is not a note, so it is not listed |
| `newer-library/` | `library.json` of a newer format version; the library opens read-only |
| `merge/` | Merge cases, listed below |

Notes of `library/notes/`:

| Note | Content |
| --- | --- |
| `01a1872b-97ba-…` Текст и форматирование | Headings, inline formatting, font, tag and custom colours, a line break, escaping, Cyrillic and emoji, a quote, an empty paragraph, a divider; icon, colour, collection, nested tags and a geotag |
| `01a1872b-97bc-…` Lists | Bulleted, numbered with `start`, checklists, every numbering style, a style of an earlier version and an unknown style identifier, nine levels of mixed kinds, adjacent lists; archived |
| `01a1872b-97be-…` Code blocks | A language and a title with characters to escape, tabs, plain, unknown language, empty, between lists; the empty paragraphs the editor puts between adjacent blocks |
| `01a1872b-97c0-…` Tables | Width, column widths, header row, a list, an image and two paragraphs in cells, a full-width table; the empty paragraph between adjacent tables |
| `01a1872b-97c3-…` Изображения и вложения | Image widths and both wrap modes, every image kind, percent-encoded and Cyrillic file names, attachment chips |
| `01a1872b-97c5-…` Links | Web and mail links, note links; trashed with its archived time kept |
| `01a1872b-97c7-…` (untitled) | Empty title and body, a `quick` field from an earlier version, kept as an unknown field, a geotag without accuracy |
| `1889d8c9-…` План… | UUID version 4, a title that could end the metadata script and needs HTML escaping, an unknown icon, a custom colour, unknown fields of every JSON type |

## Merge cases

Each folder of `merge/` is one case. `local/` and `remote/` hold the two versions of library files, named as in a library (`library.json`, `collections.json`, `rules.json`, `notes/<id>.html`); `base/` holds the version both sides started from, and is missing for a first connection (desktop specification section 12.6). `merged/` holds what the merge writes to both sides. `case.json` gives the time of the merge (`now`, which expires tombstones) and the files whose note bodies conflict (`conflicts`), whose local version becomes a conflict copy.

| Case | Content |
| --- | --- |
| `note-blocks-combine` | Changes to different blocks combine, blocks added on both sides at different places included |
| `note-adjacent-blocks` | Changes to blocks next to each other combine |
| `note-same-block` | The same block changed on both sides conflicts; the remote body wins |
| `note-metadata` | Metadata by field: one-sided changes combine, the later note wins a field changed on both sides, tags merge as case-folded sets |
| `note-first-connection-same-body` | Without a base, equal bodies keep the later note |
| `note-first-connection-different-body` | Without a base, different bodies conflict and keep the remote note |
| `collections-by-entry` | Additions on both sides, a change against a deletion, the later of two changes, a child of a deleted parent moved to the top level |
| `collections-local-reorder` | A reorder made only locally is kept |
| `collections-parent-cycle` | Each side moving a collection under the other leaves both at the top level; a child below keeps its parent |
| `rules-by-entry` | The later of two changes, a change against a deletion, an addition following its predecessor |
| `library-first-connection` | Without a base: tombstones of both sides until they expire, favourites of deleted notes dropped, tag colours by tag, remote settings |
| `library-with-base` | Favourites by entry with a local reorder kept, tag colours by tag, a setting changed on one side |

## Updating

A fixture is changed or added only in the form the desktop writes: keys in alphabetical order, two-space indentation, Qt's forms of empty arrays and objects and of numbers, and note bodies as the desktop's editor writes them. A body `core`'s codec reads and writes back unchanged is in that form, and `./gradlew :core:test` checks every fixture against it. A merged version is what the merge writes, so it is checked against the case's intent before it is committed. The damaged notes of `problems/` and the newer library are kept as they are.

A change of the library format or of synchronisation is made in the desktop specification first; the fixtures follow it.
