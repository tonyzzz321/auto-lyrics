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
| **AA offset** | Global Android-Auto rendering delay stored as `aa_offset_ms`; edited only from the phone's **Global AA Sync** row, applied on top of the lyrics offset on AA browse surfaces, never shown or edited on the head unit. |
| **Lyrics offset** | Per-LYRICS timing sync stored as `lyrics_offset:<key>` where the key hashes the loaded line list's text AND stamps; edited from the phone main-view sync bar and the AA Sync tab. |
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
                                  ├─ SyncLrcClient ────────────┤ always
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

**Media-source whitelist** (`monitored_packages`, Media apps picker in the Media source settings section): an empty set (default) follows every app; a non-empty set is applied in `pickBestSession` **before** best-session selection via the pure `sessionAllowed(pkg, ownPackage, monitored)` predicate, so an unchecked app can never win the PLAYING-preference race on the phone or in Android Auto (both render the same selected session). Saving while the followed app is unchecked re-selects immediately through a `SharedPreferences` listener that fires `updateSessions()` without waiting for the reconciliation tick. Every package ever seen with a media session persists in `seen_media_packages` to populate the picker; whitelist entries for uninstalled apps stay in the pref (survive reinstall) but are hidden from the dialog.

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

`MediaTracker.getCurrentPositionMs()` includes the PER-LYRICS offset (`state.offsetMs`): it travels with the loaded line list — a collector keyed on `lyricsOffsetKey(state.lines)` (SHA-16 of `timeMs|text` per line) swaps the stored value whenever the published lyrics change identity, so a replay or cache hit rides its tuned offset while different text (new provider, Manual Search apply) starts at 0. Writes with nothing loaded are no-ops; zero values are removed from prefs, and the legacy global `lyrics_offset_ms` key is deleted at init. Android Auto applies the additional global `aa_offset_ms` in `LyricsBrowserService`. `LyricsBrowserService.prefsListener` calls `forceRefresh()` on `aa_offset_ms`, `translation_enabled`, `aa_karaoke_enabled`, and target-language changes so head-unit browse reflects toggles immediately, not at the next track. End-of-song rewind artifact: players briefly report position 0 in a non-playing state while the next track's callbacks are in flight; `handlePlaybackStateChanged` HOLDS the song-end position whenever a non-playing callback carries <2 s against a >10 s baseline (real seeks land in a playing state and are accepted; a genuine track change resets the baseline to 0), so the AA boards never flip to the intro during the outro handoff. Phone side: `MediaTracker` keeps the listener as the fast path but additionally runs a 500 ms preference-reconciliation poll (`startPreferenceReconciliation`) that diffs translation-enabled/karaoke/target/engine/model and replays the same instant-apply handlers, so a lost listener callback can no longer strand a toggle until the next track.

Do **not** add a provider-wide pre-offset merely because lyrics appear early on one playback path. Media-session logical position can lead audible output because of Bluetooth, codec, Android Auto, DAC, or head-unit buffering. Stable output-path latency belongs in the existing offset controls unless a provider timestamp bias is demonstrated across multiple playback paths.

## Provider execution

Provider work starts only while lyrics demand is active.

All four providers race on every track change, in every mode — Karaoke mode no longer gates who is queried (it selects the winner and the cache variant):

```text
LRCLIB ───────┐
Musixmatch ───┤
SyncLRC ──────┼─ parallel
PetitLyrics ──┘  when configured
```

Each provider gets a hard **5 second total budget** at the `MediaTracker` boundary. Provider response time is not part of the score; a provider loses due to speed only when it misses its budget and produces no candidate.

