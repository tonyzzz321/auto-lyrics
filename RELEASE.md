# Auto Lyrics — Release Guide

This is the release checklist for maintainers. The GitHub Actions workflow can publish a GitHub Release from either a dedicated `Release vX.Y.Z` commit on `main` or a matching `vX.Y.Z` tag push.

The **recommended path** is the atomic release commit. It updates the Android version and triggers the release in one push, which avoids a separate manual tag step.

## Release invariant

The following values must describe the same release:

```text
app/build.gradle.kts: versionName = "X.Y.Z"
Git tag:              vX.Y.Z
Generated APK:        auto-lyrics-X.Y.Z.apk
```

`versionCode` must also increase for every new Android release.

The workflow derives the tag name and APK filename from `versionName`. Keep the release commit message, `versionName`, generated tag, and APK version aligned.

## Release triggers

`.github/workflows/build.yml` treats either of these as a release build:

```text
1. push a tag matching v*
2. push to main with a head commit message starting with "Release v"
```

Examples:

```text
Release v1.12.0     → release build
Bump version to 1.12.0 → ordinary main CI build
```

For the main-commit path, `softprops/action-gh-release` creates the unused tag derived from `versionName` and targets the release commit SHA. The workflow rejects the release before publishing if that tag already exists remotely.

For the tag path, the workflow validates that the pushed tag exactly matches `v${versionName}`.

## Optional GitHub Actions secrets

PetitLyrics is optional for release builds. Configure all four repository secrets to enable it:

```text
PETITLYRICS_USER_ID
PETITLYRICS_APP_NAME
PETITLYRICS_PKG_NAME
PETITLYRICS_CLIENT_APP_ID
```

When all four values are present, the workflow includes them in the release APK and reports that PetitLyrics is enabled in the job summary. If any value is missing, the workflow clears the entire set, reports that PetitLyrics is disabled in the job summary and workflow notice, then continues building and publishing the release without that provider. It does not print secret values.

PR and ordinary `main` builds intentionally do not receive these values. Those CI artifacts build with PetitLyrics disabled unless another non-secret fallback is explicitly present in the checkout.

## Recommended atomic release procedure

Assume the next release is `X.Y.Z`.

### 1. Update local main

```powershell
git switch main
git pull origin main
```

