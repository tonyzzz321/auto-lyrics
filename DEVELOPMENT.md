# Auto Lyrics — Development Reference

This document describes the current implementation. Keep user-facing setup in `README.md`; keep release mechanics in `RELEASE.md`.

## Terminology

| Term | Meaning |
|---|---|
| **WORD_SYNC** | Synchronized lyrics with usable sub-line timing tokens. A token may be a word, syllable, fragment, or character. |
| **LINE_SYNC** | Synchronized lyrics with line timestamps but no usable sub-line timing tokens. |
| **Timing token** | Raw provider unit stored as `LyricWord(timeMs, text, endTimeMs?)`. |
| **Display grouping** | Renderer-only mapping from fine timing tokens to readable lexical ranges. Raw provider timing is not rewritten. |
| **Plain lyrics** | Unsynchronized text represented by `LyricsStatus.PLAIN_ONLY`. |
| **Standard mode** | Accuracy-first provider selection. |
| **Karaoke mode** | May prefer a near-equivalent candidate with real timing-token data. |
| **AA offset** | Android-Auto-specific delay stored as `aa_offset_ms`. |
| **Phone/global offset** | Main lyric offset managed by `lyrics_offset_ms`. |
| **Lyrics demand** | Whether provider resolution should be active. Demand is true while a phone Activity is started or Android Auto projection is connected. |

## Architecture

```text
Phone Activity lifecycle ────────────────┐
Android Auto CarConnection(PROJECTION) ──┤
                                          ▼
                                LyricsDemandController
                                          │
                                          │ active demand
                                          ▼
MediaListenerService (NotificationListenerService)
    │
    ├── continuously observes/selects active media sessions
    └── forwards the selected session only while demand is active
                                          │
                                          ▼
                               MediaTracker (singleton / StateFlow)
                                  ├─ active MediaController state
                                  ├─ LrcLibClient ─────────────┐
                                  ├─ MusixmatchClient ─────────┤ parallel
                                  ├─ PetitLyricsClient ────────┤ when configured
                                  ├─ SyncLrcClient ────────────┤ Karaoke mode only
                                  ├─ LyricsProviderResolver ◀──┘
                                  ├─ LyricsCache (STANDARD / KARAOKE variants)
                                  ├─ MetadataCleaner
                                  ├─ LrcParser
                                  ├─ KaraokeTiming
                                  ├─ LyricsTranslator ───┐ engine routing via
                                  ├─ LlmTranslator ──────┘ translation_engine pref
                                  ├─ TranslationCache
                                  └─ AlbumColorExtractor
                                          │
                     ┌────────────────────┴────────────────────┐
                     ▼                                         ▼
                  Phone UI                           LyricsBrowserService
            MainActivity / PerformanceActivity       Android Auto MediaBrowser
                     │                                + MediaSession
                     └─ LyricWordLayout                        └─ LyricWordLayout
```

`MediaListenerService` is a `NotificationListenerService`; notification access allows inspection of active media sessions. The selected playing session remains sticky while it is still playing so controller ordering changes do not steal selection.

## Demand gating

Provider resolution is intentionally not active all the time.

`LyricsDemandController` maintains two process-wide inputs:

- phone demand: at least one Auto Lyrics Activity is in the Android **started** lifecycle state,
- car demand: `CarConnection.CONNECTION_TYPE_PROJECTION` indicates an Android Auto projection session.

The effective state is:

```text
lyricsDemand = phoneActivityStarted || androidAutoProjectionConnected
```

Important behavior:

- The notification listener continues lightweight media-session discovery even when demand is false.
- When demand becomes true, `MediaListenerService` immediately re-reads active sessions and forwards the current best session to `MediaTracker`.
- When demand becomes false, `MediaTracker` is detached from the controller so later metadata changes do not start new provider work.
- An already-issued provider HTTP request is not forcibly cancelled by this gate; the change prevents subsequent track/session updates from starting more work.
- Android Auto demand remains active for the entire projection session, even while another Android Auto app is in the foreground.
- Existing `LyricsBrowserService` startup/discovery behavior is preserved; demand gating only controls lyric-resolution work.

This separation allows the app to know the current media session quickly when the phone UI is opened or Android Auto connects without continuously querying lyric providers while the app is otherwise unused.

## Track position and offsets

`MediaTracker` extracts title, artist, album, duration, artwork, playback state, and position from the active controller. Metadata is normalized by `MetadataCleaner`.

Track changes are debounced by 600 ms. During playback, position is reconstructed from the media session's reported position, last update time, and playback speed. Synced indices are checked every 150 ms.

