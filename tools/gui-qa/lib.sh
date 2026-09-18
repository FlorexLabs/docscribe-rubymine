#!/bin/zsh
# Host-side GUI driver for docscribe-rubymine QA (ch.3) in Tart VM `qa-vm`.
# Proven 2026-09-15 (pilot 3B.1): Search Everywhere (Cmd+Shift+A) + plain-text
# query + Enter fires DocScribe actions; balloon oracle via screenshot <=6s
# after Enter; idea.log oracle for SDK/daemon/RBS details.
#
# Laws (RubyMine flavour of ~/qa-vm/GUI_DRIVER_GUIDE.md):
# - No images in LLM context: screenshots stay files, only vocr text asserted.
# - Cmd+Shift+A opens Search Everywhere (NOT Find Action); "All" tab searches
#   Actions too. Type query, verify row via OCR, Enter.
# - Balloon lifetime short: screenshot within ~6s of Enter (missed at +12s).
# - Shortcuts-conflict dialog ("Don't Show Again") dismissed once per IDE boot.
# - Trust dialog ("Trust Project") clicked once per new project dir.
# - Coordinates are @2x retina: click = OCR/2 (see click_text). CLICLICK
#   ORIGIN (proven 2026-09-16): display top-left = (0,0), same as shots.
# - VM IP floats: resolve via `tart ip` every run. Proxy vars break ssh/scp.
# - RubyMine CLI in guest: ~/bin/rubymine (symlink to .app/MacOS/rubymine).

unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy

VM="${QA_VM:-qa-vm}"
IP="$(tart ip "$VM" 2>/dev/null)"
if [[ -z "$IP" ]]; then
  echo "FATAL: VM $VM has no IP. Start it: tart run --no-graphics $VM" >&2
  exit 2
fi
SSH_HOST="admin@$IP"
SSH=(ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 "$SSH_HOST")

SHOT_DIR="${SHOT_DIR:-/tmp/gui-qa-rm}"
mkdir -p "$SHOT_DIR"
VOCR="${VOCR:-$HOME/qa-vm-bin/vocr}"
LAST_SHOT=""

gssh() { "${SSH[@]}" "$@" }

# kill_terminal — pkill dregs ONLY. NEVER `tell application "Terminal"`:
# an Application-tell LAUNCHES Terminal.app when absent, and the newborn
# window steals front seconds later, right under our chord (proven
# 2026-09-16: front=Terminal after chord, menu bar Terminal over the
# correctly-opened palette). All guest control goes via ssh anyway.
kill_terminal() {
  gssh 'pkill -9 -x Terminal 2>/dev/null; pkill -9 -x man 2>/dev/null; pkill -9 -x less 2>/dev/null' >/dev/null 2>&1
  sleep 1
}

# shot <name> — guest screencapture + scp to host, sets LAST_SHOT.
# Removes the previous file FIRST: a failed capture must leave NO file,
# not a stale one (proven 2026-09-16: ocr matched a previous run's page
# shot after a silent scp failure → false direct-hit in settings_open_page).
shot() {
  LAST_SHOT="$SHOT_DIR/$1.png"
  rm -f "$LAST_SHOT"
  gssh "screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1 || return 1
  scp -o StrictHostKeyChecking=no "$SSH_HOST:/tmp/rm-qa.png" "$LAST_SHOT" >/dev/null 2>&1 || return 1
}

ocr_text() { "$VOCR" --text-only "$LAST_SHOT" 2>/dev/null }
ocr_json() { "$VOCR" "$LAST_SHOT" 2>/dev/null }

# osa <applescript> — osascript in guest via STDIN. Never `osascript -e`
# through ssh argv: ssh joins argv with bare spaces and both local `$*` and
# the remote shell eat quotes, so `-e 'tell ... "a" ...'` arrives broken
# (proven 2026-09-15: search box never opened, stale-box false greens).
# Stdin bytes are literal — no quoting layers at all. Callers pass the
# script as a single arg, no `-e`.
# RETRY (proven 2026-09-16): guest osascript intermittently fails with
# -10810/-600 (IDE busy/indexing) and SILENTLY sends nothing — the caller
# then types into whatever has front. Retry 3x on empty output... but
# osascript returns empty on SUCCESS too, so callers that need proof must
# use osa_checked (returns output, fails when empty AND exit!=0).
osa() { print -r -- "$*" | "${SSH[@]}" osascript; }