### 2. Bump Android version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>
versionName = "X.Y.Z"
```

Check the diff:

```powershell
git diff -- app/build.gradle.kts
```

### 3. Commit with the release trigger message

The commit message must start with `Release v`:

```powershell
git add app/build.gradle.kts
git commit -m "Release vX.Y.Z"
git push origin main
```

That single push starts the release workflow. Do **not** create the tag manually when using this path.

Before pushing, verify that the derived tag is unused:

```powershell
git ls-remote --tags origin refs/tags/vX.Y.Z
```

The command should print nothing. Release CI performs the same remote-tag check and fails before building/publishing if `vX.Y.Z` already exists.

### 4. Verify the workflow

The release job should:

1. read `versionName`,
2. validate tag/version consistency and reject tag reuse,
3. enable PetitLyrics only when all four optional secrets are configured (otherwise report that it is disabled and continue),
4. run unit tests,
5. run lint,
6. build the minified release APK,
7. copy it to `auto-lyrics-X.Y.Z.apk`,
8. upload the workflow artifact,
10. create tag `vX.Y.Z`,
11. publish GitHub Release `vX.Y.Z`,
12. attach the APK and generate release notes.

This is the path used for `v1.12.0`.

## Alternative tag-driven release procedure

A traditional tag push remains supported.

First commit the version bump to `main` with a normal non-release message and allow ordinary CI to pass:

```powershell
git add app/build.gradle.kts
git commit -m "Bump version to X.Y.Z"
git push origin main
```

Verify:

```powershell
Select-String -Path .\app\build.gradle.kts -Pattern 'versionCode|versionName'
```

Then create the **exact matching tag**:

```powershell
git tag vX.Y.Z
git push origin vX.Y.Z
```

For this path, a mismatch between the pushed tag and `versionName` fails `Validate release version`.

Do not reuse an existing version tag.

## What release CI runs

For either release trigger, the Linux `build` job performs:

1. checkout,
2. JDK 17 / Gradle setup,
3. read `versionName`,
4. validate tag/version consistency and reject tag reuse for the main-commit path,
5. configure the optional PetitLyrics provider when all four secrets are present, otherwise report it disabled and continue,
6. `testDebugUnitTest`,
7. `lintDebug`,
8. `assembleRelease`,
9. copy the APK to:

   ```text
   app/build/outputs/distribution/auto-lyrics-X.Y.Z.apk
   ```

10. upload the APK as a workflow artifact,
11. create/update GitHub Release `vX.Y.Z`,
13. attach the APK and generate release notes.

The release APK is minified and signed using the repository's configured signing setup.

The Windows regression job does **not** run on release pushes. It runs only for pull requests and manual `workflow_dispatch`. Release candidates should therefore already have passed Windows regression through the PR that introduced the code, or via a manual workflow run when needed.

## Post-release verification

Open the new GitHub Release and verify all of the following:

- release tag is `vX.Y.Z`,
- release points to the intended commit,
- attached file is `auto-lyrics-X.Y.Z.apk`,
- release is not draft/prerelease unless intentionally requested,
- Actions run completed successfully,
- the job summary confirms whether PetitLyrics was enabled or disabled as intended,
- unit tests passed,
- lint passed,
- release APK build passed,
- release notes cover the intended PR range.

For a functional smoke test, install the release APK and verify:

1. With Android Auto disconnected and Auto Lyrics closed, changing tracks in another media app does not start new provider resolution.
2. Opening Auto Lyrics on the phone resolves the current media session.
3. Connecting Android Auto activates lyric resolution without keeping the phone UI open.
4. LRCLIB lookup works on a known synchronized track.
5. Musixmatch lookup works on a known supported track.
6. PetitLyrics works on a known supported track when release credentials are present.
7. Karaoke mode can select a real WORD_SYNC candidate when it is near-equivalent to the standard winner.
8. SyncLRC is queried on every track change; token-bearing karaoke wins, its synced/plain tiers compete as Synced/Not-synced candidates, and a kana/alias track resolves through the iTunes catalog ladder when the exact `/lyrics` name match fails.
9. Manual Search opens timed-rows-only (the **Include unsynced results** checkbox reveals `Not synced` rows), never lists an all-symbol junk row, surfaces iTunes-catalog rows for an alias (kana) query, and an applied pick survives reload (permanent cache overwrite + forced retranslation).
10. Android Auto shows `Lyrics | Karaoke | Sync | More`; the header playback time ticks every second, including while lyrics or translations are still loading.
11. Current lyrics display the `▶` marker.
12. Lyrics tab is a forward 5-row window with the sung line pinned first, and each row's translation line starts at the same x as its lyric line.
13. Karaoke tab shows the two-row board for a Not-synced track too (estimated pair, `▶` on the estimated row, bottom row right-justified).
14. While a translation runs, browse rows stay single-line (no blank translation lines, no jumping); the two-line grid appears once the run completes and holds height after that.
15. Sync remains a fixed 3-row lyric preview; ±50 ms AA offset controls still update the view.
16. The Karaoke edge Reset zeroes both the connected car and the global default — the old value must not return after a reinstall or reconnect.
17. An instrumental stretch renders as the countdown row (one `♪` glyph per remaining second, max 4) in the Lyrics, Karaoke, and Sync tabs; the now-playing card holds title/artist until 4 s before the coming lyric, then counts down with it as subtitle — including mid-song gaps (the finished line drops immediately). No payload line that is blank, a music tag (`♪`/`🎵`/`[Music]`), a section header (`[Verse]`, `[Chorus]`, `【副歌】`), or a production credit (`词：林夕`, `Lyrics by …`) ever renders — on any surface, phone included (e.g. 侧面 by 张国荣 ships timed 词：/曲： credit rows that must never appear before the first sung line) — and a `[Verse]`-heavy payload still translates line-for-line correctly. Outro returns the card to title/artist (karaoke: after the last word's line ends; synced: 5 s after the last line starts) while the browse tabs keep the last lyric lit.
18. A track whose best upload is music-symbol-only resolves to the next provider's real lyrics (or reports not-found); it is never cached as empty 'found' lyrics. A track with no artist tag shows `Unknown Artist` on the AA now-playing card.
19. Transport-wipeout retry: when every race leg dies on transport errors (provider overload), the track holds LOADING, ONE automatic retry fires after 3 s, and a recovered provider resolves FOUND with no user action — while a genuine "no lyrics anywhere" track still reports not-found immediately with no retry.
20. Media source → Media apps: default reads `All apps`; uncheck `Monitor all apps` and pick one app ⇒ lyrics follow ONLY that app on phone and Android Auto, mid-playback (unchecking the current app flips to no-media or another whitelisted player immediately); every player appears in the dialog after it shows a media notification once; an app kept checked then uninstalled stays whitelisted (hidden from the dialog) and resumes on reinstall.
21. Translation Language lists `Simplified Chinese · 简体中文` and `Traditional Chinese · 繁體中文` (every entry shows its native name, e.g. `Japanese · 日本語`); with target Simplified, a TRADITIONAL payload converts to Simplified offline instantly (status stays `Already Chinese`, provider label unchanged, zero engine/network work) and the same song shows original form with target Traditional; flipping the target mid-song re-forms the visible lyrics immediately from cache; a Japanese payload never changes form under any Chinese target; a Simplified payload under target Traditional shows 繁體 on the AA board with karaoke highlights still landing on the right glyphs.22. Android Auto section **Karaoke single lines** switch: OFF (default) = no-translation board stays the constant two-line grid and the clock tick repaints silently; ON = rows drop to single lines immediately (mid-playback flip) and accept the tick nudge; with translations published the board is two-line regardless of the switch.23. A SyncLRC payload that opens with the song's own title/artist (`側面 - 張國榮`-style join, `歌曲：X 歌手：Y` label form, or a title line followed by an artist line) renders those lines NOWHERE — board, phone, card, translation — even when the entry was cached before this fix (re-stripped at every load); a first line that merely equals the title (the sung hook) still renders.24. A song with an INTRO marker plus a later INTERLUDE marker (short first line, long silence — 全部的爱 Live): while the intro counts 4→1, a previewed interlude row holds a steady 4 glyphs and starts ticking only inside ITS OWN final 4 seconds (never in lockstep with the intro); and during a short line immediately followed by an interlude, the now-playing subtitle previews the INTERLUDE MARKER itself (its glyph row, like the tabs) — never the lyric behind the gap, and never skips straight past the marker.25. Header + upgrade edge stability: a long CJK title ellipsizes on ONE line ending at the same right edge as the artist/time line (no wrapped second line); a mixed Latin title (`レイジョアハンズ!! ~Raise Y/Our Hands`-style) also stays ONE line — its ellipsis may land up to one word short of the edge but never tips `Hands…` onto line two at the CJK-calibrated +n; and upgrading over a previous install at the same head unit keeps the calibrated right edge EXACTLY where it was at the same +n value (resolved column pinned per fingerprint — only moving the knob or Reset re-resolves it).
27. Manual search rows are body-backed: search a song whose Musixmatch rows include refusals/instrumentals — the pick-list shows only rows whose lyric body actually arrived usable (tap the status line → diagnostics carry `musixmatch: k/n usable`), every listed row applies on the first click with NO `Fetching lyrics…` step and no `Provider returned no usable lyrics`, and kinds are final at list time (a `Karaoke · Musixmatch` row applies as Karaoke).
26. Now-playing card default wrap: a lyric line longer than the card's title field renders as the WHOLE line wrapped by the head unit onto its second line (no one-character chunk tails, no chunk stepping mid-line); countdown previews show whole next lines too. Flipping the static `MANUAL_CARD_SPLIT` flag ON restores word-chunk title walking, translated-chunk selection, and the first-chunk preview.
28. SyncLRC full coverage: play a track whose player reports NO artist — SyncLRC still participates via the title-only catalog ladder (the exact `/lyrics` call requires both params, so it is skipped — no 400, no death clause; duration carries the pick). Manual Search with a blank artist field lists up to 6 title-only catalog rows, each showing its own artist to pick from; with an artist given, the exact-term row lists FIRST and catalog rows append without re-fetching it; the status diagnostic reads `synclrc: N` / `synclrc: k/n usable` (dropped ladder candidates fold into the fraction — no per-candidate death lines) / `synclrc: 0 (no catalog match)` / `synclrc: <cause>`.
29. Manual Search status contract + mandatory title: the song-title field is required (Search refuses blank titles; the artist field stays optional), and the status line prints only the outcome — `12 results · track 4:31`, `0 results · track 4:31`, or a red `Error · track 4:31` when nothing listed AND a provider leg died transport-wise. All per-provider diagnostics (counts, catalog misses, bodies kept, hidden-unsynced tally, leg causes) moved behind a TAP on the status line (monospace diagnostics dialog, Close returns). Kill a leg to see Error: search with airplane mode on → `Error · track …`, tap shows the per-leg timeouts.
30. Per-lyrics sync replaces the global Phone Sync (upgrade NOTE: the legacy `lyrics_offset_ms` value is dropped on first launch): the main-view presets, Reset and Tap to Sync now store against the LOADED LYRICS — replay or cache-hit rides the tuned value, applying DIFFERENT lyrics text starts at 0 (verified via the status line `Offset: ±Nms · this lyrics`), and nothing loads → buttons no-op with `No lyrics loaded to sync`. The AA Sync tab edits the same value (`Offset: +0.2s` seconds display; `◀◀ Delay lyrics: −0.2s` / `▶▶ Advance lyrics: +0.2s`; the ± tap opens the host's playback view — UNAVOIDABLE for playables per Google's media interaction model, and the browsable-folder alternative was live-reverted because Gearhead prefetches folder children without a tap, drifting the offset on its own) and shows NO global-AA row; the phone settings **Global AA Sync** row (±100 ms) edits `aa_offset_ms` and stays invisible on the head unit.
## Local release-like build

A local `.env` can be used for provider configuration, but it does not reproduce GitHub's repository-secret injection exactly.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## If a release was created with the wrong version

Prefer creating a new corrected release version rather than rewriting an already published release/tag.

For example:

1. bump `versionCode` and `versionName` correctly,
2. commit the corrected version,
3. publish the next unused release version,
4. verify the generated tag and APK filename before considering the release complete.

Avoid force-moving published release tags unless there is a specific reason and all consumers of that tag are understood.

## Related documentation

- [README.md](README.md) — installation, user-facing features, local build basics.
- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, provider demand, resolver, cache, provider formats, Android Auto implementation, regression checklist.