`MediaTracker.getCurrentPositionMs()` includes the phone/global offset. Android Auto applies the additional `aa_offset_ms` in `LyricsBrowserService`. `LyricsBrowserService.prefsListener` calls `forceRefresh()` on `aa_offset_ms`, `translation_enabled`, `aa_karaoke_enabled`, and target-language changes so head-unit browse reflects toggles immediately, not at the next track. Phone side: `MediaTracker` keeps the listener as the fast path but additionally runs a 500 ms preference-reconciliation poll (`startPreferenceReconciliation`) that diffs translation-enabled/karaoke/target/engine/model and replays the same instant-apply handlers, so a lost listener callback can no longer strand a toggle until the next track.

Do **not** add a provider-wide pre-offset merely because lyrics appear early on one playback path. Media-session logical position can lead audible output because of Bluetooth, codec, Android Auto, DAC, or head-unit buffering. Stable output-path latency belongs in the existing offset controls unless a provider timestamp bias is demonstrated across multiple playback paths.

## Provider execution

Provider work starts only while lyrics demand is active.

Standard mode runs:

```text
LRCLIB ───────┐
Musixmatch ───┼─ parallel
PetitLyrics ──┘  when configured
```

Karaoke mode additionally runs:

```text
SyncLRC ──────┐
LRCLIB ───────┤
Musixmatch ───┼─ parallel
PetitLyrics ──┘  when configured
```

Each provider gets a hard **5 second total budget** at the `MediaTracker` boundary. Provider response time is not part of the score; a provider loses due to speed only when it misses its budget and produces no candidate.

Debug logging:

```powershell
adb logcat -s Musixmatch:D SyncLRC:D ProviderResolver:D PetitLyrics:D
```

## Provider resolution

Provider clients return normalized `LyricsProviderCandidate` objects. The base score is:

```text
final = metadata match    × 0.82
      + lyric quality     × 0.10
      + source confidence × 0.08
```

A synchronized candidate (`LyricsStatus.FOUND`) outranks a plain candidate. LRCLIB plain text remains the last-resort fallback.

### Standard vs Karaoke selection

Standard mode returns the synchronized candidate with the highest ordinary final score.

Karaoke mode first computes that same standard winner, then considers only candidates that:

- declare `WORD_SYNC`,
- actually contain `LyricWord` timing data,
- have metadata score no more than **0.03** below the standard winner,
- have lyric payload quality no more than **0.05** below the standard winner.

The highest-scoring candidate inside that near-equivalent WORD_SYNC set wins. This lets an equivalent Musixmatch, PetitLyrics, or SyncLRC result replace LRCLIB LINE_SYNC in Karaoke mode without allowing a materially weaker live/remix/mismatched recording to win solely because it has richer timing.

### Metadata scoring

Approximate metadata weighting:

```text
title     55%
artist    30%
duration  12% when reliable
album      3%
```

Weights are renormalized when a field is unavailable. PetitLyrics duration is not trusted in common resolver scoring; LRCLIB, Musixmatch, and SyncLRC duration may be used when present.

Romanized player metadata can be compared against native Japanese provider metadata. For a near-exact title, a cross-script artist mismatch can be neutralized only with independent evidence such as an artist-constrained query, strong album similarity, or strong reliable-duration similarity.

Multi-contributor player artist strings are preserved. For near-exact titles, contributor-aware comparison can match one comma/semicolon-delimited contributor rather than truncating the media metadata globally.

### Payload quality and source confidence

Payload quality checks include suspicious Japanese/Latin alternation, near-duplicate transliteration timestamps, timing far beyond track duration, unusually short timing coverage, and very late first timestamps.

Source confidence is intentionally only 8% of the final score. Current policy strongly favors PetitLyrics Type 3/2 for Japanese and LRCLIB synchronized data for non-Japanese tracks. Musixmatch and SyncLRC use generic synchronized-provider confidence unless a more specific policy is justified later.

## Timing tokens vs display grouping

`LrcParser` accepts fractional seconds as optional in both line (`[mm:ss]`) and word (`<mm:ss>`) timestamps — real-world files ship without fractions, with centiseconds, and with milliseconds; a strict fraction requirement silently merged later word tokens into the last parsed one, freezing karaoke highlighting mid-line. It also strips spaces flanked by CJK characters on both sides (`cleanSpacing`, applied to display text, word tokens, and plain bodies at parse time) because karaoke-adjacent sources insert them between individual characters; Latin spacing is untouched. Per-character token streams (one CJK glyph per token plus a trailing space) are handled by a second pass that drops the boundary space from the preceding token, and the karaoke display text is rebuilt as the exact concatenation of word tokens so highlight spans always align.

Provider granularity is preserved independently from display granularity.

Example:

```text
raw timing:  Pro @1000 / vi @1100 / der @1200
source text: Provider
highlight:   Provider
```

Japanese data can be character-granular:

```text
raw timing: 君 / を / 忘 / れ / な / い
```