# front_is_rubymine — hard gate before ANY keystroke batch. Returns 0 only
# if RubyMine is front RIGHT NOW (no activate attempt, pure check).
front_is_rubymine() {
  local front
  front=$(osa 'tell application "System Events" to return name of first process whose frontmost is true' 2>/dev/null)
  echo "$front" | grep -qi "rubymine"
}
activate() {
  local tries front
  for (( tries = 1; tries <= 3; tries++ )); do
    osa 'tell application "RubyMine" to activate' >/dev/null 2>&1
    sleep 1
    # Proof RubyMine is frontmost: keystrokes otherwise leak into the guest
    # Terminal/Finder and open stray windows (proven 2026-09-15: "man Check"
    # Terminal windows spawned by our own query text landing in a shell via
    # a zsh not-found handler, then overlay poisoned every oracle).
    # NOTE: never query `tell application "Terminal"` — that LAUNCHES it.
    front=$(osa 'tell application "System Events" to return name of first process whose frontmost is true' 2>/dev/null)
    if echo "$front" | grep -qi "rubymine"; then
      return 0
    fi
    echo "activate try $tries: front is [$front]" >&2
    # Guest Terminal.app is never needed (all guest control via ssh);
    # kill it AND its man/less children when it steals front so retries
    # type into the IDE, not a shell. man/less survive pkill -x Terminal
    # as orphans and keep their windows (proven 2026-09-16: 21 man
    # windows, front=Terminal right after RubyMine-activate).
    if echo "$front" | grep -qi "terminal"; then
      kill_terminal >/dev/null 2>&1
      sleep 2
    fi
    sleep 2
  done
  return 1
}
escape() {
  osa 'tell application "System Events" to key code 53' >/dev/null 2>&1
  sleep 0.5
}

# escape_x2 — dismiss stacked dialogs (Settings + crash reporter + save
# dialog can pile 2-3 deep; proven 2026-09-16: single escape left Settings
# front, next shot read the Plugins page and the case asserted on garbage).
escape_x2() {
  escape; sleep 1; escape; sleep 2
}

