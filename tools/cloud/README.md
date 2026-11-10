# Cloud packs: scores and instruments

Scores and instruments are **made outside the car** on a creator platform (web) and shared through a cloud.
The car app only lists, downloads (parked) and plays them: it has no editor and never uploads.

```
creator platform (web)  ──upload──▶  cloud (catalog + pack files)  ──download, parked──▶  car app
  score editor / MIDI import                                                  Get tab → Albums "받은 곡"
  record or design an instrument                                               Get tab → Band cards
```

The example cloud is `docs/cloud`, served by GitHub Pages at
`https://hoyadong1.github.io/Caestro/cloud/` (`CloudLibrary.BASE`). A debug build can point at another copy:
`adb shell am start -n com.drivesoundtrack.app/.MainActivity --es cloudBase https://.../cloud/`.

    python build_cloud.py <cache dir>     # rebuilds the example instruments and catalog.json (needs numpy)

## catalog.json

```json
{ "format": 1, "items": [
  { "id": "largo", "type": "score", "title": "Largo", "subtitle": "…", "creator": "…", "license": "…",
    "color": "#8FD0B8", "files": ["scores/largo.json"], "bytes": 783 },
  { "id": "trombone", "type": "instrument", "title": "트롬본", "subtitle": "녹음한 악기", "creator": "…", "license": "…",
    "look": "BRASS", "color": "#E3A857", "stages": [0, 2, 4],
    "files": ["instruments/trombone.json", "instruments/trombone.pcm"], "bytes": 745 } ] }
```

- `id`: lowercase letters, digits, `-` and `_` (up to 40). A pack is stored in a folder of that name.
- `files`: paths relative to the catalog; the first is the pack's manifest `<id>.json`. No `..`, no absolute paths, 16 MB per file.
- The car refuses a pack it could not play and keeps the previous copy.

## Score (`type: "score"`)

| field | meaning |
|---|---|
| `title`, `character`, `composer` | shown in Albums and Now playing |
| `bpm`, `tempoRange` | tempo when parked, BPM added at 130 km/h |
| `beatsPerBar` | 3 or 4; a step is a sixteenth note |
| `tonic` | key, e.g. `"C"`, `"F#"` |
| `chords` | `"C:16 \| C:8 F/C:8 \| …"` symbol and length in steps; `\|` bar lines are checked |
| `voicings` | optional, one explicit chord per symbol (`"C3 E3 G3 C4 E4 \| …"`) |
| `melody` | `"E5:6 G5:2 G5:8 \| …"`; `r` rests, `-` ties, `:n` sets the length |
| `bass` | `HALVES`, `ROOT_FIFTH`, `VOICING`, or an explicit line in the melody notation |
| `arp`, `comp` | one bar: arpeggio indices (`0 . 2 .`) and chord hits (`x . . .`) |
| `high`, `canonBars` | top stage: `BELLS`, `OCTAVE` or `CANON` (with the canon delay in bars) |
| `defaults` | six suggested instrument ids (bass → high); unknown ones fall back to the default band |

Only scores the uploader wrote, or whose copyright has ended, are accepted.

## Instrument (`type: "instrument"`)

| field | meaning |
|---|---|
| `label`, `creator`, `license` | shown on the band card |
| `sound` | `recorded` (uploaded recordings) or `designed` (a timbre shaped on the platform) |
| `stages` | where it may play: 0 bass, 2 chords, 3 arpeggio, 4 melody, 5 high (1, rhythm, is ignored) |
| `sustained`, `voices`, `mono`, `minMidi`, `maxMidi` | how notes are held and spread |
| `gain`, `attack`, `release` | level and envelope (seconds) |
| `look`, `color` | stage drawing: `BOWED`, `KEYS`, `HARP`, `PLUCKED`, `WIND`, `BRASS`, `MALLET`, `GLASS`, … |
| `rate`, `pcm` | 24000 Hz, 16-bit mono little-endian, notes back to back in `<id>.pcm` |
| `notes` | `{ root, offset, length, loopStart, loopEnd }` per note, offsets in samples; `-1` loops for one-shots |

Notes are leveled like the app's own bank (loudest 100 ms at RMS 0.25), so a `gain` near the app's instrument
of the same family sits at the same level. `CloudLibraryTest` checks the example packs against the app's own
instruments, clicks and clipping.