`LyricWordLayout` aligns timing tokens back to `LyricLine.text`, uses Java/Android word boundaries, and applies a conservative Japanese display heuristic that merges adjacent hiragana suffixes into a preceding kanji-containing range. Multi-syllable chunk payloads therefore display closer to `君を / 忘れない` than six separate highlights. Two refinements: payloads whose tokens are syllable-sized for any CJK script — kana, Han, or Hangul, since SyncLRC tokenizes Korean and Chinese per character too — highlight glyph-by-glyph instead of jumping in dictionary-word steps, and every highlight range sweeps through trailing punctuation/spaces up to the next token so ending punctuation is never left unlit when the line changes.

This is a display heuristic, not a morphological analyzer. The original timing tokens remain intact in state and cache. If tokens cannot be aligned to the source line, the previous separator reconstruction is used as a compatibility fallback.

## LRCLIB

LRCLIB starts with constrained metadata and relaxes discovery when necessary. Returned records are re-ranked locally using title, contributor-aware artist matching, album, duration, and version qualifiers.

Synced LRC becomes `LINE_SYNC`. Plain lyrics can be returned as `LyricsStatus.PLAIN_ONLY` only when no acceptable synchronized result exists.

## Musixmatch

Musixmatch uses the anonymous mobile API rather than the desktop endpoint, because runtime testing found the desktop path could return unrelated fixed tracks despite successful status.

Flow:

```text
token.get
    ↓
macro.subtitles.get
    ├── matcher.track.get
    ├── track.richsync.get
    └── track.subtitles.get
```

The provider reads the explicit `matcher.track.get` result and validates it locally before accepting lyric payloads. One token refresh/retry is allowed after authentication rejection.

RichSync timing is:

```text
token timestamp = line ts + chunk o
```

