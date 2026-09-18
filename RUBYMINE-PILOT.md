# RubyMine driver pilot — reconnaissance notes (2026-09-14)

## CLI recon (`rubymine --list-commands` / `--help`)

Useful for the driver:
- `rubymine /project/dir` — open project (= our `code <dir>` for fresh_window).
- `rubymine [project] --line N --column M file` — open file AT a line.
  VS Code refused `:line` (unreliable via osascript nav); here it is a native
  CLI flag, so RubyMine `open_file` can place the cursor exactly. More precise oracles.
- `--wait` — block until the editor tab closes. Useless for screenshots, but
  proves the CLI can synchronize — potential replacement for blind sleeps.
- `inspect` — headless project analysis from CLI. Potential SECOND oracle
  (like our `code --status`): run checks headless, compare with GUI results.
  Caution: needs configured project/SDK, slow.

NOT available (confirms the plan):
- No window list, no current-project query, no headless GUI-state command.
  `window_gate` for RubyMine must be built via System Events UI elements or
  OCR anchors — same as VS Code, re-tuned.

Rest of `--list-commands` is irrelevant: Remote Dev / thin client / SQL /
shared indexes / format / duplocate / rinspect.

## Differences vs VS Code driver (plan)

1. **Palette.** Cmd+Shift+A (Find Action) or double-Shift instead of Cmd+Shift+P.
   Pattern stays: type plain text → Enter → settle. `palette()` gets rewritten,
   everything downstream lives.
2. **Window accounting.** No `code --status` equivalent. System Events UI
   elements or OCR anchors. This is the #1 unknown — probe first.
3. **Speed.** JVM + SDK indexing: startup/settle several times slower than Code.
   Full run estimate 1.5–2h vs 50 min; all sleeps/retries need recalibration.
4. **License in VM.** RubyMine is paid. Host shows `ja-netfilter` javaagent in
   the launcher banner — the VM needs the same agent/config copied over, or
   RubyMine will not start. Risk for the pilot checklist.
5. **Settings.** XML under `~/Library/Application Support/JetBrains/.../options/`
   instead of `settings.json` — scriptable with python the same way. Ruby SDK
   (rbenv) configured once, config cloned.

## Sections to cover (checklist ch.3, 69 items, currently ~all skipped in JSON)

3A install/menu/hotkeys (7), 3B actions (9), 3C annotator (15), 3D RBS (7),
3E update types (8), 3F workspace batch (6), 3G doctor (5), 3H MCP/settings (12).

## Pilot proposal (1 session)

One 3B case (Check File → Problems → Output). Answers the two unknowns:
palette reliability + window accounting. If green in one session, run ch.3
in full. Estimate for full ch.3: 5–7 sessions by ch.2 analogy.