A miss is classified TRANSIENT vs GENUINE by leg outcome. Every client helper that used to swallow a transport death into a silent `null` now records it into a per-fetch `transport` list threaded from `MediaTracker` (HTTP 429/5xx, network exceptions, malformed 200s; LRCLIB/SyncLRC 404 and Musixmatch 8xxxxxx API statuses stay CLEAN misses - those are the providers' real "no lyrics" answers). `isTransientMiss` fires only when NO provider offered any candidate and at least one leg died. The fetch then runs the BOUNDED RETRY LOOP: up to `AUTO_RETRY_MAX` (2) backoff races, each after `AUTO_RETRY_BACKOFF_MS` (3 s, the track stays LOADING and the AA header keeps ticking), and each retry race includes ONLY the legs `retryProviders` marks dead (no candidate + timeout or recorded transport error) - clean empty answers are FINAL for the race and are never re-fetched, so a retry costs requests only where one actually failed. Each pass is cancelled by any track change and skipped if the state left LOADING meanwhile (e.g. a Manual Search apply landed). Failures after the last attempt resolve NOT_FOUND - and that latch is PER PLAYBACK: `replayRearmIfLatched` re-arms the full cycle - all legs - ONLY when playback STARTS AT THE BEGINNING again: a PLAYING callback with the position inside `START_OVER_WINDOW_MS` (2 s) after at least `REPLAY_MIN_HEARD_MS` (15 s) was heard (back-to-start / play-from-top); nothing caches NOT_FOUND, so the re-race is genuine. Scrub-backs and forward seeks that do not restart at 0 never fire it, FOUND lyrics never re-fetch, and while any cycle is running the status is LOADING so no second cycle can start. Pause/unpause cannot cross the threshold, and the end-of-song position-0 artifact is a NON-playing state handled by the endRewind hold, so neither reloads. Providers that all answered cleanly with nothing never burn a retry.

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

Before anything is scored, `selectBest` drops candidates whose payload is ALL non-lyric lines - blank, music-symbol, or production-credit (`hasDisplayableContent`, keyed on the display's own `LyricDisplay.isSymbolText`/`isCreditLine` pair so acceptance and rendering share one vocabulary). Such a payload renders as nothing, and accepting it would cache the track permanently as a fetch success — silencing the retry gate and out-competing the next provider that has real text. Rejection happens at resolution only: an all-symbol payload scores as if that provider returned nothing, so the next-best real candidate wins; an all-junk field resolves NOT_FOUND with the normal manual-retry latch. Manual Search rows ride the same gate (`hasDisplayableLines`): non-lyric rows never enter the pick-list, and lazy-provider bodies are resolved eagerly AT SEARCH TIME (`eagerBodies`: top 6 rows per leg, parallel under the remaining leg budget) so candidates whose body the provider refuses, times out on, or fills with junk are dropped before listing — every row the dialog shows is clickable (the leg's count line folds the fraction: `musixmatch: k/n usable` when bodies were dropped, plain `musixmatch: n` when all survived). The gate is defense in depth since the ingest sanitizer (`LyricDisplay.stripNonLyric`) landed: non-lyric lines never reach scoring at all, and an all-non-lyric payload arrives EMPTY from the parser and resolves NOT_FOUND on its own.

One leak needs track context the context-free sanitizer cannot have: SyncLRC's aggregated sources (NetEase/QQ-family uploads) sometimes ship the track's OWN title/artist as the first "lyric" line (`側面 - 張國榮`, `歌曲：X 歌手：Y`, or a standalone title-then-artist pair). `LyricDisplay.stripTitleArtistHeaders` runs once per load in `MediaTracker.prepared` — the state boundary every path crosses (cache hit, race winner, manual apply) — so already-cached payloads self-heal at load. Rules are deliberately narrow because a line equal to the song title is usually the HOOK, not garbage: only the FIRST 3 lines are eligible, and only when the FULL title and the FULL artist both appear — the normalized line (letters+digits only, fullwidth ASCII folded) is their exact concatenation in either order, a short labeled form (a header label word like 歌曲/歌手/singer present), or an adjacent exact title+artist pair. A leading hook that only equals the title survives. Like the Chinese-form conversion, the strip is never persisted: the cache keeps the provider's original bytes. Both match keys are additionally folded through the offline trad->simp table (`ChineseVariant.toSimplified`, passed by `prepared`): file tags mix written forms freely — the live 凉茶王 leak was a session title tagged `涼茶王` (trad) against a header line `凉茶王-东山少爷` (simp), which an unfixed key reads as two different names — and the fold is COMPARISON-ONLY (display text and cache bytes keep their own form; the kana veto keeps Japanese kanji out of it).

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

`LyricWordLayout` aligns timing tokens back to `LyricLine.text`, uses Java/Android word boundaries, and applies a conservative Japanese display heuristic that merges adjacent hiragana suffixes into a preceding kanji-containing range. The phone main-screen list centers each line with `gravity=center_horizontal` and NO flowed prefixes: the ▶ marker is painted into a constant `LeadingMarginSpan` gutter (lyric line only, never the translation subline) and `paddingEnd` cancels the gutter's half-width box shift — flowed prefixes (`"▶  "` vs spaces) center every line on a different axis because the marker glyph's advance is font-dependent, which read as an off-center block with a jogging current line. Multi-syllable chunk payloads therefore display closer to `君を / 忘れない` than six separate highlights. Two refinements: payloads whose tokens are syllable-sized for any CJK script — kana, Han, or Hangul, since SyncLRC tokenizes Korean and Chinese per character too — highlight glyph-by-glyph instead of jumping in dictionary-word steps, and every highlight range sweeps through trailing punctuation/spaces up to the next token so ending punctuation is never left unlit when the line changes.

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

RichSync chunk offsets are occasionally non-monotonic (notably CJK lines); `parseRichSyncWords` re-sorts tokens chronologically before returning them. Some payloads also store absolute track times in `o` instead of line-relative offsets; any offset exceeding the line end (`te`) that fits the line window once `ts` is subtracted is reinterpreted relative to the line, because absolute-polluted tokens otherwise land past the line and freeze the highlight until the next line starts. Payloads can also hold one coarse chunk (often a whole CJK clause) across seconds of continuing singing; `fillUncoveredText` synthesizes per-character tokens for any run of line text no chunk covers (some payloads time only the line's first stretch), spread between the surrounding token times or the line boundary; `spreadLongGaps` then spreads multi-character tokens that hold >=1.2s (mid-line gap, or a whole line riding a single chunk) character-by-character across the hold, and `LrcParser.collapsePunctuation` folds standalone punctuation tokens into the preceding word (punctuation is never sung; both the RichSync repair path and the LRC karaoke tokenizer run it). The line boundary comes from `effectiveLineEndMs`: `te` is trusted only when it leaves a real tail past the last token — polluted lines set `te` at the line start — otherwise the next line's start bounds the hold. Both exist because frozen highlights mid-line read as a stall-then-jump bug. RichSync chunks otherwise remain raw `LyricWord` timing tokens. Older builds rode anomaly counters (`fix`/`oob`/`dup`/`miss`/`gapXs`) on the source label; the counters are gone, and `LyricsCache.sanitizeSource` truncates those pills out of already-cached labels at load. If RichSync is absent or unusable, line-synced subtitle LRC becomes `LINE_SYNC`.

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

`SyncLrcClient` runs on every auto search. It consumes the current public API (untyped, so every lyric tier comes back in one response):

```text
GET https://api.synclrc.dev/lyrics
    ?track=<title>
    &artist=<artist>
    [&album=<album>]
    [&duration=<rounded seconds>]
```

`/lyrics` live-aggregates upstream sources and can serve documented `503
SOURCES_UNAVAILABLE` for extended periods, and its matching (like the DB's)
is literal — a kana session title over a romanized entry matches nothing.
The auto ladder is therefore exact `/lyrics` → `itunesFallback`: the iTunes
Search API resolves alias/romanized spellings and each candidate pair is
fetched as an exact `/lyrics` row (`pickBestCandidate` picks the winner,
capped at 3 fetches). A blank artist never skips the leg, but the endpoint
REQUIRES both params (title-only `/lyrics` is a contract 400:
`Missing 'track' and 'artist' parameters`), so the exact stage is dropped
and the catalog ladder runs title-only — guess mode (the artist tiebreak
passes everything, so duration-distance carries the pick), which still
beats the leg contributing nothing; Manual Search stays the correction
path. iTunes-resolved rows are unaffected: every ladder fetch carries the
candidate's own artist. Manual runs BOTH stages
(`manualSearch` = exact `/lyrics` with the edited terms → `itunesRows`
with `CATALOG_LOOKUP_LIMIT_MANUAL = 6`, the whole iTunes page — a human
picks listed rows, so more candidates help where auto's single winner only
needs three). The catalog ladder's dedupe set is seeded with the requested
names, so the stage-1 row is never re-fetched and catalog rows append after
it; blank artist drops stage 1 (needs both params) and runs the ladder
title-only. The DB `/search`
listing stays retired — literal matching found no alias rows. Leg
diagnostics use the same shape as the body-backed legs: `synclrc: N` (every
fetch produced a row), `synclrc: k/n usable` (n name-pairs fetched, k
listed — per-candidate ladder deaths and clean misses fold into the
fraction instead of emitting one death line each), `synclrc: 0 (no catalog
match)` (nothing fetched, nothing died), `synclrc: <cause>` (the exact
call itself died with nothing else probed). Only the leg's own request
deaths reach the transport list; the ladder fetches write to a scratch
sink (`itunesRows` also returns its attempt count).

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

Auto Lyrics accepts every payload tier: a non-empty `karaoke` body with timed tokens wins, otherwise `synced` lines enter as LINE_SYNC and `plain` text as PLAIN (the aggregator's NetEase/QQ/Kugou/LDDC sources are often the only synced copy of a CJK track). For compatibility with older SyncLRC deployments, `SyncLrcClient` also routes the former type-specific `lyrics` shape by its `type` field:

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

The gear button toggles `layout_font_settings` in `activity_main.xml`, organized in four sections: **Media source** (Media apps picker), **Phone** (Size, Font), **Android Auto** (Global AA Sync ±100 ms + reset, Karaoke edge ◀±n▶ + reset), **Lyrics** (Karaoke Mode, Translate Lyrics; the dynamic target/engine/status rows are inserted here relative to the translate row via `indexOfChild`). The Android Auto section sits directly under Phone and is wrapped in `section_aa`, hidden until `CarConnection` reports a head unit — every row in it calibrates the connected car (the edge knob persists per unit as `aa_karaoke_edge:<fingerprint>`). Deliberately outside the panel: Manual Search and Fullscreen buttons (button bar), Jump-to-Current, and the per-lyrics fine-offset presets + Tap to Sync (live mid-song controls under the lyrics — they edit `lyrics_offset:<key>`, and no-op with `No lyrics loaded to sync` while nothing is loaded). Row typography is uniform (since 2ee0eb1/497cd1c): every row label 13sp #CCCCDD regular, every in-section gap 8dp, control buttons 13sp mixed-case — new rows must match.

## Translation engines

`MediaTracker.translateIfNeeded()` routes by the `translation_engine` pref (`on_device` default, `llm`). Both engines implement `TranslationEngineUi` — a shared `UiState` `StateFlow` plus a manual-retry gate — and `TranslationStatusView` observes whichever engine is active. `TranslationEngineView` is the settings row that picks the engine and configures the remote endpoint.

**On-device (`LyricsTranslator`)** — ML Kit with on-demand language-pack downloads; the already-in-language short-circuit compares BASE languages (`TranslationLanguages.baseLanguage`, so the two Chinese written forms never engine-translate into each other), and `und` detection fails closed behind the Retry gate. ML Kit receives concrete codes: Chinese targets are `zh-CN`/`zh-TW` — the two separate TranslateLanguage codes with their own model packs (a bare `zh` was never a valid `TranslateLanguage.fromLanguageTag` code, so the historical bare-Chinese target could not actually run on-device).

**Remote LLM (`LlmTranslator`)** — any OpenAI-compatible `/chat/completions` endpoint:

- Config prefs: `llm_base_url`, `llm_model`, `llm_api_key` (key optional; many self-hosted servers need none). A bare host input normalizes to `https://host/v1` via `normalizeBaseUrl`; an explicit scheme or path passes through untouched. Cleartext is allowed app-wide (`usesCleartextTraffic`) because the LLM server never runs on the phone.
- Requests send the lyric lines as a JSON array in ≤6000-char chunks at temperature 0.2. A lyrics-specific system prompt requires exactly N strings, line discipline (non-lexical/ad-lib lines pass through), chorus consistency, no sanitizing, and concise phrasing.
- Responses parse leniently (code-fence strip, numbered-line fallback) and must match the input line count; up to 2 in-conversation repair retries, then a hard failure that latches manual Retry — there is no silent fallback to on-device translation.
- Model IDs come from `GET {base}/models`, fetched when the Configure dialog opens, when the Base URL field loses focus after a change, and via the ↻ reload row; endpoints without a `/models` route fall back to manual entry.

**Chinese written forms** — the target list has no bare Chinese entry: `zh-CN` (*Simplified Chinese · 简体中文*) and `zh-TW` (*Traditional Chinese · 繁體中文*) are separate targets. Picker labels append the NATIVE name whenever it differs from the English one (`Japanese · 日本語`, `German · Deutsch`, …) via `TranslationLanguages.displayName`; the selected-row value, prompts and status lines use the plain `englishName`. `normalizeLanguageTag` resolves every zh script/region spelling (`zh`, `zh-Hans-CN`, `zh-Hant`, `zh-TW`, …) onto the two codes — legacy bare `zh` targets migrate to Simplified, matching what the historical target produced — parsing the RAW tag because JVM `java.util.Locale` drops script subtags while Android's does not.

Already-Chinese lyrics in the OTHER written form are converted ON DEVICE, never re-translated (`util/ChineseVariant`): OpenCC's two-stage algorithm over dictionaries generated by `scripts/build_chinese_dicts.py` from OpenCC master (Apache-2.0) — greedy longest-match over ~49k context PHRASE pairs (this is what protects the one-to-many characters: 皇后/干戈/出面 survive S→T while 后面→後面, 头发→頭髮) then a per-character fallback taking the most common candidate. Every pair is same-length IN UTF-16 CODE UNITS (the Python builder measures `utf-16-le`, not code points — non-BMP compatibility ideographs otherwise break index preservation in Kotlin), and character keys are BMP-singles; the tests re-validate the shipped assets. Detection counts form-exclusive characters (both character tables are identity-free, so every key is an exclusive marker) with majority-wins and tie = hands-off. **Kana anywhere hard-vetoes detection AND conversion** — Japanese payloads never change. Conversion rides the translation pipeline (Translate-Lyrics OFF = show exactly what the provider shipped) and applies PER LOAD at the state boundaries (`MediaTracker.prepared`: cache hit, race winner, manual apply) — the lyrics cache keeps the provider's ORIGINAL form, so flipping the target re-converts the playing track instantly from cache, and `TranslationCache` (keyed by source-text hash) re-keys naturally, old entries aging harmlessly and flip-back hitting the old translation.


## Manual lyrics search

`ManualSearchDialog` (main-screen **Manual Search** button, next to Fullscreen Lyrics) opens a dialog with an editable SONG TITLE field (required — Search refuses blank titles with `Song title required`) and an optional ARTIST field, both pre-filled from the playing track. Search runs **LRCLIB + PetitLyrics (when configured) + SyncLRC + Musixmatch**: `LrcLibClient.manualSearch` fires the queries below (LRCLIB list responses embed lyric bodies, so each response is a fully-formed candidate), and `SyncLrcClient.manualSearch` runs BOTH SyncLRC stages: the exact `/lyrics?track=&artist=` call with the (possibly edited) terms first, then the alias-aware catalog ladder (`itunes.apple.com/search?term=<title artist>&entity=song` — a kana title resolves to the romanized `Connect` the catalog indexes) over the whole iTunes page (up to 6 rows, deduped against stage 1), with a 6 s internal budget so slow responses shed candidates instead of the leg; a blank artist field drops the exact call (the endpoint requires both params — title-only is a 400) and lists ladder rows only. The DB `/search` listing is gone from both paths: its literal matching never returned alias rows. iTunes rows rank through the same `ManualSearch.rank` as everything else. LRCLIB query semantics matter: structured `/search?track_name=&artist_name=` is an exact-equality filter, and free-text `q=` AND-joins every word, so a single combined `"artist title"` query returns nothing for edited terms. `manualSearch` therefore fires ONE title-only free-text query (the only spelling that survives real metadata; artist no longer goes over the wire) and `ManualSearch.rank` scores artists — match-vs-cover ordering lives there: kind first, then artist penalty (`artistPenalty` normalises featurings and parentheticals, containment counts as a match), then duration delta, then title. The providers run as parallel fault-isolated legs, each under its own 10 s wall-clock cap (`MediaTracker.manualLeg`: `withTimeout` + `runInterruptible`), so a stalled or failing provider degrades to one diagnostic line (`lrclib: timed out after 10s`, `musixmatch: SocketTimeoutException`, `synclrc: 0 (no catalog match)`) and can never void the rows a healthy provider already returned — there is no shared search budget any more. Each leg additionally records its TRANSPORT deaths (non-404 HTTP, network exceptions) into a per-leg list; `launchManualSearch` collects leg timeouts/exceptions plus those entries into a `deaths` list delivered with the results — the status contract's Error signal — while the diagnostics lists stay the clean per-provider lines. All provider rows then pass `ManualSearch.titleIsRelevant` (normalised containment, ≥half query-token overlap, or character coverage for non-spaced scripts) so artist-wide fallback queries cannot bury the song under other recordings; filtered count appears in diagnostics. PetitLyrics joins the race only when its identity fields are configured (`manualSearch` lists validated candidates with a body lambda per row; the leg's eager-body phase decodes them at search time — Type 3 word payloads as Karaoke, Type 2 plain bodies as Synced, labels derived from the body's word content); unconfigured it contributes nothing silently. Every text-carrying row passes the auto path's junk gate (`LyricsProviderResolver.hasDisplayableLines` — blank/music-symbol/credit-only payloads never become rows). Musixmatch's list comes from `mobile.track.search` (`MusixmatchClient.manualSearch`/`parseTrackSearchResponse`); its search results carry no lyric body or duration, so those rows show `--:--`. The leg then runs `fetchManualLyrics` eagerly for the top 6 candidates (RichSync karaoke → line subtitle → plain body via `track.text.get`, advertisement/footer noise stripped): rows whose body arrives usable are listed with real content and a kind derived from the payload's word content (`Musixmatch · Karaoke`/`· Synced` are the LIST labels now, never a mid-list surprise), and refused/junk/failed bodies simply never appear. `applyManualLyrics` no longer fetches — applied rows are always body-backed, and a row that still carries the fetch lambda or a junk-only payload is an invariant violation refused with `Provider returned no usable lyrics`. Rows carry their provider (`Karaoke · SyncLRC · 4:00 — …`). Applied lyrics keep one source vocabulary across providers AND viewers: `Provider · Kind` (`LRCLIB · Synced`, `Musixmatch · Karaoke`, `LRCLIB · Not synced`, …) is used for the phone label, the Manual Search rows and the AA More tab alike — provider jargon (`· Line`, `· RichSync`, `· Plain`) is retired. The Kind always describes the payload: fetch derives it from word content (enhanced LRCLIB uploads count as Karaoke), and `LyricsCache.sanitizeSource` re-derives it at load (words ⇒ Karaoke, none ⇒ Synced, plus the legacy jargon mapping), so pre-existing cache entries self-correct.

`ManualSearch.toHits` converts responses with `LrcParser.parseKaraoke` (word-timed uploads become `Karaoke` hits and keep their word timing; line-timed ones are `Synced`; plain-only bodies are `Plain`; noise-only items dropped, duplicate ids deduped) and `ManualSearch.rank` orders rows: best kind first, then closest duration to the playing track, then title. Selecting a row calls `MediaTracker.applyManualLyrics`, which overwrites the active `LyricsCache` variant under the session track key (so the override survives reloads permanently), deletes the cached translation for the track, swaps the visible state, clears stale translated lines, and triggers translation — a manual apply is therefore also the force-retranslation path. It is a no-op if the track changed after the search; the in-flight search job is cancelled by track changes like other fetch work. An **Include unsynced results** checkbox (default OFF — the pick-list is timed rows only until the user opts in) filters `ManualHitKind.PLAIN` rows out of the pick-list via `ManualSearch.applyUnsyncedFilter` — karaoke and line-synced rows always stay. The **status line shows only the outcome** (`ManualSearch.statusLine`): `N results · track 4:31` with the listed (post-filter) row count, `0 results · track 4:31` for a clean all-empty, or a red `Error · track 4:31` when nothing listed AND at least one leg died transport-wise — counts, catalog misses, bodies kept, hidden-unsynced tallies and per-leg causes NEVER print on it; the status line is clickable and opens a monospace **Search diagnostics** dialog (diagnostics lines + death lines + the track duration, `Close` returns to the still-open search dialog). The dialog's state (edited terms, last results, diagnostics and deaths, selection, include-unsynced flag, in-flight flag) lives in `MediaTracker.manualSearchSession` for as long as the same track plays — reopening the dialog restores it, and a track change clears it; a search finishing while the dialog is closed still records into the session.

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

`LyricsCache` persists `LyricWord(timeMs, text, endTimeMs)`, so RichSync, PetitLyrics Type 3, and Enhanced-LRC timing survive restart/reload. Cached payloads are the SANITIZED line list (non-lyric lines already stripped by the parser at save time); `get` re-applies `LyricDisplay.stripNonLyric` after `repairWordTags` so entries written before the sanitizer self-heal at load. Instrumental markers are never stored - they are derived from the sanitized timing at display time, which keeps the translation engines' index-for-index line-count validation intact.

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

Lyrics is first/default. The main browse window shows up to 5 rows, with the active line pinned to the **first row** and the window reading forward — near the song end it shows fewer rows rather than back-filling old lines. PLAIN_ONLY on this tab is a different animal: the FULL lyric list at once (no window), every row idle-prefixed, `plain_line_N` ids (non-parseable - zero stamps must never become a seek-to-0), under a static header tail (`artist · duration`, no ticker): a plain song changes NOTHING over time, so the refresh gate's plain signature is constant and the list is pushed exactly once per track - a touch-scroll is never rebuilt under the finger. Instrumental stretches are not payload content: providers' non-lyric lines (`♪` runs, `[Music]`/`[Verse]`-style tags, section headers, blank lines) are STRIPPED AT INGEST (`LyricDisplay.stripNonLyric` at every parse point; legacy cache entries self-heal at load), and the silence between sung lines DERIVES marker rows (`SungLine.virtualRows`). An INTRO marker opens the sequence when the first line starts >= `SungLine.INTRO_MIN_MS` (4 s) in; an INTERLUDE marker follows any line whose derived END - last token start + `LINE_SUSTAIN_MS` (5 s) on token-timed payloads, else start + the SYLLABLE-PROPORTIONAL HOLD on line-timed ones: `PronouncedLength.estimate(line.text)` x `MS_PER_SYLLABLE` (800 ms), clamped to `LINE_HOLD_MIN_MS` (2 s) / `LINE_HOLD_MAX_MS` (15 s) - sits more than `GAP_MIN_MS` (5 s) before the next line starts (equivalently: karaoke gaps > 10 s from the last word start; line-timed gates ride the line's estimated sung length, e.g. a 3-syllable line earns a marker past start + 7.4 s). The estimator is a deliberate one-pass approximation (`util/PronouncedLength`): Han = 1 syllable per character, but in lines carrying KANA the kanji price comes from `assets/japanese/kanji_mora.txt` - per-kanji mora counts of the most common standalone reading, derived from JMdict (CC BY-SA 4.0) by `scripts/build_japanese_mora.py`, fallback 1; kana count morae (small ya/yu/yo merge, sokuon and the prolonged mark count); Hangul 1 per block; Latin/Cyrillic/Greek count vowel groups with the classic Latin silent-e/-le/-ed adjustments; Arabic/Hebrew long-vowel letters counted; digits 1, punctuation 0. A derived end may overrun the next line's start (routine: most lines sit closer than the hold) and that is harmless - ends are silence references, never display windows, and every handoff keys on line STARTS. Markers are first-class rows: every tab renders them as a COUNTDOWN row - one music glyph per remaining second until the NEXT LINE AFTER THAT MARKER starts (max four, `LyricDisplay.countdownSymbol` + `SungLine.markerCountdownSeconds` - each marker row counts toward its OWN successor, never the first-future line overall: a previewed interlude sitting far ahead of a ticking intro must hold the cap 4 while the intro runs 4->1; the current row may use `SungLine.countdownSeconds` because its target coincides), marker-free on the `IDLE_LINE_PREFIX` lead that still holds the ▶ advance; digits switch 300 ms early (`DISPLAY_LEAD_MS`) because a browse-row push needs ~0.5 s to paint, and the position heartbeat ticks at 500 ms. A short intro (< 4 s) earns no marker: nothing counts down and the identity card holds until the first line.Every lyric/subtitle row pair aligns its left edges: titles lead with a bare `▶` (current) or `\u200B`+`\u3000` (idle — the exact 1.0 em lattice atom whose advance the bottom row's pixel-perfect padding proves equals `▶`'s on live hosts; NBSP runs sat off-lattice and drifted), and subtitles carry their own guarded indent, so original and translation start at one x on all rows regardless of font size. Karaoke is a Chinese-KTV-style two-row board under the same song-info header row as Lyrics: parity is SEQUENCE space (user-settled model - the countdown is a row like any lyric) - rows at an even offset from the sequence anchor ride the left-justified TOP row, odd offsets the right-justified bottom row. The anchor is ALWAYS sequence index 0: an INTRO marker, when it exists, is row 0 and owns the top slot - the first real lyric previews and is sung from the BOTTOM; without one the first lyric rides top. Mid-song marker rows are sequence members too, shifting the parity of every later row (and markers never stack, so a marker's board partner is always a real lyric).THE NO-TRANSLATION BOARD IS COMPACT SINGLE LINES (user-settled: the constant two-line grid and its **Karaoke single lines** settings switch were RETIRED; the pref `aa_karaoke_single_line` is deleted, single-line is the only mode. The original causal bet - that removing the legacy `padEnd(60)` over-column padding cured the short-row jump - was FALSIFIED the same day by a Chinese song with zero translation activity still flipping rows while every row was byte-identical and sub-column; the grid's immunity was height, not content (see THE OPEN FLIP)): with translations published the lyric leads and the translation (or an invisibly filled second row when a row's translation is missing) follows and heights hold through the run; without them each row is ONE lyric line (an empty hold slot still rides a `\u2800` row so it cannot collapse). The filler is `\u2800` BRAILLE PATTERN BLANK, a printable blank: whitespace-only filler lines (the lead run, `\u3000`, padding runs) - and a truly empty line - are trimmed by the host and the line collapses, which is what kept the bottom row jumping. The host's every-second clock refresh must therefore only repaint, never re-animate (the LOADING/NOT_FOUND/ERROR/empty message rows keep their `\u2800` second line - stable-height ticking states): the every-second "jumping row" is Gearhead animating its list re-bind after each notifyChildrenChanged re-fetch, and there is NO content-side cure: over-column whitespace runs and content diffs feed it (the padEnd(60) lesson) but byte-identical rows INSIDE the calibrated column flip too (live-verified on single-line boards; truncation changed nothing). The only immune states are lists the clock never re-binds - Sync and More exclude the header-tick from their notify mask, so they are not re-fetched per second - and lists whose rows are ALL constant height. Gearhead has no per-row browse update: its dex carries only whole-folder notifyChildrenChanged (no changed-item/incremental mechanism exists in the framework service API either) `AaKaraokeRows.rightAlign` emulates right justification with fullwidth-space padding to a fixed column — browse rows render left-aligned only and wrap (they never shrink text: over-padding just breaks the row across lines, so padding past the viewport is unusable). Legacy equal-width tail padding on Lyrics/Sync rows (`padEnd(60)`) was REMOVED for this reason (live pre-fix: the Lyrics tab jumped per-second while the board never did - the board only ever pads TO the column, the lyrics rows padded 60 units PAST it, and the host re-laid-out the overlong whitespace run on each clock rebind). The column auto-sizes from Gearhead's `BrowserIconSize` root hint (`AaKaraokeRows.columnFromIconSize`, 43 units at the 143 px DHU reference, clamped to 40..64 — icon size and row-text scale are NOT constant across hosts: a production unit shipped icon=56 with the same row capacity as the DHU's 143, so the column never collapses below the calibrated capacity) plus a per-head-unit nudge (an integer character delta applied AFTER the clamp so the knob stays live on floored hosts, adjusted from the **phone** settings Android Auto section — ◀/+n/▶ — never on the head unit). The head unit is identified by `auto/AaHost`: the sorted root-hints bundle from `onGetRoot` is SHA-256-hashed into a per-model fingerprint (Gearhead exposes no serial; identical models sharing a fingerprint share a viewport, which is exactly what the calibration tracks), and the nudge persists per fingerprint in `aa_karaoke_edge:<fp>`, seeded from the legacy global `aa_karaoke_edge` on first sight of a car. The RESOLVED column (model + delta) is additionally PINNED per fingerprint in `aa_karaoke_column:<fp>` (`AaHost.column`): the delta is a nudge against the column MODEL, so a release that changes the model (icon-size derivation, clamp, margin) would silently move a calibrated car's edge at the same pref value - the pin makes upgrades unable to re-base it. Only moving the knob (which re-resolves against what this build renders) or Reset (which drops the pin and hands control back to the current model) changes a pinned column; hint-less hosts have no identity to pin to and keep the plain delta read. The identity clears on projection disconnect, and the phone settings row is only visible while a head unit owns the connection (its ⓘ explains which car is being tuned). The same column also budgets the Lyrics/Karaoke header row truncation (`AaKaraokeRows.truncate`). The header's two fields render at DIFFERENT scales and the host wraps rather than shrinks, so they cannot share one raw budget: the SUBTITLE truncates at the column UNADJUSTED (its smaller glyph scale and native indent cancel the art-icon inset - DHU-verified exact at the same edge the board pads to; the earlier -2.0 em inset + -1.5 em wrap-margin deductions had it ellipsizing ~3.5 em short, which read as the info row needing +12 where the board was happy at +8), while the TITLE pays `HEADER_TITLE_INSET_EM` (2.0 em): it starts right of the album-art icon at the larger title scale, and budgeting it at the raw column overflowed the field and WRAPPED the spill onto a second line. The title budget additionally subtracts `AaKaraokeRows.wordGuardEm` - the widest proportional-run (Latin) word in the title, capped at 2.5 em, and ZERO for pure wide-char titles: the host WORD-wraps, so a string the model thinks fits can still tip its last Latin word onto line two when the real title font renders that run even slightly wide (live: a CJK+`~Raise Y/Our Hands` title wrapped at +2 where kana-only titles landed exactly on the edge, while pure-CJK titles never wrap because every wide glyph is model-exactly 1.0 em). The guard makes the ellipsis land before the wrap point - up to a word short for Latin-heavy titles, exactly on the edge for CJK - which is the bounded, one-line-guaranteed alternative to the host stealing a board row; text plus marker wider than the column stays unpadded rather than wrapping, and `AaKaraokeRows.markerFor` compacts the ▶ marker when the padded string would otherwise exceed the column. Each row's lyric and translation share one `\n`-joined two-line title — the host scales title and subtitle glyph sizes independently per row, so padding across those two fields can never hold an edge, while one string always renders at one font scale and the bottom row's equal-column padding lands both tails on the same edge; the active pair's translation line carries the identical bare-`▶` marker string and both lines subtract the same `AaKaraokeRows.markerEm` allowance (▶ charged at 1.0 em, above its real advance), cancelling the glyph's unknown width out of both tails). Row-to-row text alignment is pixel-tuned on live hosts rather than derived from em maths: browse fields strip leading whitespace-atoms at line start unless guarded by U+200B (not Java-whitespace, halts the trim, renders ~7 px); behind the guard titles render `\u3000`/ASCII/NBSP while subtitles strip NBSP, and subtitle fields sit at a native indent left of the title's. Live-tuned constants (DHU): the marker is a bare big `▶` (~45 px lead), idle rows lead with `\u200B`+one `\u3000` (never NBSP runs: off-lattice, they drifted against the marker while the U+3000-padded bottom row stayed pixel-perfect), and lyrics/sync subtitles indent with `\u200B\u3000 ` — all text starts converge within a few px. Retune those constants from head-unit screenshots, never from font models. EVERY browse row is COLUMN-FIT (`rowFit`): each lyric/translation/subtitle string entering a Lyrics/Sync row or a Karaoke board line truncates at the column minus the lead marker advance and the word guard (the header title's rule, now applied to rows too), so no row can ever present an over-column field for the host to word-wrap. THE OPEN FLIP (decided 2026-10-26 to keep as-is pending a design choice): with the header clock ticking, the Lyrics and Karaoke tabs still intermittently flip short rows between one and two lines on some songs - proven from the phone-side probe to be pure host re-bind (server pushes 1 Hz for the clock alone, Gearhead re-fetches byte-identical lists, both board rows flip while the header never does; a tab switch flips the host into a passive relayout mode where the same 1 Hz push+fetch renders silently, which is why switching back and forth "fixes" it). The exhaustive fix set is A) move the live playback time out of the browse header (static `artist - duration`, no per-second push, jumps die at the root) or B) restore constant two-line rows everywhere (camouflage). Loading, not-found and error states delegate to the Lyrics tab’s builder so the Karaoke tab mirrors them exactly. PLAIN_ONLY gets NO board (decided 2026-10-30): the position/duration estimate that used to drive the pair faked synced behaviour the payload never promised - the tab now shows the track header plus the `No synced lyrics found` message row, and the phone drops its equivalent fake (the plain crawl animator is deleted; the phone sheet is static and scroll-from-top). The card falls back to the not-found identity layout with the same subtitle (15 s temporal like other failures), and the Sync tab prints the same message row WITHOUT the offset/± rows (nothing to time against). A sung line is never swapped out: markers are INSERTED between real lyric lines, never substituted for them - the windows page over the sequence at fixed size, and the Karaoke board alternates markers into its two-row parity like a real lyric line, shifting the row side of every later line. The current row is simply the last row whose span has begun (`SungLine.currentVIndex`): a real line's span starts at its time, a marker's span starts at the end it derives from (the previous line's derived end, or 0:00 for the intro), so the previous lyric stays lit until the marker's span opens. Cache repairs protect the derivation: coarse-chunk lines get their final token start re-spread near the real sung end, so their +5 s end lands late or past the next line and nothing is guessed. `rowRender` is the single (original, translation) decision per sequence row: marker rows put IDENTICAL text in both fields (the translation field exists only when the song has translations), and only real-lyric rows consult the translation array - a lyric line and its translation line can never disagree about digits vs triples. Marker rows are never marker-pinned.The now-playing card shares `SungLine` with the browse tabs but rides markers with its own TWO-PHASE rule (user-settled): while a marker row is current the card holds the TRACK IDENTITY layout until `SungLine.CARD_LEAD_MS` (4 s) before the next line - a finished line is dropped IMMEDIATELY even mid-interlude (option A, same as the intro) - then it counts 4→1 with the coming lyric as subtitle; a short intro without a marker simply rides identity. The card's subtitle preview NEVER SKIPS A ROW: a marker is a row like any lyric, so when an instrumental comes next the subtitle previews the MARKER - its live glyph row, counting toward its own successor, exactly as the browse tabs preview it (`SungLine.previewSuccessor` returns the next row whatever it is); only past the last row is the preview blank. The outro hands off to track identity once the last line ENDS (`SungLine.pastLastLineEnd`: last token start + 5 s for token-timed payloads, else line start + the syllable-proportional hold): intro and outro show a normal player layout — DISPLAY_TITLE is the bare song title with the artist on the subtitle line (never the joined "title — artist"; a blank artist tag renders "Unknown Artist", matching the phone UI); while the engine works, "Loading translation…" TEMPORARILY REPLACES the artist line there too (the identity subtitle passes through the same loading override as the lyric card - `getCardText` applies it for any FOUND status). MANUAL CARD SPLIT IS OFF BY DEFAULT (`MANUAL_CARD_SPLIT = false` in the service): the head unit wraps long now-playing text onto a second line on its own - a live shot of a long track title wrapped cleanly at the field edge - while the width-model chunking could cut a lyric into a main line plus a ONE-CHARACTER tail. With the flag OFF every card field (lyric title, translated subtitle, countdown-phase preview) gets the whole line and the host's wrap lays it out; flipping the static flag ON restores the full chunk machinery (title chunks, translated-chunk selection, first-chunk preview, hold anti-flicker). Padding math runs on `AaKaraokeRows.advanceEm`, a Roboto advance-width model (CJK exactly 1 em, proportional Latin widths; the coarse one-unit-per-character estimate left multi-em gaps after English-heavy lines and wrapped others); each side flips to `line + 2` when its own line finishes and rows seek on click like Lyrics rows. Sync is a fixed 3-row timing preview with the line being sung pinned first, rows top-to-bottom: `Current offset: +0.2s` (the PER-LYRICS value in SECONDS, no subtitle), directly below it the two playable control rows stacked `◀◀ Delay lyrics: −0.2s` then `▶▶ Advance lyrics: +0.2s` (`SYNC_STEP_MS` 200 ms shown in seconds), then the 3-row preview (the old sandwich layout with Advance under the preview is retired) - the ± tap jumps to the host's playback view and that is UNAVOIDABLE (Google's media interaction model: any playable selection opens the playback view; the card there already rides the new offset). A stay-in-place alternative was built and REVERTED the same day: ± as BROWSABLE folders adjusting in onLoadChildren drift on their own, because Gearhead loads listed folders' children without any tap (prefetch = the write fires unprompted); browse rows are either actionable-and-jump or stay-and-do-nothing; the global AA offset has no row on the car at all (phone-only editor, like the edge knob), and the refresh gate watches `state.offsetMs` so phone-side edits reach the head unit even while paused. Message states mirror on every tab: Karaoke shows the Lyrics tab's rows (its only row on not-found), and Sync prints the same `Play a song to see lyrics` / `Loading lyrics…` / `No lyrics found for this track` / `Error loading lyrics` message row (ticking states with the stable `\u2800` second line) in those states — with nothing loaded there is no offset row on Sync. The track header deliberately carries no source/target language info — the More tab owns that; while the active translation engine is working, the now-playing subtitle shows "Loading translation…" (mirroring "Loading lyrics…") instead of the next-line preview; the last lyric line's preview slot stays BLANK (no lone `♪`; every lyrics path filters blank lines at parse, so a blank FOLLOW-up line cannot reach the subtitle ladder at all). Failure subtitles are temporal: `No lyrics found` / `No synced lyrics found` / `Error loading lyrics` hold the card subtitle for 15 s (`FAILURE_SUBTITLE_MS`, clock stamped on status/track change), then the card relaxes to the normal player layout (title + artist) while the browse tabs keep reporting the failure. More shows provider and metadata details.

Android Auto projection activates `LyricsDemandController` for the entire projection session. Provider resolution therefore remains available even if the user navigates from Auto Lyrics to another Android Auto app; it stops only after projection disconnects unless the phone UI is still active.

Future-word look-ahead has been removed. A `notifyChildrenChanged` rebuilds the section's ENTIRE row list, so the header's per-second playback-time tick runs whenever a track is loaded (any status - it also ticks through Loading/Not-found) and is only safe while the rebuilt rows are byte-identical and height-stable (a ONE-TIME accepted height flip at translation publish excepted): translations are PUBLISHED AT RUN END: `visibleTranslations` hides the whole list while the engine runs, because streaming a partial list in mid-run shows blank second lines (the chunk hasn't arrived) and flips row heights line-by-line - the host animates each flip as a jumping row, and users read the blanks as broken. Mid-run the Lyrics/Sync rows render single-line (translation rides the subtitle field) and the Karaoke board is its compact single-line mode; when the run completes the translation lines engage ONCE (rows whose real translation is missing render the `\u2800` invisibly filled second line - whitespace fillers are trimmed by the host and collapse) - with the compact board that is a ONE-TIME one-to-two-line height flip per run, accepted with the single-line model. Engine completion carries no state emission, so the refresh gate watches the visibility decision itself (`translationsVisibleChanged`). The every-second host refresh must only repaint, never shift rows (a height-changing repaint reads as the bottom row 'jumping' between one-line and two-line) - steady-state rows are byte-identical between pushes and no row pads past the calibrated column. The browse tree remains throttled (`NOTIFY_THROTTLE_MS = 500 ms`), so AA visual transitions can be coarser or slightly late compared with phone/Performance; they are no longer intentionally advanced. The now-playing subtitle is checked every 200 ms.

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
7. Sync stays at 3 rows; its ±100 ms rows move the PER-LYRICS offset (`Offset: ±Nms · this lyrics`), and the value follows the exact lyrics — switch tracks or apply different Manual Search text and it resets to 0, replay the same lyrics and it comes back. The global AA offset (phone settings **Global AA Sync** ±100 ms) is never shown on the car.
8. More displays provider/details without changing timing.
9. Plain lyrics still advance.
10. Karaoke source text retains correct spacing for English, Japanese, and mixed scripts.
11. No future timing token is highlighted solely to hide browse latency.
12. Karaoke ON can select an equivalent word-timed provider while Karaoke OFF independently retains the standard winner.
13. Switching translation engine between on-device and remote re-translates the current track without restarting the app, and replaying a cached track shows the translated lines immediately.
14. Manual Lyrics Search (main-screen button) with edited terms lists Karaoke/Synced/Plain results (durations where the provider gives them; every row body-backed and clickable), and the picked result replaces the lyrics, drops the previous translation immediately, survives reload from cache, and translates like any found result.
15. Karaoke word highlighting advances through files whose word timestamps omit fractional seconds, and CJK lyrics render without injected inter-character spaces (including per-character SyncLRC token streams).
16. The fullscreen performance view shows translated sublines under each lyric line, colored with the same active/inactive focus.
17. Manual search lists SyncLRC and Musixmatch rows (provider-labelled); rows are body-backed at search time (tap-open diagnostics `musixmatch: k/n usable`), so every listed row applies on the first click with kind final at list time.

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
| `app/src/main/java/com/autolyrics/lyrics/SyncLrcClient.kt` | SyncLRC public API integration (aggregator; every payload tier, karaoke preferred). |
| `app/src/main/java/com/autolyrics/lyrics/LrcParser.kt` | Standard and Enhanced-LRC parsing. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Metadata/quality/source scoring and mode-aware selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | STANDARD/KARAOKE persistent cache. |
| `app/src/main/java/com/autolyrics/lyrics/TranslationCache.kt` | Persistent translation cache keyed by track+target+engine+model+source-text hash. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsTranslator.kt` | On-device ML Kit engine: language detection, pack downloads, per-line translation. |
| `app/src/main/java/com/autolyrics/lyrics/LlmTranslator.kt` | Remote OpenAI-compatible engine: chunking, lenient parsing, repair retries, `/models` listing. |
| `app/src/main/java/com/autolyrics/lyrics/ManualSearch.kt` | Manual-search hit model, response conversion, duration-aware ranking. |
| `app/src/main/java/com/autolyrics/ManualSearchDialog.kt` | Main-screen button dialog for manual lyrics lookup and override. |
| `app/src/main/java/com/autolyrics/MediaAppsDialog.kt` | Media-source picker (Media source section): whitelist of apps whose media notifications are followed. |
| `app/src/main/java/com/autolyrics/lyrics/KaraokeTiming.kt` | Shared raw-token active-index selection. |
| `app/src/main/java/com/autolyrics/util/LyricWordLayout.kt` | Exact source layout and timing-token → display-range grouping. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser, MediaSession, Sync controls, Karaoke rendering. |
| `.github/workflows/build.yml` | CI and release build. |
| `RELEASE.md` | Release checklist, version/tag invariants, and release triggers. |
