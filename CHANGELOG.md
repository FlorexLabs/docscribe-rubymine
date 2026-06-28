# Changelog

## [0.1.4] — 2026-06-28

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

### Added

- **Daemon mode** — persistent Ruby server process communicating over Unix socket JSON-RPC 2.0. Replaces the old
  spawned-process-per-operation model, dramatically reducing latency on repeated check/fix operations.
- **`buildCheckJson` adapter** — converts daemon RPC responses into the RuboCop-compatible JSON format required by the
  annotation system for inline diagnostics.
- **`RunIdeTask` configuration** — supports `docscribe.local.gem.path` system property and `DOCSCRIBE_LOCAL_GEM_PATH`
  env var for local gem development.
- **Tests** — `RpcProtocolTest.kt` (11 tests) and `BuildCheckJsonTest.kt` (6 tests).

### Changed

- **Performance** — daemon mode keeps a persistent server process, eliminating CLI startup overhead on every check/fix
  operation.
- **Architecture** — removed `src/main/resources/daemon/docscribe-daemon.rb` (replaced by inline Ruby script in
  `DocscribeDaemon.kt`).
- **Version** bumped from `0.1.3` to `0.1.4`.

### Removed

- Obsolete `docscribe-daemon.rb` standalone script.
