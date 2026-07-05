# Changelog

## [0.1.5] — 2026-07-05

### Added

- **Rakefile support (no extension)** — all annotators, actions, and intention actions now accept
  a file named `Rakefile` (without `.rb` or `.rake` extension) in addition to `.rb` and `.rake`
  files. This ensures the plugin works on standard Rakefiles that have no file extension.
- **Graceful handling when docscribe gem not installed** — `DocscribeDaemon` now runs
  `bundle exec docscribe --version` once on first use and caches the result. If the gem is missing, a
  user-friendly notification (with "Open Gemfile" action) is shown once, and all operations return a
  clear error: *"Add 'gem \"docscribe\"' to your Gemfile and run 'bundle install'"*.
- **Tests for gem availability check** — 5 new tests in `DocscribeDaemonGemTest.kt` covering MISSING
  status, error message format, all command types, and status caching.

### Changed

- **All commands now use project Ruby SDK** — `bundle exec docscribe` (via `DocscribeRunner`
  and `DocscribeDaemon.fallback()`) and `bundle exec docscribe --version` (via
  `DocscribeDaemon.performGemCheck()`) now prepend the Ruby SDK's `bin/` directory to `PATH`
  and set `BUNDLE_GEMFILE`. This ensures the SDK's Ruby and Bundler are used instead of system
  defaults. Extracted shared `buildSdkEnvironment()` helper, also reused in server startup.
- **`com.intellij.modules.ruby` made optional** — changed from hard `<depends>` to
  `<depends optional="true" config-file="withRubyPlugin.xml">`. Ruby-specific extensions
  (`ExternalAnnotator`, `FoldingBuilder`, `DependencySupport`, `IntentionAction`) moved to a new
  secondary descriptor. Plugin now installs on IntelliJ IDEA without Ruby plugin (limited
  functionality), unblocking Marketplace publication.
- **Annotation cache now respects settings** — `configHash` in `AnnotatorFileInfo` is now derived
  from `DocscribeSettings.hideCommentsByDefault` instead of being hardcoded to `0`. Changing
  settings automatically invalidates cached annotations. Cache also has a max size (1000 entries)
  with LRU-like eviction.
- **Version** bumped from `0.1.4` to `0.1.5`.

### Fixed

- **Annotation cache never invalidated on settings change** — `DocscribeSettingsChangeListener`
  now calls `DocscribeAnnotatorCache.clear()` when settings are saved, in addition to refreshing
  code folding.

### Build

- **`intellijDependencies()`** — added to repository section in `build.gradle.kts` (required by
  `instrumentTestCode` task).

## [0.1.4] — 2026-06-29

### Fixed

- **RPC protocol deadlock** — `rpcCall()` now appends `\n` to JSON-RPC request and calls `channel.shutdownOutput()`
  after write. Without these, the Ruby server's `client.gets` blocked forever waiting for a newline or EOF, causing an
  infinite progress bar in the IDE.
- **Exit code semantics** — all fix actions (`SafeFixAction`, `AggressiveFixAction`, `DocscribeFixIntention`,
  `DocscribeAggressiveFixIntention`) now check `exitCode != 0` instead of `exitCode >= 2`. The old check was inherited
  from RuboCop conventions where exit code 1 means "success with offenses". In daemon mode, the server returns exit code
  1 on errors, which was silently treated as success.
- **VFS refresh in intention actions** — added `vFile.refresh(false, false)` before
  `FileDocumentManager.getInstance().reloadFiles(vFile)` in both quick-fix intention actions. Without refresh,
  IntelliJ's VFS cache didn't detect the external file change.
- **Detekt compliance** — refactored `DocscribeDaemon.kt` to fix 6 violations: cyclomatic complexity, long method,
  `TooGenericExceptionCaught`, `MagicNumber`, and `ReturnCount`.
- **Folding builder never loaded** — `language="Ruby"` (wrong case) in `plugin.xml` caused `YardFoldingBuilder` to be
  silently ignored. Fixed to `language="ruby"` (lowercase).
- **Folding regions silently dropped** — missing `order="first"` allowed `RubyFoldingBuilder` to overwrite our regions.
  Fixed by adding `order="first"` to `plugin.xml`.
- **`NullableReturnType` warning** — suppressed with `@Suppress` annotation on `YardFoldingBuilder.getPlaceholderText`.
- **CI test failures** — added Ruby 3.4, `bundler-cache`, docscribe gem priority chain.

### Added

- **Daemon mode** — persistent Ruby server process communicating over Unix socket JSON-RPC 2.0. Replaces the old
  spawned-process-per-operation model, dramatically reducing latency on repeated check/fix operations.
- **`buildCheckJson` adapter** — converts daemon RPC responses into the RuboCop-compatible JSON format required by the
  annotation system for inline diagnostics.
- **`RunIdeTask` configuration** — supports `docscribe.local.gem.path` system property and `DOCSCRIBE_LOCAL_GEM_PATH`
  env var for local gem development.
- **Tests** — `RpcProtocolTest.kt` (11 tests) and `BuildCheckJsonTest.kt` (6 tests).
- **`hideCommentsByDefault` reactivity** — new `DocscribeSettingsChangeListener` topic + app-level service; toggling
  the setting now refreshes folding in all open editors.
- **Tests (round 2)** — 45 new tests (117 total) across 13 new files: `SafeFixActionTest`, `AggressiveFixActionTest`,
  `CheckWorkspaceActionTest`, `UpdateTypesActionTest`, `DocscribeFixIntentionTest`,
  `DocscribeAggressiveFixIntentionTest`, `DocscribeCheckIntentionTest`, `DocscribeAnnotatorCacheTest`,
  `YardFoldingBuilderTest`, `CommandFromOptionsTest`, `DocscribeDaemonTest`, `DocscribeRunnerTest`,
  `DocscribeSettingsChangeListenerTest`.
- **Full KDoc documentation** — all 17 main Kotlin source files documented (every class, method, and data class).

### Changed

- **Performance** — daemon mode keeps a persistent server process, eliminating CLI startup overhead on every check/fix
  operation.
- **Architecture** — removed `src/main/resources/daemon/docscribe-daemon.rb` (replaced by inline Ruby script in
  `DocscribeDaemon.kt`).
- **Version** bumped from `0.1.3` to `0.1.4`.
- **`DocscribeSettings`** — removed 5 fields (`commandPath`, `useBundleExec`, `runOnSave`, `useRbs`, `useDaemon`).
- **`DocscribeSettingsConfigurable`** — reduced to a single checkbox.
- **`DocscribeRunner.runDocscribe`** — always uses `bundle exec docscribe`.
- **`DocscribeDaemon`** — `useRbs` and `settings` parameters removed; daemon always on.
- **`DocscribeAnnotator.configHash`** — constant `0`.
- **`YardFoldingBuilder`** — fixed to `language="ruby"` (lowercase), `order="first"`, 6-arg `FoldingDescriptor`.
- **`DocscribeRunner.getCommandArgs`** — `-B` always passed in safe/aggressive modes.
- **CI pipeline** — Ruby 3.4, `bundler-cache`, docscribe gem priority chain.

### Removed

- Obsolete `docscribe-daemon.rb` standalone script.
- Settings `commandPath`, `useBundleExec`, `runOnSave`, `useRbs`, `useDaemon`, `omitBoilerplate`.
- Corresponding UI controls from the settings page.
- `GemfileHasRbsTest.kt` (logic moved into `UpdateTypesAction`).
