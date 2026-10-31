# AGENTS.md

Working notes for AI coding agents in this repository. Human docs: `README.md` (features/build) and `DEVELOPMENT.md` (authoritative architecture reference — providers, cache policy, scoring, Android Auto; read the relevant section before touching those areas).

## Project

Android app (`com.autolyrics`) that fetches synced/karaoke lyrics for whatever is playing (MediaSession listeners like Spotify) and displays them in a phone UI and Android Auto. Kotlin, single `:app` module, no Compose — programmatic views installed by `AutoLyricsApp` activity callbacks.

## Build environment (hard constraints)

- JDK 21 installed → Gradle wrapper must stay >= 8.5 (currently 8.7).
- Release APKs are arm64-v8a only (`ndk.abiFilters` in `app/build.gradle.kts`; ML Kit `libtranslate_jni.so` is ~16 MB per ABI, so filtering cut 63 MB of native libs to 16). Debug builds keep all ABIs for emulators.
- Release builds run R8 with `proguard-android-optimize.txt` + no AGP default consumer rules; unit tests run unminified, so they cannot catch Gson/R8 reflection breakage (see DEVELOPMENT.md TypeToken incident) — smoke-test release network paths on-device.
- Android SDK at `$HOME/android-sdk`; `local.properties` (`sdk.dir=...`) is gitignored and must be recreated in fresh clones.
- Gates before declaring work done: `./gradlew assembleDebug testDebugUnitTest` (plain JUnit 4 tests under `app/src/test`, no Robolectric; keep new engine logic JVM-testable via `internal` pure functions).

## Architecture quick map

- `media/MediaTracker` — singleton state owner: track changes, provider fetch, position, and translation trigger (`translateIfNeeded`), cancellation via per-purpose jobs.
- `lyrics/` providers (`LrcLibClient`, `MusixmatchClient`, `PetitLyricsClient`, `SyncLrcClient`) ranked by `LyricsProviderResolver` (candidates whose payload is all blank/symbol lines are dropped before scoring — `hasDisplayableContent`, keyed on `LyricDisplay.isSymbolText`; the same `hasDisplayableLines` gate filters Manual Search rows and re-checks lazy bodies at apply); results cached by `LyricsCache` (STANDARD/KARAOKE variants, permanent — a hit is final; Manual Search apply is the only refresh path and also force-retranslates). `ManualSearchDialog` (main-screen button) + `ManualSearch` implement the user-override search dialog across LRCLIB/SyncLRC/Musixmatch (+ PetitLyrics when configured) — parallel fault-isolated legs, each under its own 10 s timeout, per-leg diagnostics shown in the dialog status line; overrides apply through `MediaTracker.applyManualLyrics`.
- `util/SungLine` — the single sung-line / virtual-row source for ALL display surfaces (every AA browse builder, the browse refresh gate, and the AA now-playing card): `virtualRows` is the display sequence (real lyric rows + countdown rows; provider symbol lines NEVER render — absorbed runs live under their countdown, sub-threshold tags are skipped leaving the previous line lit), `currentVIndex` picks the current row, and the row-refresh gate tracks every field builders see (start/end/currentIdx/gap/countdown/headerSec) or pushes get swallowed. Regression harness: `AaSequenceSimTest` sweeps playback through real provider payloads.
- Translation: two engines behind `lyrics/TranslationEngineUi` — `LyricsTranslator` (on-device ML Kit; language-pack download state machine) and `LlmTranslator` (OpenAI-compatible `/chat/completions`; JSON-array chunks, line-count validation, repair retries). Pref `translation_engine` routes; `TranslationCache` (filesDir/translation_cache, key = track+target+sha256(source text) — engine/model stored but not keyed; no expiry) is consulted first. Settings row `TranslationEngineView`, progress UI `TranslationStatusView` observes the active engine's `StateFlow`.
- `auto/LyricsBrowserService` — Android Auto browse tree (Lyrics · Karaoke · Sync · More; exactly 4 root tabs, the host caps at 4). Row text alignment and karaoke right-justification ride PIXEL-TUNED constants (`CURRENT_LINE_PREFIX`, `IDLE_LINE_PREFIX`, `TRANSLATION_SUBTITLE_PREFIX` in the service; `AaKaraokeRows` advance/column model; per-car column nudge persisted per head unit (`aa_karaoke_edge:<fingerprint>` int-delta prefs seeded from the global `aa_karaoke_edge`; identity = SHA-256 of the root-hints bundle in `auto/AaHost`), adjusted from the phone settings SYNC section, which only shows the row while a head unit is connected) — Gearhead strips/garbles leading whitespace in ways no font model predicts; retune only from head-unit (DHU) screenshots, see DEVELOPMENT.md "Host field rules".
- Providers must never block playback: all network work runs off main; `Fetch` jobs are cancelled per-track.

## Conventions

- Objects/singletons for engines and clients; views are custom `LinearLayout`s installed from `AutoLyricsApp.onActivityResumed` with idempotent `install()`.
- SharedPreferences file name `auto_lyrics_prefs`; keys are `const val` on the owning class (`TranslationLanguages`, `LlmTranslator`, etc.).
- Failure UX: transient failures latch a manual-retry gate (see `prepareManualRetry`); never auto-retry in a loop, never log API keys.
- Remote LLM endpoints have no TLS requirement (server never runs on the phone; `usesCleartextTraffic` is enabled). Bare host input is normalized to `https://host/v1` by `LlmTranslator.normalizeBaseUrl`; the API key is optional. Model lists come from the endpoint's `/models` route with a manual-entry fallback. User-facing option lists stay intentionally small (see `TranslationLanguages.primaryTargets`).