RichSync chunk offsets are occasionally non-monotonic (notably CJK lines); `parseRichSyncWords` re-sorts tokens chronologically before returning them. Some payloads also store absolute track times in `o` instead of line-relative offsets; any offset exceeding the line end (`te`) that fits the line window once `ts` is subtracted is reinterpreted relative to the line, because absolute-polluted tokens otherwise land past the line and freeze the highlight until the next line starts. Payloads can also hold one coarse chunk (often a whole CJK clause) across seconds of continuing singing; `fillUncoveredText` synthesizes per-character tokens for any run of line text no chunk covers (some payloads time only the line's first stretch), spread between the surrounding token times or the line boundary; `spreadLongGaps` then spreads multi-character tokens that hold >=1.2s (mid-line gap, or a whole line riding a single chunk) character-by-character across the hold, and `LrcParser.collapsePunctuation` folds standalone punctuation tokens into the preceding word (punctuation is never sung; both the RichSync repair path and the LRC karaoke tokenizer run it). The line boundary comes from `effectiveLineEndMs`: `te` is trusted only when it leaves a real tail past the last token — polluted lines set `te` at the line start — otherwise the next line's start bounds the hold. Both exist because frozen highlights mid-line read as a stall-then-jump bug. RichSync chunks otherwise remain raw `LyricWord` timing tokens. The `LyricsProviderCandidate` source label carries anomaly counters (`fix`/`oob`/`dup`/`miss`/`gapXs`) measured during parsing. If RichSync is absent or unusable, line-synced subtitle LRC becomes `LINE_SYNC`.

Musixmatch uses an unofficial/internal endpoint and can change independently of Auto Lyrics.

## PetitLyrics

Supported formats:

- **lyricsType=3 / WSY** — each `<word>` contributes `starttime`, optional `endtime`, and raw `wordstring` to `LyricWord`.
- **lyricsType=2 / LSY** — binary line timing combined with a **lyricsType=1** plain-text companion, preferably resolved by the same `lyricsId`.

Type 3 does not guarantee linguistic words. Japanese may be character-granular and English may contain fragments. Those raw elements are retained and grouped only at render time.

`endTimeMs` persists through `LyricsCache` and is honored by `KaraokeTiming`. If one token ends before the next starts, the gap has no active highlight.

Search progressively relaxes:

```text
title + artist + album
        ↓
title + artist
        ↓
title only
```

The search stage is preserved through `artistQueryCorroborated`, so a title-only fallback does not receive the same cross-script evidence as an artist-constrained query.

PetitLyrics uses an internal/unofficial endpoint and requires local build configuration.

## SyncLRC

`SyncLrcClient` is Karaoke-mode-only. It consumes the current public API:

```text
GET https://api.synclrc.dev/lyrics
    ?track=<title>
    &artist=<artist>
    &type=karaoke
    [&album=<album>]
    [&duration=<rounded seconds>]
```

The current public response shape exposes canonical metadata plus separate lyric fields:

```json
{
  "id": "...",
  "track": "Song",
  "artist": "Artist",
  "album": "Album",
  "duration": 215,
  "instrumental": false,
  "karaoke": "[00:00.00]<00:00.05>...",
  "synced": "[00:00.00]...",
  "plain": "..."
}
```

Auto Lyrics accepts only a non-empty `karaoke` payload. For compatibility with older SyncLRC deployments, `SyncLrcClient` can also read the former type-specific shape:

```json
{
  "type": "karaoke",
  "lyrics": "[00:00.00]<00:00.05>..."
}
```

Legacy `type=synced/plain` bodies and current responses without a Karaoke field are rejected for this provider. Direct LRCLIB already covers LINE_SYNC/plain and should remain the canonical path for those formats.

SyncLRC documents Karaoke as Enhanced LRC and uses LRCLIB plus an LDDC/syncedlyrics-backed stack for sources including NetEase, QQ Music, Kugou, and Musixmatch. Auto Lyrics calls the public JSON service only; it does not copy or embed SyncLRC/LDDC source code.

SyncLRC uses the resolver's generic synchronized-provider confidence. It is not granted an unconditional preference over direct providers.

### Enhanced LRC parser

`LrcParser.parseKaraoke()` accepts line timestamps such as `[00:01.00]` and timing-token timestamps such as `<00:01.25>`. Dot or colon fractional separators are tolerated.

Word tokens are sorted chronologically after parsing (time order is sung order; display text follows the sort), and a line repeated with multiple timestamp tags (`[00:10.00][00:40.00]`) gets its word times shifted onto each instance instead of highlighting fully-lit from word one on repeats. Text before the first word tag (spoken intros) is attached to the first token rather than dropped.

Timing tags are removed to recover exact `LyricLine.text`. Every raw substring between timing tags becomes a `LyricWord`; attached whitespace is retained so the original line can be reconstructed exactly. Line-only LRC produces no fake timing tokens.

## PetitLyrics local configuration

Copy the tracked template:

```powershell
Copy-Item .env.example .env
```

Fill in:

```dotenv
PETITLYRICS_USER_ID=your-user-id
PETITLYRICS_APP_NAME=your-app-name
PETITLYRICS_PKG_NAME=your-package-name
PETITLYRICS_CLIENT_APP_ID=your-client-app-id
```

Configuration order:

```text
process environment
    ↓ if missing/blank
.env
    ↓ only when .env itself does not exist
.env.example
```

If a required value is blank, PetitLyrics is skipped. These values are compiled into Android `BuildConfig`; keeping them out of Git does not make them secret in a distributed APK.

## Settings panel

The gear button toggles `layout_font_settings` in `activity_main.xml`, organized in three sections: **Phone UI** (Size, Font), **Lyrics** (Karaoke Mode, Translate Lyrics; the dynamic target/engine/status rows are inserted here relative to the translate row via `indexOfChild`, and **Sync** (Phone Sync ±50 ms, AA Sync ±50 ms + reset). Deliberately outside the panel: Manual Search and Fullscreen buttons (button bar), Jump-to-Current, and the phone fine-offset presets + Tap to Sync (live mid-song controls under the lyrics).

## Translation engines

`MediaTracker.translateIfNeeded()` routes by the `translation_engine` pref (`on_device` default, `llm`). Both engines implement `TranslationEngineUi` — a shared `UiState` `StateFlow` plus a manual-retry gate — and `TranslationStatusView` observes whichever engine is active. `TranslationEngineView` is the settings row that picks the engine and configures the remote endpoint.

**On-device (`LyricsTranslator`)** — ML Kit with on-demand language-pack downloads; same-source-and-target short-circuits, and `und` detection fails closed behind the Retry gate.

**Remote LLM (`LlmTranslator`)** — any OpenAI-compatible `/chat/completions` endpoint:

- Config prefs: `llm_base_url`, `llm_model`, `llm_api_key` (key optional; many self-hosted servers need none). A bare host input normalizes to `https://host/v1` via `normalizeBaseUrl`; an explicit scheme or path passes through untouched. Cleartext is allowed app-wide (`usesCleartextTraffic`) because the LLM server never runs on the phone.
- Requests send the lyric lines as a JSON array in ≤6000-char chunks at temperature 0.2. A lyrics-specific system prompt requires exactly N strings, line discipline (non-lexical/ad-lib lines pass through), chorus consistency, no sanitizing, and concise phrasing.
- Responses parse leniently (code-fence strip, numbered-line fallback) and must match the input line count; up to 2 in-conversation repair retries, then a hard failure that latches manual Retry — there is no silent fallback to on-device translation.
- Model IDs come from `GET {base}/models`, fetched when the Configure dialog opens, when the Base URL field loses focus after a change, and via the ↻ reload row; endpoints without a `/models` route fall back to manual entry.

## Manual lyrics search

`ManualSearchDialog` (main-screen **Manual Search** button, next to Fullscreen Lyrics) opens a dialog with editable song/artist fields pre-filled from the playing track. Search runs **LRCLIB + SyncLRC + Musixmatch**: `LrcLibClient.manualSearch` fires the queries below (LRCLIB list responses embed lyric bodies, so each response is a fully-formed candidate), and `SyncLrcClient.manualFetch` asks the aggregator with the same terms — nothing is rejected there, so its karaoke/synced/plain payload becomes one extra row (SyncLRC has no candidate-list endpoint, unlike the single-match limitation on the Musixmatch path). LRCLIB query semantics matter: structured `/search?track_name=&artist_name=` is an exact-equality filter, and free-text `q=` AND-joins every word, so a single combined `"artist title"` query returns nothing for edited terms. `manualSearch` therefore fires four queries — structured, combined free-text, title-only, artist-only — and merges/dedupes via `ManualSearch.mergeResponses` within a 10 s budget. All provider rows then pass `ManualSearch.titleIsRelevant` (normalised containment, ≥half query-token overlap, or character coverage for non-spaced scripts) so artist-wide fallback queries cannot bury the song under other recordings; filtered count appears in diagnostics. Musixmatch's list comes from `mobile.track.search` (`MusixmatchClient.manualSearch`/`parseTrackSearchResponse`); its search results carry no lyric body or duration, so those rows show `--:--` and resolve lazily on pick. Karaoke is labelled upfront when a search row carries `has_richsync` (same flag the auto macro path reads); rows without the flag are labelled Synced and may still resolve to karaoke — the applied source then says `Musixmatch · RichSync`: `applyManualLyrics` runs the hit's `fetch` lambda off-main (RichSync karaoke → line subtitle → plain body via `track.text.get`, advertisement/footer noise stripped) and reports through a callback. Rows carry their provider (`Karaoke · SyncLRC · 4:00 — …`). Applied lyrics keep one source vocabulary across providers: `Provider · Kind` (`LRCLIB · Synced`, `Musixmatch · RichSync`, …) is used for both automatic and user-picked lyrics.

`ManualSearch.toHits` converts responses with `LrcParser.parseKaraoke` (word-timed uploads become `Karaoke` hits and keep their word timing; line-timed ones are `Synced`; plain-only bodies are `Plain`; noise-only items dropped, duplicate ids deduped) and `ManualSearch.rank` orders rows: best kind first, then closest duration to the playing track, then title. Selecting a row calls `MediaTracker.applyManualLyrics`, which overwrites the active `LyricsCache` variant under the session track key (so the override survives reloads permanently), deletes the cached translation for the track, swaps the visible state, clears stale translated lines, and triggers translation — a manual apply is therefore also the force-retranslation path. It is a no-op if the track changed after the search; the in-flight search job is cancelled by track changes like other fetch work. The dialog's state (edited terms, last results, selection, in-flight flag) lives in `MediaTracker.manualSearchSession` for as long as the same track plays — reopening the dialog restores it, and a track change clears it; a search finishing while the dialog is closed still records into the session.

## Cache policy

Cache identity includes:

```text
namespace v16
STANDARD or KARAOKE variant
normalized title
normalized artist
normalized album
rounded duration seconds
```

`LyricsCache` persists `LyricWord(timeMs, text, endTimeMs)`, so RichSync, PetitLyrics Type 3, and Enhanced-LRC timing survive restart/reload.

Mode-specific cache variants prevent a Karaoke WORD_SYNC winner from pinning standard mode and prevent a standard LRCLIB LINE_SYNC winner from hiding a richer Karaoke result.

Refresh policy: **cached lyrics are permanent.** A cache hit is final and the
provider race never runs for that track/variant again until the entry is
replaced. Manual Search is the user-facing refresh path: applying a pick writes
over the cached variant, so corrected lyrics survive reloads indefinitely.
Bumping the key prefix (`v16`) is the only global flush.

**Translation cache** (`filesDir/translation_cache`) persists translated lines. Its key covers track identity (title/artist/album/duration plus Spotify track ID when present), target language, and a SHA-256 of the concatenated source lyric text; entries never expire. Engine and model are stored on the entry but deliberately *not* keyed: a translation is reused across engines, and switching engines keeps showing the existing translation until the user forces a redo. Two force-retranslation paths exist: Manual Search apply (deletes the cached translation for the track before re-translating) and the **Redo** button on the translation status line (`MediaTracker.resetTranslationForCurrentTrack`, which deletes the current track's entry for the selected target, immediately clears the visible translated sublines — the same state swap Manual Search apply uses — and re-runs the engine). Redo shows only on finished non-failure states (`Japanese → English`, `Already English`); failure phases keep the separate Retry button. A lyric text change or target-language change invalidates the entry on its own; a cache hit skips detection and network work entirely.

## Karaoke active-token timing

`KaraokeTiming.activeWordIndex()` is shared by phone/Performance and Android Auto.

- Without explicit end times, the latest token whose `timeMs <= position` is active. The scan never breaks at the first future token: providers (notably Musixmatch RichSync for CJK lines) occasionally emit out-of-order offsets, and a breaking scan would freeze the highlight until the line ends.
- With `endTimeMs`, a token stops at that explicit end.
- A gap between explicit end and the next token has no active highlight.
- An ended newer token never reactivates an older open-ended token.
- Backward seeking within a line can select an earlier token again.

The phone sweep (`PhoneKaraokeSweep`) scans tokens the same way rather than stopping at the first future token, so a payload quirk can no longer freeze the phone highlight while fullscreen keeps progressing. When the display-alignment heuristic declines a payload, the sweep falls back to the raw token spans (`textSpaceRanges`) instead of leaving the current line fully dim, and resolved display ranges are memoized per line because they are queried on every animation frame.

Timing selection and display grouping are independent: `KaraokeTiming` chooses the raw token index, then `LyricWordLayout` chooses the visible lexical range. `displayRangesForLine` is memoized (views re-query every animation frame) and `PhoneKaraokeSweep` reads `textSpaceRanges`, which keeps the sweep stepping in token space when the credibility heuristic declines a payload. `LrcParser` sorts word tokens chronologically, shifts word times onto each repeated line tag, and attaches untagged prefix text to the first token.

## Android Auto

`LyricsBrowserService` exposes:

```text
Lyrics | Karaoke | Sync | More
```

Lyrics is first/default. The main browse window shows up to 5 rows, with the active line pinned to the **first row** and the window reading forward — near the song end it shows fewer rows rather than back-filling old lines (the plain-text estimate path still keeps a full window via `stableWindowStart`). During an instrumental intro (position before the first timed line) the sung row renders as `♪` — with `♪` in its translation subtitle too — while the coming lines preview below; the Karaoke board's `AaKaraokeRows.pairFor` virtual -1 slot does the same on the board's even (top) side. Every lyric/subtitle row pair aligns its left edges: titles lead with `▶ ` (current) or `\u3000` (idle), and the subtitle carries the same `\u3000` indent, so original and translation start at one x on all rows regardless of font size. Karaoke is a Chinese-KTV-style two-row board under the same song-info header row as Lyrics: even 0-based lines ride the left-justified top row, odd lines right-justified below `AaKaraokeRows.rightAlign` emulates right justification with fullwidth-space padding to a fixed column — browse rows render left-aligned only and wrap (they never shrink text: over-padding just breaks the row across lines, so padding past the viewport is unusable). The column auto-sizes from Gearhead's `BrowserIconSize` root hint (`AaKaraokeRows.columnFromIconSize`, calibrated 43 units at the 143 px reference viewport) plus a per-car nudge persisted in the `aa_karaoke_edge` pref (an integer character delta, adjusted from the **phone** settings SYNC section — ◀/+n/▶ — never on the head unit); text plus marker wider than the column stays unpadded rather than wrapping, and `AaKaraokeRows.markerFor` compacts the ▶ marker when the padded string would otherwise exceed the column. Each row's lyric and translation share one `\n`-joined two-line title — the host scales title and subtitle glyph sizes independently per row, so padding across those two fields can never hold an edge, while one string always renders at one font scale and the bottom row's equal-column padding lands both tails on the same edge; the active pair's translation line carries the identical bare-`▶` marker string and both lines subtract the same `AaKaraokeRows.markerEm` allowance (▶ charged at 1.0 em, above its real advance), cancelling the glyph's unknown width out of both tails). Row-to-row text alignment is pixel-tuned on live hosts rather than derived from em maths: browse fields strip leading whitespace-atoms at line start unless guarded by U+200B (not Java-whitespace, halts the trim, renders ~7 px); behind the guard titles render `\u3000`/ASCII/NBSP while subtitles strip NBSP, and subtitle fields sit at a native indent left of the title's. Live-tuned constants (DHU): the marker is a bare big `▶` (~45 px lead), idle rows lead with `\u200B`+4 NBSP, and lyrics/sync subtitles indent with `\u200B\u3000 ` — all text starts converge within a few px. Retune those constants from head-unit screenshots, never from font models. Any state other than FOUND (plain-text lyrics, loading, not-found) delegates to the Lyrics tab’s builder so the Karaoke tab mirrors it exactly. Padding math runs on `AaKaraokeRows.advanceEm`, a Roboto advance-width model (CJK exactly 1 em, proportional Latin widths; the coarse one-unit-per-character estimate left multi-em gaps after English-heavy lines and wrapped others); each side flips to `line + 2` when its own line finishes and rows seek on click like Lyrics rows. Sync is a fixed 3-row timing preview with the line being sung pinned first, plus ±50 ms AA offset controls. Karaoke and Sync show "No lyrics found for this track" when the search failed (Karaoke as its only row, Sync as its second row under the AA Offset row). The track header deliberately carries no source/target language info — the More tab owns that; while the active translation engine is working, the now-playing subtitle shows "Loading translation…" (mirroring "Loading lyrics…") instead of the next-line preview. More shows provider and metadata details.

Android Auto projection activates `LyricsDemandController` for the entire projection session. Provider resolution therefore remains available even if the user navigates from Auto Lyrics to another Android Auto app; it stops only after projection disconnects unless the phone UI is still active.

Future-word look-ahead has been removed. The browse tree remains throttled (`NOTIFY_THROTTLE_MS = 500 ms`), so AA visual transitions can be coarser or slightly late compared with phone/Performance; they are no longer intentionally advanced. The now-playing subtitle is checked every 200 ms.

Other relevant constants:

| Constant | Value |
|---|---:|
| `SYNC_WINDOW_SIZE` | 3 |
| `DEFAULT_WINDOW_SIZE` | 5 |
| `CURRENT_LINE_PREFIX` | `▶  ` |
| `PAD_WIDTH` | 60 |
| `NOTIFY_THROTTLE_MS` | 500 ms |
| `SESSION_REFRESH_MS` | 1500 ms |
| `PLAIN_LOOP_DELAY_MS` | 2000 ms |

## Now-playing Card Text

The Android Auto now-playing card shows the current lyric in DISPLAY_TITLE and what follows in DISPLAY_SUBTITLE. Long lines are chunked at word boundaries to fit width budgets.

| Constant | Value | Purpose |
|---|---:|---|
| TITLE_MAX_WIDTH | 28 | Max display width for DISPLAY_TITLE (Title font is larger) |
| SUBTITLE_MAX_WIDTH | 32 | Max display width for DISPLAY_SUBTITLE |
| MIN_CHUNK_DISPLAY_MS | 1000 ms | Minimum display time for non-ELRC chunks |
| DEFAULT_LAST_LINE_MS | 5000 ms | Assumed duration of last synced line when track duration unknown |
| BROWSE_KARAOKE_WINDOW_MS | 600 ms | Karaoke bracket look-ahead for browse tree |
| TITLE_KARAOKE_WINDOW_MS | 300 ms | Karaoke bracket look-ahead for now-playing title |

Width calculation: 1 unit per Latin character, 2 per fullwidth/CJK character. Karaoke brackets 【】 count as 2 each.

Architecture:
- CardText(lyricTitle, subtitle) holds the computed text for the now-playing card
- getCardText(state) delegates to getLyricCardText() for active lyrics, or returns status text for intro/loading/error states
- getLyricCardText(state) handles chunking logic: find active line, chunk to TITLE_MAX_WIDTH with karaoke brackets if ELRC, select chunk by word time or proportion
- uildBaseMetadata(state, lyricTitle) sets DISPLAY_TITLE to lyricTitle when provided, else "Title — Artist"
- Separate karaoke caches: rowseKaraoke (600ms window) for browse tree, 
owPlayingKaraoke (300ms window) for now-playing
- ChunkHold prevents flicker on estimated (non-ELRC) chunk changes

## GitHub Actions

`.github/workflows/build.yml` runs on pull requests to `main`, pushes to `main`, `v*` tags, and manual `workflow_dispatch`.

Linux `build` job behavior:

- pull request / ordinary main push: unit tests, lint, debug APK assembly,
- release trigger: unit tests, lint, release APK assembly, artifact upload, GitHub Release creation.

A release trigger is either:

- a `v*` tag push, or
- a `main` commit whose message starts with `Release v`.

The Windows regression job runs only for `pull_request` and `workflow_dispatch`. It runs `gradlew.bat` unit tests, debug assembly, and lint. It is intentionally skipped on ordinary main pushes and release pushes.

Release workflow details are in `RELEASE.md`.

## Build and release pitfalls

`./gradlew assembleDebug testDebugUnitTest` is the standard gate, but unit tests run **unminified**: R8 only touches release builds, so reflection-dependent code (Gson) can pass tests and fail on device. Release runs `proguard-android-optimize.txt` with no AGP default consumer rules; the historical incident was `LrcLibClient.executeList`'s anonymous `TypeToken` subclass losing its generic signature — every LRCLIB *search* call threw `IllegalStateException: TypeToken must be created with a type argument` in release while the `executeSingle`/`get` path (no TypeToken) kept working, which showed up as spurious "No lyrics found" instead of a crash. Fixed 2026-10 by parsing with `Array<T>::class.java` (R8-safe) plus `-keep class * extends com.google.gson.reflect.TypeToken`. After any release build change, smoke-test each Gson-reflection network path on-device.

## Regression checklist

Run before merging provider/resolver changes:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Automated coverage should retain:

- lyrics-demand phone Activity counting and Android Auto projection behavior,
- cross-script artist corroboration and same-title mismatch rejection,
- multi-contributor artist matching,
- Japanese/Latin interleaved payload quality penalty,
- standard accuracy-first selection,
- Karaoke near-equivalent real-WORD_SYNC preference,
- PetitLyrics Type 3 start/end/token parsing,
- PetitLyrics Type 2 + Type 1 companion handling,
- explicit word-end gaps,
- Musixmatch token/matcher/RichSync regressions,
- Enhanced-LRC exact spacing and absolute timestamps,
- current SyncLRC `karaoke` field parsing,
- legacy SyncLRC type-specific compatibility,
- rejection of SyncLRC synced/plain-only responses,
- fine English fragment → source-word display grouping,
- Japanese character timing → word-like display grouping without mutating raw timing,
- synchronized candidate preference over plain lyrics,
- LLM reply parsing: JSON array, code-fence strip, numbered-line fallback, and line-count rejection,
- LLM chunk splitting at the char budget with blank/note-only lines skipped,
- remote base-URL normalization (`host` → `https://host/v1`, explicit paths preserved),
- translation cache round-trip and invalidation on lyric-text/target change only (engine/model switches deliberately reuse the stored translation),
- karaoke word-token sorting, repeated-line word-time shift, and untagged prefix retention,
- manual search title-relevance filtering.

Manual Phone / DHU checks:

1. With Android Auto disconnected and Auto Lyrics closed, changing tracks in another media app does not start new provider resolution.
2. Opening Auto Lyrics on the phone immediately resolves the currently selected session.
3. Connecting Android Auto activates provider resolution without requiring the phone UI to remain open.
4. Lyrics / Sync / More remain ordered correctly.
5. Window sizes and translation layout remain correct.
6. `▶` gutter alignment remains stable.
7. Sync stays at 3 rows and ±50 ms changes AA offset.
8. More displays provider/details without changing timing.
9. Plain lyrics still advance.
10. Karaoke source text retains correct spacing for English, Japanese, and mixed scripts.
11. No future timing token is highlighted solely to hide browse latency.
12. Karaoke ON can select an equivalent word-timed provider while Karaoke OFF independently retains the standard winner.
13. Switching translation engine between on-device and remote re-translates the current track without restarting the app, and replaying a cached track shows the translated lines immediately.
14. Manual Lyrics Search (main-screen button) with edited terms lists Karaoke/Synced/Plain results with durations, and the picked result replaces the lyrics, drops the previous translation immediately, survives reload from cache, and translates like any found result.
15. Karaoke word highlighting advances through files whose word timestamps omit fractional seconds, and CJK lyrics render without injected inter-character spaces (including per-character SyncLRC token streams).
16. The fullscreen performance view shows translated sublines under each lyric line, colored with the same active/inactive focus.
17. Manual search lists SyncLRC and Musixmatch rows (provider-labelled); a Musixmatch pick shows 'Fetching lyrics…' then resolves to karaoke/synced/plain.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/AutoLyricsApp.kt` | Process initialization, phone Activity demand tracking, Android Auto `CarConnection` tracking. |
| `app/src/main/java/com/autolyrics/media/LyricsDemandController.kt` | Process-wide phone/Android-Auto demand state. |
| `app/src/main/java/com/autolyrics/media/MediaListenerService.kt` | Media-session discovery/selection and demand-gated forwarding to `MediaTracker`. |
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Media tracking, provider concurrency, resolver orchestration, cache refresh. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB lookup and local matching. |
| `app/src/main/java/com/autolyrics/lyrics/MusixmatchClient.kt` | Musixmatch mobile token/macro/RichSync/subtitle flow. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | PetitLyrics Type 3 token timing and Type 2 line timing. |
| `app/src/main/java/com/autolyrics/lyrics/SyncLrcClient.kt` | Karaoke-only SyncLRC public API integration. |
| `app/src/main/java/com/autolyrics/lyrics/LrcParser.kt` | Standard and Enhanced-LRC parsing. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Metadata/quality/source scoring and mode-aware selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | STANDARD/KARAOKE persistent cache. |
| `app/src/main/java/com/autolyrics/lyrics/TranslationCache.kt` | Persistent translation cache keyed by track+target+engine+model+source-text hash. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsTranslator.kt` | On-device ML Kit engine: language detection, pack downloads, per-line translation. |
| `app/src/main/java/com/autolyrics/lyrics/LlmTranslator.kt` | Remote OpenAI-compatible engine: chunking, lenient parsing, repair retries, `/models` listing. |
| `app/src/main/java/com/autolyrics/lyrics/ManualSearch.kt` | Manual-search hit model, response conversion, duration-aware ranking. |
| `app/src/main/java/com/autolyrics/ManualSearchDialog.kt` | Main-screen button dialog for manual lyrics lookup and override. |
| `app/src/main/java/com/autolyrics/lyrics/KaraokeTiming.kt` | Shared raw-token active-index selection. |
| `app/src/main/java/com/autolyrics/util/LyricWordLayout.kt` | Exact source layout and timing-token → display-range grouping. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser, MediaSession, Sync controls, Karaoke rendering. |
| `.github/workflows/build.yml` | CI and release build. |
| `RELEASE.md` | Release checklist, version/tag invariants, and release triggers. |
