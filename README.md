# Auto Lyrics

Auto Lyrics is an Android app that follows the active media session and displays lyrics on both the phone and Android Auto. It resolves lyrics from **LRCLIB**, **Musixmatch**, optional **PetitLyrics**, and **SyncLRC** — all providers race on every track change regardless of Karaoke mode, SyncLRC contributing Enhanced-LRC word timing.

## Features

- **Android Auto lyrics** — current line marked with `▶` and pinned to the first row, plus a Karaoke tab with a two-line KTV-style board (left/right justified, each side advancing when its line finishes) — word-timed and synced lyrics ride their timing, not-synced lyrics ride a position estimate. Translations publish when the run finishes: no blank translation lines mid-load.
- **Lyrics / Sync / More browse tabs** — lyrics are the primary view, timing adjustment is kept separate, and detailed track/provider information lives under More.
- **Fixed 5-row lyric window** — the Lyrics tab reads forward from the pinned current line with a translation line stacked under each lyric line; near the song end it simply shows fewer rows.
- **Per-lyrics sync** — the fine-offset presets and **Tap to Sync** under the lyrics tune the LOADED LYRICS (stored against the exact timing set, not the song or the provider): replay the same lyrics and the value rides along, get different timing and it starts fresh at zero.
- **Fixed 3-row Sync preview** — current line plus surrounding context while adjusting the per-lyrics offset on the head unit.
- **Now-playing lyric line** — the Android Auto now-playing card carries the current lyric line and falls back to a plain title/artist player card during intro, instrumentals, outro, and lyric failures — with a 4→1 music-glyph countdown into each coming lyric during the final seconds of any gap.
- **Multi-provider resolution** — LRCLIB, Musixmatch, configured PetitLyrics, and SyncLRC candidates are compared using metadata match, lyric quality, and source confidence.
- **Karaoke-aware selection** — normal mode remains accuracy-first; Karaoke mode may prefer a real WORD_SYNC candidate when its recording match and payload quality are near-equivalent to the normal winner.
- **Musixmatch RichSync** — when available, Musixmatch word-level timing is exposed as karaoke/word sync; line-synchronized subtitles are used as fallback.
- **SyncLRC Enhanced LRC** — queried on every track change for additional fine-grained timing sourced through its LRCLIB/LDDC/syncedlyrics stack (NetEase, QQ, Kugou included); token-bearing karaoke wins over the aggregator's synced and plain tiers.
- **Timing-token / display-word separation** — providers may timestamp a whole word, syllable, fragment, or individual character. Raw timestamps are retained, while the renderer groups fine tokens into human-readable display words/phrases.
- **Script-aware rendering** — English, Japanese, and mixed-script lines preserve the provider's original text and spacing without synthetic spaces.
- **Synced and plain fallback** — synchronized lyrics are preferred; LRCLIB plain text remains a last-resort fallback, shown honestly as a static lyric sheet (no fake current-line marker or auto-scroll) with the head unit reporting `No synced lyrics found` on the Karaoke and Sync tabs.
- **Robust metadata matching** — handles recording/version qualifiers, romanized-vs-native-script artist names, and multi-contributor metadata such as `Alan Menken, Howard Ashman, Samuel E. Wright, Disney`.
- **Works with normal media-session players** — Spotify, YouTube Music, Apple Music, Poweramp, and other apps that expose usable Android media metadata.
- **Per-app media source** — the Media source section (first in settings) restricts lyrics to chosen apps' media notifications; applies to the phone and Android Auto alike. Default follows every media player.
- **Demand-aware provider work** — active media sessions are monitored continuously, but lyric providers are queried only while the phone UI is in use or Android Auto projection is connected.
- **Phone companion UI** — lyrics remain available on the phone, including the performance/karaoke view with translated sublines.
- **Local cache** — normal and Karaoke selections use separate cache variants; word timestamps are preserved (no provider ships word-end times; sung ends are derived at display time). Entries are permanent; applying a Manual Search result is the only refresh path.
- **Simplified / Traditional Chinese** — the target list separates 简体中文 from 繁體中文 (every picker entry carries its native name when it differs), and already-Chinese lyrics written in the other form are converted **on-device** with OpenCC's phrase+character dictionaries — no network, no re-translation; Japanese lyrics are never touched.
- **Dual translation engines** — on-device ML Kit, or any OpenAI-compatible remote LLM endpoint (model dropdown fed by the endpoint's `/models` route; API key optional).
- **Persistent translation cache** — translated lines are stored per track, target language, and lyric-text hash, so replays skip detection and network entirely; applying a Manual Search result or tapping **Redo** on the translation status line forces retranslation.
- **Manual lyrics search** — main-screen button opens a dialog with an editable song title (required) and artist (optional); pick any result (Karaoke / Synced / Plain, with its duration vs the playing track) to override automatic resolution; an **Include unsynced results** checkbox (off by default) reveals the no-timing rows. The status line shows only the outcome — `N results · track 4:31`, `0 results…`, or `Error…` when a provider leg actually died — and tapping it opens the full per-provider diagnostics.

## Architecture

```text
Phone Activity lifecycle ───────┐
Android Auto CarConnection ─────┤
                                ▼
                      LyricsDemandController
                                │ active demand
                                ▼
MediaListenerService (NotificationListenerService)
    │ selects active media sessions continuously
    │ forwards the selected session only while demand is active
    ▼
MediaTracker (singleton / StateFlow)
    │
    ├── track + playback state
    ├── LRCLIB ────────────────┐
    ├── Musixmatch ────────────┤ parallel
    ├── PetitLyrics ───────────┤ when configured
    ├── SyncLRC ───────────────┤ exact → iTunes-resolved ladder
    ├── LyricsProviderResolver ◀┘
    ├── LyricsCache / TranslationCache
    └── LyricsTranslator · LlmTranslator / album-art helpers
            │
            ├──────────────────────────┐
            ▼                          ▼
      Phone UI                 LyricsBrowserService
                               (Android Auto MediaBrowser + MediaSession)
```

Provider demand is active while at least one Auto Lyrics phone Activity is started **or** while Android Auto projection is connected. Android Auto keeps lyric resolution active for the full projection session even when another AA app is in the foreground. When neither condition is true, `MediaListenerService` may keep selecting the current media session, but it does not forward session changes to `MediaTracker`, so new provider lookups do not start.

The provider resolver validates plausible candidates with one common scoring model. Synchronized candidates outrank plain lyrics. Timing granularity alone does not override a materially stronger recording match, but Karaoke mode can choose a near-equivalent candidate that contains real word/timing-token data.

See [DEVELOPMENT.md](DEVELOPMENT.md) for scoring, cache policy, provider formats, and implementation details.

## Requirements

- Android 8.0 / API 26 or newer
- Android Studio with Android SDK 34
- JDK 17
- Notification access for Auto Lyrics so it can inspect active media sessions
- Android Auto developer mode / unknown sources when using a sideloaded build

## Install a Release

Prebuilt APKs are published under [GitHub Releases](https://github.com/whoxamxl/auto-lyrics/releases/latest).

Because Auto Lyrics is sideloaded, Android Auto normally needs developer mode enabled before the app is shown:

1. Open Android Auto settings on the phone.
2. Enable Android Auto developer mode.
3. Open **Developer settings**.
4. Enable **Unknown sources**.
5. Reconnect Android Auto if Auto Lyrics does not appear immediately.
6. Open Auto Lyrics on the phone and grant notification access.

## Build from Source

### Basic build

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### PetitLyrics configuration

LRCLIB, Musixmatch, and SyncLRC do not require local credentials. PetitLyrics is optional in local builds and requires your own registered client values.

Copy the template:

```powershell
Copy-Item .env.example .env
```

Then fill in:

```dotenv
PETITLYRICS_USER_ID=
PETITLYRICS_APP_NAME=
PETITLYRICS_PKG_NAME=
PETITLYRICS_CLIENT_APP_ID=
```

`.env` is ignored by Git. Do not commit private client values. The values are compiled into `BuildConfig`, so they should also **not be treated as cryptographically secret once an APK is distributed**.

Configuration precedence is:

```text
process environment
    ↓
.env
    ↓ only when .env does not exist
.env.example
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for provider details and [RELEASE.md](RELEASE.md) for the release workflow.

## Android Auto UI

### Lyrics

The primary browse tab. It shows the track header plus a moving lyric window:

```text
   previous line
   previous line
▶  current line
   next line
   next line
```

The current line is pinned to the first row (near the song end the list simply shows fewer rows), every row's translated line starts at the same x as its lyric line, and instrumental stretches — intro included — render as a **countdown row**: one `♪` glyph per remaining second until the next lyric (max four). Instrumentals are derived from the timing gap, not from tags: non-lyric lines (`♪` runs, `[Music]`/`[Verse]`-style tags, section headers, timed production credits like `词：林夕`/`Lyrics by …`) are stripped when lyrics are downloaded, so they never render anywhere.

### Karaoke

A Chinese-KTV-style two-row board for line-timed lyrics, under the same song-info header as the Lyrics tab: the line being sung (marked with a triangle) sits on one row while its partner previews on the other — top rows left-justified, bottom rows right-justified (emulated with width-modelled padding auto-sized from the head unit's own icon-size hint, fine-tunable per car from the phone's Karaoke edge setting). Each side advances two lines when its own line finishes, translations ride directly under their lyric line, instrumental stretches (intro and mid-song) tick the per-second countdown — the countdown row owns its board slot like any lyric, so with an intro countdown the first line is sung from the bottom row — and rows seek on click. Not-synced lyrics ride the same board on a position estimate. Without a translation each row is one compact line; rows with translations are always two lines (lyric + translation).

### Sync

The secondary tab for timing correction on the head unit. Top to bottom: `Current offset: ±0.2s` (the per-lyrics value — the same one the phone's main-view bar edits), the control rows `◀◀ Delay lyrics: −0.2s` and `▶▶ Advance lyrics: +0.2s`, then a forward-reading 3-row lyric preview (line being sung first; countdown glyphs during intros/instrumentals). The global **Global AA Sync** delay is edited only from the phone's Android Auto settings section and is never shown here. The tab always carries a row: `Play a song to see lyrics` before anything plays, `Loading lyrics…`, `No lyrics found for this track`, or `Error loading lyrics` in those states.

### More

Shows detailed information such as title, artist, album, provider, synchronization state, detected language, and duration.

## How Lyrics Are Resolved

1. `MediaListenerService` continuously selects the best active media session, but forwards it for lyric work only while the phone UI is active or Android Auto projection is connected.
2. The selected media session provides title, artist, album, duration, playback state, and position.
3. Metadata is cleaned before lookup.
4. LRCLIB, Musixmatch, and SyncLRC launch in parallel on every track change; configured PetitLyrics joins them. Karaoke mode no longer gates who is queried — it only changes how the winner is selected and which cache variant stores it.
5. Provider-specific lookup logic gathers plausible candidates. Musixmatch locally validates the matcher result from its mobile macro response. SyncLRC contributes every tier its aggregate returns (token-timed karaoke first, then synced, then plain).
6. Every payload is sanitized at parse: blank lines, `♪`/`🎵`/`[Music]` lines, section headers (`[Verse]`, `[Chorus]`, `【副歌】`, …), and production-credit rows (`词：林夕`, `曲：Paul Gray`, `Lyrics by …`) are stripped before anything is stored or displayed, and instrumentals are derived from the remaining timing.
8. `LyricsProviderResolver` discards candidates whose payload has no real lyric line at all (all blank/music-symbol/credit lines never win — the next provider with actual text takes over, and an all-junk field reads as not-found), then validates metadata and compares candidate quality/source confidence. If the race dies on transport errors (provider overload), automatic retries fire after 3 s — up to two, and each retry re-races only the providers that actually failed, never ones that cleanly said "no". A "not found" outcome latches only for the current playback: starting the track again from 0:00 re-tries the full search — seeking, pausing, or tracks that already have lyrics never do. Genuine "no lyrics anywhere" answers are never retried.
9. In normal mode, the highest-quality synchronized recording match wins. In Karaoke mode, a real WORD_SYNC candidate can replace that winner only when metadata and payload quality remain near-equivalent.
10. The selected result is written to a mode-specific cache variant and kept permanently; the provider race never re-runs for that track. Applying a Manual Search result is the only way to replace a cached entry (it also forces retranslation).

This design is intentionally not “first provider to respond wins”; normal request latency is not part of the score. A provider only loses due to speed when it exceeds its timeout budget and therefore fails to produce a candidate for that comparison.

### WORD_SYNC means timed tokens, not guaranteed linguistic words

Providers do not all expose the same granularity. A timed element may be a linguistic word, a syllable, a fragment, or — especially with Japanese karaoke data — one character.

Auto Lyrics therefore keeps the source timing tokens intact and separates them from **display grouping**. For example:

```text
Provider timing tokens: Pro / vi / der
Display highlight:       Provider

Japanese timing tokens: 君 / を / 忘 / れ / な / い
Display highlight:      君を / 忘れない   (word-like grouping)
```

This preserves precise timing while avoiding character-by-character or syllable-by-syllable visual jitter when the source is finer than the desired UI.

### Why WORD_SYNC does not always beat LINE_SYNC

`WORD_SYNC` describes timing granularity, not whether the provider matched the correct recording or whether the payload itself is reliable. A wrong live/remaster/edit with rich timing should not beat the correct recording merely because it has more timestamps.

Normal mode therefore remains accuracy-first. Karaoke mode adds a constrained preference: a WORD_SYNC candidate must contain actual timing tokens and stay within the resolver's metadata/payload-quality tolerance of the normal winner.

## Musixmatch provider

Musixmatch uses the anonymous mobile API path rather than the desktop endpoint. Runtime testing found the desktop endpoint could return unrelated fixed tracks even with HTTP/API success, so Auto Lyrics does not rely on that path.

Current flow:

```text
token.get
    ↓
macro.subtitles.get
    ├── matcher.track.get      → matched metadata
    ├── track.richsync.get     → word/timing-token data when available
    └── track.subtitles.get    → line-synced LRC fallback
```

Important behavior:

- the anonymous token is short-lived and cached briefly,
- one token refresh/retry is allowed after an authentication rejection,
- the macro's explicit `matcher.track.get` result is locally validated before lyrics are accepted,
- RichSync is preferred when usable,
- RichSync chunks retain their timestamps even when the source line has no spaces,
- rendering aligns timing chunks to the original source text and groups fine fragments for display,
- line-synchronized subtitles remain a synchronized fallback,
- provider/network failure is isolated from the other providers.

Musixmatch integration uses an unofficial/internal API surface and may change independently of Auto Lyrics.

## SyncLRC provider

Every auto search queries the public [SyncLRC](https://github.com/TharukRenuja/SyncLRC) API:

```text
GET https://api.synclrc.dev/lyrics
    ?track=...
    &artist=...
    [&album=...]
    [&duration=...]
```

The current public API returns canonical metadata plus separate `karaoke`, `synced`, and `plain` fields; all three tiers are accepted, with a token-bearing `karaoke` payload winning over synced and plain. For compatibility with older SyncLRC deployments, the client also routes the former type-specific `lyrics` response shape by its `type` field.

`/lyrics` aggregates from upstream sources **live**, so it can return `503 SOURCES_UNAVAILABLE` while the sources are down. When the exact-name request fails or matches nothing, Auto Lyrics goes straight to the iTunes-resolved catalog lookup — the same mechanism the synclrc.dev website's search box uses: the iTunes Search API maps alias/romanized spellings (コネクト → Connect), and each candidate name pair is fetched as an exact `/lyrics` request, best pick winning (exact title, artist containment, closest duration). A session with no artist still runs the ladder title-only (the exact call itself requires both params — no artist means ladder-only, not a 400). Manual Search runs BOTH stages — the exact `/lyrics` call with the edited terms first, then the same catalog lookup keeping every iTunes candidate (up to the whole page of 6) as a pick-list row instead of a single winner. The DB-backed `GET /search` is no longer called from either path — its literal matching never resolved aliases (a kana title returns zero rows while the romanized name is indexed) and its exact-name row would only repeat the cached auto payload.

SyncLRC documents Karaoke as Enhanced LRC and uses LRCLIB plus LDDC/syncedlyrics-backed sources such as NetEase, QQ Music, Kugou, and Musixmatch. Auto Lyrics does **not** embed or copy that upstream code; it consumes the public JSON API.

A SyncLRC candidate is created only when:

- a usable payload is present: Karaoke (Enhanced LRC with timed tokens) wins when available, otherwise the response's synced lines become a LINE_SYNC candidate and plain text a last-resort PLAIN candidate,
- the returned metadata passes the normal resolver checks.

Accepting every tier matters because the aggregator sources NetEase, QQ, Kugou, and LDDC — for many CJK tracks its synced lines are the only synced lyrics available anywhere the app can reach. Karaoke still outranks the lower tiers in scoring. It is an opportunistic third-party service; availability, upstream sources, rate behavior, and API behavior may change independently of Auto Lyrics.

## Testing with Android Auto Desktop Head Unit (DHU)

1. Install **Android Auto Desktop Head Unit emulator** from Android Studio → SDK Manager → SDK Tools.
2. Enable Android Auto developer mode on the phone.
3. Enable **Unknown sources** in Android Auto developer settings.
4. Install the Auto Lyrics debug APK on the phone.
5. Connect the phone to DHU using the Android Auto/DHU setup supported by your SDK version.
6. Open **Lyrics** from the Auto Lyrics browse UI and verify line updates, Sync controls, and More metadata.

Useful provider logs during development:

```powershell
adb logcat -s Musixmatch:D SyncLRC:D ProviderResolver:D PetitLyrics:D
```

## Known Constraints

- Lyrics availability depends on upstream providers; not every recording has usable synchronized or Enhanced-LRC lyrics.
- Musixmatch and PetitLyrics integrations use unofficial/internal API surfaces; SyncLRC is a third-party public aggregation service. Any of them can change independently of Auto Lyrics.
- Media-session metadata quality varies by player. Auto Lyrics includes heuristics for common metadata problems, but unusual formats can still miss or mismatch.
- Audio-output latency can differ between the phone speaker, Bluetooth, codecs, head units, and Android Auto. Use the per-lyrics offset (phone bar / AA Sync tab) for per-timing-set correction and the global **Global AA Sync** delay for a stable head-unit output path; phone-speaker and Bluetooth delays ride the per-lyrics value. Auto Lyrics does **not** apply a fixed provider-wide pre-offset without evidence that a timing source itself is systematically biased across playback paths.
- Android Auto browse-tree updates remain throttled, so visual Karaoke updates can be coarser than the phone/Performance UI even though future words are no longer intentionally highlighted early.
- Fine source timing is display-grouped heuristically. Japanese segmentation in particular is intended to be more readable than character-by-character highlighting, not to be a full morphological analysis engine.
- PetitLyrics client identifiers embedded in a distributed Android APK are extractable by design; do not use values that rely on client-side secrecy.
- Sideloaded Android Auto media apps generally require Android Auto developer settings to be enabled.

## Developer Documentation

- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, provider resolver, cache behavior, provider formats, and Android Auto implementation notes.
- [RELEASE.md](RELEASE.md) — versioning, release secrets, tagging, and post-release verification.

## License

This repository currently does not contain a `LICENSE` file, and the upstream repository does not declare a GitHub-detected license. Do not infer redistribution terms from this README; licensing should be clarified explicitly before redistribution or publication beyond the repository's existing use.
