# AGENTS.md

Working notes for AI coding agents in this repository. Human docs: `README.md` (features/build) and `DEVELOPMENT.md` (authoritative architecture reference — providers, cache policy, scoring, Android Auto; read the relevant section before touching those areas).

## Project

Android app (`com.autolyrics`) that fetches synced/karaoke lyrics for whatever is playing (MediaSession listeners like Spotify) and displays them in a phone UI and Android Auto. Kotlin, single `:app` module, no Compose — programmatic views installed by `AutoLyricsApp` activity callbacks.

## Build environment (hard constraints)

- JDK 21 installed → Gradle wrapper must stay >= 8.5 (currently 8.7).
- Android SDK at `$HOME/android-sdk`; `local.properties` (`sdk.dir=...`) is gitignored and must be recreated in fresh clones.
- Gates before declaring work done: `./gradlew assembleDebug testDebugUnitTest` (plain JUnit 4 tests under `app/src/test`, no Robolectric; keep new engine logic JVM-testable via `internal` pure functions).

## Architecture quick map

- `media/MediaTracker` — singleton state owner: track changes, provider fetch, position, and translation trigger (`translateIfNeeded`), cancellation via per-purpose jobs.
- `lyrics/` providers (`LrcLibClient`, `MusixmatchClient`, `PetitLyricsClient`, `SyncLrcClient`) ranked by `LyricsProviderResolver`; results cached by `LyricsCache` (STANDARD/KARAOKE variants).
- Translation: two engines behind `lyrics/TranslationEngineUi` — `LyricsTranslator` (on-device ML Kit; language-pack download state machine) and `LlmTranslator` (OpenAI-compatible `/chat/completions`; JSON-array chunks, line-count validation, repair retries). Pref `translation_engine` routes; `TranslationCache` (filesDir/translation_cache, key = track+target+engine+model+sha256(source text), no expiry) is consulted first. Settings row `TranslationEngineView`, progress UI `TranslationStatusView` observes the active engine's `StateFlow`.
- Providers must never block playback: all network work runs off main; `Fetch` jobs are cancelled per-track.

## Conventions

- Objects/singletons for engines and clients; views are custom `LinearLayout`s installed from `AutoLyricsApp.onActivityResumed` with idempotent `install()`.
- SharedPreferences file name `auto_lyrics_prefs`; keys are `const val` on the owning class (`TranslationLanguages`, `LlmTranslator`, etc.).
- Failure UX: transient failures latch a manual-retry gate (see `prepareManualRetry`); never auto-retry in a loop, never log API keys.
- Remote LLM endpoints have no TLS requirement (server never runs on the phone; `usesCleartextTraffic` is enabled). Bare host input is normalized to `https://host/v1` by `LlmTranslator.normalizeBaseUrl`; the API key is optional. Model lists come from the endpoint's `/models` route with a manual-entry fallback. User-facing option lists stay intentionally small (see `TranslationLanguages.primaryTargets`).