# search_type <query> — Cmd+Shift+A, type query (no Enter). Idempotent:
# leading Escape closes stale dialogs (a stale open Search would toggle shut
# and eat the query).
# LANDMINES (all proven 2026-09-15/16, full day burned):
# 1. NEVER type while a Search/Recent popup is already open: the query text
#    lands in the popup's own speed-search, never reaches the box, and the
#    leaked keystrokes spawn Terminal man windows.
# 2. The guest keeps a stuck Cmd/Shift MODIFIER state after ANY Terminal/man
#    episode: subsequent chords re-fire "Open man Page for Selection" in
#    Terminal instead of Search Everywhere. No key-tap releases it (tried
#    bare Cmd, Ctrl, double-Cmd). ONLY a fresh Terminal kill + activate
#    cycle clears it; first chord after cleanup opens Search reliably,
#    later chords degrade again.
# 3. Cmd+Shift+A is OVERLOADED in RubyMine: first press opens "Recent
#    Locations" (?), second press opens Search Everywhere. Blind Enter
#    after one press fires the WRONG action.
# 4. MAN-PAGE SERVICE (the big one, proven 2026-09-16): typing '_' (as in
#    "update_types") into Search Everywhere triggers macOS "Open man Page
#    for Selection" — Terminal opens "man <text>" ASYNCHRONOUSLY, seconds
#    after the keystroke. The palette underneath still shows through, so
#    OCR sees the query echo AND the man overlay in one shot. Mitigation:
#    type the query in TWO chunks ("upd" + "ate_types") with a verify
#    between: if the first chunk already spawned Terminal, abort before
#    the '_' is ever typed.
# Protocol: kill Terminal/man/less -> activate -> Escape (dismiss stale
# popup) -> verify NO popup via OCR -> chord -> SHORT sleep -> front gate
# -> type query IMMEDIATELY -> verify query echo via OCR -> Enter.
# TIMING IS THE BUG (proven 2026-09-16): the old 3s sleeps after chord and
# after typing created windows where a focus steal routed keystrokes into
# a guest shell (man-page service: Terminal launches, "Open man Page for
# Selection" eats the query, overlay poisons all oracles). Sleeps now 1s/2s
# and every keystroke batch is front-gated.
# open_palette — open Search Everywhere via MENU CLICKS (Navigate >
# Search Everywhere), NOT a keyboard chord. Proven 2026-09-16: BOTH chord
# paths are poisoned — osascript keystroke AND cliclick kd/ku trigger the
# macOS man-page service ("Open man Page for Selection": Terminal steals
# front, T=1, query lands in editor and CORRUPTS calc.rb). cliclick
# kp:"cmd+shift+a" never opens the palette at all (6/6 no-shell). Menu
# clicks open it cleanly (T=0, front stays rubymine, markers present).
open_palette() {
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" 'cliclick c:271,9; sleep 2' >/dev/null 2>&1
  shot "palette-nav"
  local se
  se=$("$VOCR" "$SHOT_DIR/palette-nav.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Search Everywhere':
        print(f\"{int(o['x']/2)},{int(o['y']/2)}\")
        break
")
  [[ -z "$se" ]] && { echo "open_palette: no Search Everywhere row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$se; sleep 2" >/dev/null 2>&1
  front_is_rubymine || { echo "open_palette: lost front" >&2; return 1; }
}

search_type() {
  local tries i
  for (( tries = 1; tries <= 3; tries++ )); do
    kill_terminal >/dev/null 2>&1
    sleep 1
    escape
    # Fail fast: NEVER type the query when RubyMine is not front — the text
    # would land in a guest shell and spawn man windows (proven 2026-09-15).
    activate || { echo "search_type: RubyMine not front, abort (no keystrokes sent)" >&2; return 1; }
    front_is_rubymine || { echo "search_type: lost front after activate, abort" >&2; return 1; }
    # Stale popup check: abort the attempt (Escape next round closes it).
    # NOTE: the Search Everywhere *toggle button* ("Search Everywhere 1 1"
    # + Run Anything/Go to File rows) is NOT a stale popup — it's the
    # palette's mode switcher, always rendered once the box opens. Only
    # RESULT rows (Recent Locations/Find Action/All/Classes/Files...) count.
    shot "search-pre-$tries"
    if ocr_text | grep -qi "Recent Locations\|Find Action"; then
      echo "search_type try $tries: stale popup open, escape+retry" >&2
      escape
      sleep 1
      continue
    fi
    open_palette || { escape; sleep 1; continue; }
    # Clear stale query: Search Everywhere RETAINS the previous query when
    # reopened (proven 2026-09-16: "doctor" appended to "update_types" ->
    # "update_typesdoctor" still ranked Update first, Enter fired the WRONG
    # action). Cmd+A + Delete via osascript (proven clean: T=0; cliclick
    # kp with modifiers DROPS the keys, proven same day).
    osa 'tell application "System Events" to keystroke "a" using command down' >/dev/null 2>&1
    sleep 1
    osa 'tell application "System Events" to key code 51' >/dev/null 2>&1
    sleep 1
    # Gate 1: palette SHELL must be open BEFORE any typing — else the query
    # lands in the editor and corrupts the fixture (proven 2026-09-16:
    # chord missed, "update_types" typed into calc.rb -> "class
    # Calcupdate-types", oracle then matched the query in the editor as a
    # false positive). No shell -> escape + retry, zero keystrokes.
    shot "search-open-$tries"
    if ! ocr_text | grep -qi "Search Everywhere\|Find Action\|Recent Locations\|Include disabled actions\|Symbols"; then
      echo "search_type try $tries: palette shell not open, escape+retry" >&2
      escape; sleep 1
      continue
    fi
    # Query typing via osascript keystroke (NOT cliclick t: — cliclick
    # splits on spaces: `t:Check Entire Workspace` arrives as 3 argv and
    # only "Check" is typed; proven 2026-09-17 while writing 3F). osa
    # passes the script via stdin so spaces/quotes are literal. Palette
    # opens by MENU now, so no chord/man-service risk on typing.
    unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
    local qhead="$1" qtail=""
    if [[ "$1" == *_* ]]; then
      qhead="${1%%_*}"
      qtail="_${1#*_}"
    fi
    osa "tell application \"System Events\" to keystroke \"$qhead\"" >/dev/null 2>&1
    sleep 2
    # Terminal check via pgrep (NOT System Events window count: querying
    # `tell process "Terminal"` risks LAUNCHING Terminal.app when absent —
    # same class of bug as the kill_terminal AppleScript tell, proven
    # 2026-09-16). pgrep is side-effect free.
    local tc
    tc=$(gssh 'pgrep -x Terminal | wc -l' 2>/dev/null | tr -d ' ')
    if [[ -n "$tc" && "$tc" != "0" ]]; then
      echo "search_type try $tries: Terminal spawned mid-query, abort" >&2
      escape; sleep 1
      continue
    fi
    if [[ -n "$qtail" ]]; then
      osa "tell application \"System Events\" to keystroke \"$qtail\"" >/dev/null 2>&1
      sleep 2
    fi
    shot "search-$tries"
    # Proof the box is open AND received the query: query echo PLUS a
    # palette-shell marker. Query-alone is NOT proof — a missed chord types
    # into the editor and the editor then contains the query (false positive
    # proven 2026-09-16: "Calcupdate-types" in calc.rb matched "$1").
    local stxt; stxt="$(ocr_text)"
    if echo "$stxt" | grep -qi "$1" && echo "$stxt" | grep -qi "Search Everywhere\|Find Action\|Recent Locations\|Include disabled actions\|Symbols\|Update Types\|Check Entire Workspace\|Check Current File\|DocScribe Doctor"; then
      return 0
    fi
    echo "search_type try $tries: box not open, retry" >&2
  done
  return 1
}

# search_fire <query> <tag> — search_type + Enter, with the Settings-trap
# guard: if Enter landed in Settings (query matched a Settings row better
# than the action), the shot shows the Settings dialog — caller must treat
# as failure, NOT as the action result (proven 2026-09-16: Enter on
# "Update Types from RBS" opened Settings > Plugins, balloon oracle then
# read the Plugins page).
# MAN-PAGE GUARD (proven 2026-09-16): queries containing '_' (update_types,
# Close All Tabs...) trigger the macOS man-page service when ANY keystroke
# leaks: Terminal opens "man <query>" and eats the rest. If the post-Enter
# shot shows a man overlay, fail fast (caller retries after Terminal kill).
search_fire() {
  # NOTE: $1 MUST be forwarded — a hardcoded "update_types" here silently
  # fired Update for every caller (proven 2026-09-16: 3e7 doctor leg typed
  # update_types, balloon asserted on the stale update result).
  search_type "$1" || return 1
  sleep 2
  shot "$2-palette"
  search_enter
  shot "$2"
  if ocr_text | grep -qi "No manual entry\|^man \|manual entry for"; then
    echo "search_fire $1: leaked to Terminal man page" >&2
    escape_x2
    return 1
  fi
  if ocr_text | grep -qi "Marketplace\|Select plugin to preview"; then
    echo "search_fire $1: landed in Settings, not the action" >&2
    escape_x2
    return 1
  fi
}

# search_enter — Enter + short settle (balloon must be shot within ~6s).
search_enter() {
  osa 'tell application "System Events" to key code 36' >/dev/null 2>&1
  sleep 6
}

# click_text <shot-png> <grep-pattern> — click first OCR match center (retina /2).
# FORBIDDEN patterns (proven 2026-09-15): the menubar overlay
# "man Check Current File" (a Terminal window, NOT IDE UI) — clicking it
# opens more Terminal windows and wedges the VM. Never click y<60 rows.
click_text() {
  local png="$1" pattern="$2" xy
  xy=$("$VOCR" "$png" 2>/dev/null | python3 -c "
import json,sys,re
d = json.load(sys.stdin)
for o in d:
    if re.search(r'''$pattern''', o['text'], re.I):
        print(f\"{int((o['x'] + o['w'] / 2) / 2)},{int((o['y'] + o['h'] / 2) / 2)}\")
        break
")
  if [[ -z "$xy" ]]; then
    echo "click_text: no match for /$pattern/ in $png" >&2
    return 1
  fi
  gssh "cliclick c:$xy" >/dev/null 2>&1
  sleep 1
}

# open_file <guest-path> — FOREGROUND rubymine CLI (blocks until the window
# lands in the new project) + trust dialog. Background `&` never retargets:
# the new file opens as a stray tab in the OLD project window (proven
# 2026-09-15: qa-nogem/foo.rb landed inside qa-stand window).
# NOTE: foreground CLI steals the ssh session ~25s; run it as its own gssh.
open_file() {
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=60 admin@"$ip" \
    "\$HOME/bin/rubymine \"\$HOME/$1\"" >/dev/null 2>&1
  sleep 5
  activate
  shot "trust-check"
  if ocr_text | grep -qi "Trust Project"; then
    click_text "$LAST_SHOT" "Trust Project" || return 1
    sleep 25
    activate
  fi
}

# front_window_has <pattern> — verify the FRONT IDE window is ours via
# System Events window name (OCR sees all windows, not just front).
# NOTE: `name of window 1` is unreliable — RubyMine reports an empty first
# name when the welcome/dialog layer exists (proven 2026-09-15: got "" with
# windows=[, qa-stand – calc.rb]). Match against EVERY window name.
front_window_has() {
  local name
  name=$(osa 'tell application "System Events" to tell process "rubymine" to return name of every window' 2>/dev/null)
  echo "$name" | grep -qi "$1"
}

# rm_log_grep <pattern> — grep guest idea.log.
rm_log_grep() {
  gssh "grep -i '$1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log 2>/dev/null | tail -n 20"
}

pass() { echo "ok $1"; return 0 }
fail() { echo "not ok $1 $2" >&2; return 1 }
