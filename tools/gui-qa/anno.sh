#!/bin/zsh
# Annotator helpers (3C): log-backed oracles.
# - log_run <file> — fire one annotator pass by touching the file (mtime bump)
#   and wait for the "apply file=... offenses=N" line.
# - log_offenses <file> — last offenses= N for file (cachable path).
# - wait_log <pattern> <tries> — poll idea.log for pattern (5s steps).
cd "$(dirname "$0")" || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

LOGF='~/Library/Logs/JetBrains/RubyMine2026.2/idea.log'
log_mark() { gssh "wc -l < $LOGF" | tr -d ' '; }
# wait_log <grep-pattern> <mark> [tries=12] — new lines after mark matching pattern.
wait_log() {
  local pat="$1" mark="$2" tries="${3:-12}" i out
  for (( i = 1; i <= tries; i++ )); do
    out=$(gssh "awk 'NR>$mark' $LOGF | grep -i -F '$pat' | tail -n 3")
    if [[ -n "$out" ]]; then print -r -- "$out"; return 0; fi
    sleep 5
  done
  return 1
}
# offenses_for <file> <mark> — last "apply file=<file> ... offenses=N" after mark.
# NOTE: guest awk is BSD awk — no {line=$0}/match(RSTART) niceties. Keep to
# plain patterns; do the last-line selection with tail.
offenses_for() {
  local f="$1" mark="$2"
  gssh "awk 'NR>$mark' $LOGF | grep -F 'DocScribe apply file=$f' | tail -n 1"
}
# daemon_bounce — kill the gem server process so the plugin's ensureRunning
# starts a FRESH daemon on the next pass. The daemon caches config+RBS
# state per process (apply_cli_overrides short-circuits on equal overrides,
# and a stale server predates fixture changes); without a bounce the
# annotator keeps reporting the OLD fixture (proven 2026-09-15: 3c5 popup
# showed MissingDocBlock rows on an UpdatedParam fixture).
# NOTE: kills only the `docscribe server` ruby child, NOT the IDE.
daemon_bounce() { gssh 'pkill -f "docscribe server" 2>/dev/null; sleep 3'; }

# sig_off / sig_on — idempotent RBS on/off.
# DANGER (2026-09-16): sig/ is NOT in any git repo (qa-stand has no .git)
# and NOTHING recreates it — 3c4/3c5 cleanup() only RESTORES calc.rbs
# content. `rm -rf sig.off` must NEVER run while sig/ is already moved
# (it deletes the one copy). Guard: move only if sig/ exists.
sig_off() { gssh 'if [ -d ~/qa-stand/sig ]; then rm -rf ~/qa-stand/sig.off; mv ~/qa-stand/sig ~/qa-stand/sig.off; fi; true'; }
sig_on() { gssh 'if [ -d ~/qa-stand/sig.off ]; then rm -rf ~/qa-stand/sig; mv ~/qa-stand/sig.off ~/qa-stand/sig; fi; true'; }
# sig_ensure — recreate the canonical sig/calc.rbs (add/sub) from scratch.
# Call in cleanup() of EVERY case that touches sig/ (3c4/3c5/3c7/3d*).
sig_ensure() { gssh 'mkdir -p ~/qa-stand/sig; rm -rf ~/qa-stand/sig.off; cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'; }

# rewrite_run <guest-path> — rewrite same bytes (new mtime+content stamp;
# bare touch does NOT retrigger: VFS content stamp unchanged).
rewrite_run() { gssh "printf '%s\n' \"\$(cat $1)\" > $1; sleep 12"; }

# open_tree_file <name> — double-click tree row (x<600 panel), verify editor.
# Retries: a just-created file needs VFS refresh before the tree shows it
# (proven 2026-09-15: cfg.rb missing on first shot, present ~15s later).
# Precondition: no modal dialog open — Escape x2 first (proven 2026-09-16:
# leftover Settings window made the tree shot show the settings tree).
open_tree_file() {
  escape; escape
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  local try TXY
  for try in 1 2 3 4; do
    shot "tree-$1"
    TXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if '''$1''' in o['text'] and o['x'] < 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
    [[ -n "$TXY" ]] && break
    sleep 10
  done
  [[ -z "$TXY" ]] && { echo "open_tree_file $1: no tree row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TXY" >/dev/null 2>&1
  sleep 8
  activate || return 1
}

# open_intention <tag> [anchor] — click anchor in editor, Alt+Enter, shot.
# Default anchor 'def add'. Caller greps rows from ocr_text (LAST_SHOT).
open_intention() {
  # NOTE: anchor is matched as SUBSTRING of noisy OCR ('# @param [String]'
  # arrives as '#', '@return', ...). Prefer a stable rare token: 'show'
  # matches `def show` on one OCR row. Callers pass e.g. 'show'.
  local tag="$1" anchor="${2:-def add}"
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  shot "$tag-anchor"
  local AXY
  AXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
# OCR splits tokens ('# @param [String]' -> '#','@return','def show'...),
# so match SUBSTRING, not exact text. Editor region only (x>700).
cands = [o for o in d if '''$anchor''' in o['text'] and o['x']>700]
cands.sort(key=lambda o: o['y'])
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
  [[ -z "$AXY" ]] && { echo "open_intention $tag: no anchor $anchor" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$AXY; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
  sleep 4
  shot "$tag"
}
