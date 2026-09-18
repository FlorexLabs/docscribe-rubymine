#!/bin/zsh
# Menu helper: rclick at editor text coords, hover DocScribe, fire a row,
# shot balloon. Usage: menu_fire <tag> <row-pattern> [rc-x] [rc-y].
# Requires stand.sh first (qa-stand front, calc.rb open).
menu_fire() {
  local tag="$1" row="$2" cx="${3:-500}" cy="${4:-140}"
  kill_terminal >/dev/null 2>&1
  sleep 2
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  escape
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick rc:$cx,$cy; sleep 3; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$tag-rc.png" >/dev/null 2>&1
  local DS
  DS=$("$VOCR" "$SHOT_DIR/$tag-rc.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip().endswith('DocScribe'):
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$DS" ]] && { echo "menu_fire $tag: no DocScribe group" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick m:${DS/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$tag-sub.png" >/dev/null 2>&1
  local CC
  CC=$("$VOCR" "$SHOT_DIR/$tag-sub.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='''$row''':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$CC" ]] && { echo "menu_fire $tag: no row $row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick c:${CC/,/,}; sleep 5; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$tag.png" >/dev/null 2>&1
  LAST_SHOT="$SHOT_DIR/$tag.png"
  # Balloon width: the notification box extends BEYOND the 2012px viewport
  # (Vision sees only the visible left part, e.g. "checked 1 file(s)"
  # without counts). The counts ARE in idea.log for every action? No —
  # only annotator logs counts. So: drag-select the balloon text into the
  # clipboard via OCR-anchored drag, then read pbpaste. Anchor = the
  # visible "DocScribe:" row (always rendered, left part visible).
  BALLOON_TEXT="$(balloon_select "$tag")"
}

# balloon_select <tag> — triple-click the balloon row (selects the whole
# notification line incl. the off-viewport part), Cmd+C, pbpaste.
balloon_select() {
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  local xy
  xy=$("$VOCR" "$SHOT_DIR/$1.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'DocScribe' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$xy" ]] && { echo ""; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick tc:$xy; sleep 1; cliclick kd:cmd t:c ku:cmd; sleep 1; pbpaste" 2>/dev/null
}

# doctor_report <tag> — full Doctor text via Notifications tool window.
# The Doctor BALLOON is height-capped (~4 lines visible, rest clipped —
# proven 2026-09-16: OCR sees title + project root only). The same report
# sits complete in View > Tool Windows > Notifications: click the entry,
# Cmd+A/Cmd+C (osascript, proven clean), pbpaste. Prints report to stdout.
# View-menu coords: View=222,9 (proven via menubar OCR; stable across runs).
doctor_report() {
  local tag="$1"
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" 'cliclick c:222,9; sleep 2' >/dev/null 2>&1
  shot "$tag-view"
  local tw
  tw=$("$VOCR" "$SHOT_DIR/$tag-view.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Tool Windows':
        print(f\"{int(o['x']/2)},{int(o['y']/2)}\")
        break
")
  [[ -z "$tw" ]] && { echo "doctor_report $tag: no Tool Windows row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick m:$tw; sleep 2" >/dev/null 2>&1
  shot "$tag-twsub"
  local nt
  nt=$("$VOCR" "$SHOT_DIR/$tag-twsub.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Notifications':
        print(f\"{int(o['x']/2)},{int(o['y']/2)}\")
        break
")
  [[ -z "$nt" ]] && { echo "doctor_report $tag: no Notifications row" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$nt; sleep 3" >/dev/null 2>&1
  shot "$tag-tw"
  local dxy
  dxy=$("$VOCR" "$SHOT_DIR/$tag-tw.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in sorted(d, key=lambda r: r['y']):
    if 'DocScribe' in o['text'] and '===' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$dxy" ]] && { echo "doctor_report $tag: no Doctor entry" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$dxy; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to keystroke "a" using command down' >/dev/null 2>&1
  sleep 1
  osa 'tell application "System Events" to keystroke "c" using command down' >/dev/null 2>&1
  sleep 1
  local rep
  rep=$(gssh 'pbpaste' 2>/dev/null)
  # Hide the tool window again (Shift+Escape), so later stand shots see
  # the plain project layout.
  osa 'tell application "System Events" to key code 53 using shift down' >/dev/null 2>&1
  sleep 1
  print -r -- "$rep"
}

# doctor_report_clip <tag> — full Doctor text via BALLOON click + Cmd+A/C.
# The Notifications tool window path (doctor_report) is FLAKY: the tool
# window often shows an older notification or no DocScribe entry at all
# (proven 2026-09-17: tw.png without any Doctor row while the balloon sat
# visible on screen). Clicking the balloon itself + select-all + copy is
# deterministic: the balloon is on screen by construction (caller fired it
# seconds ago). Anchor = the "=== DocScribe" title row (topmost). Prints
# the clipboard content to stdout.
doctor_report_clip() {
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  # The balloon EXPIRES (~30-60s lifetime — proven 2026-09-17: anchor
  # present in an older shot, gone from a fresh shot minutes later).
  # Re-shot FIRST so the anchor coords are current, not from $1.png.
  shot "$1-live" >/dev/null 2>&1
  local png="$SHOT_DIR/$1-live.png"
  [[ -f "$png" ]] || png="$SHOT_DIR/$1.png"
  local dxy
  # Anchor: the TITLE row ("=== DocScribe", topmost DocScribe row), NOT the
  # balloon center. Clicking the center lands on balloon BODY text: the
  # click selects one wrapped line (or nothing copyable), Cmd+A then grabs
  # the underlying EDITOR, not the balloon (proven 2026-09-17: pbpaste
  # returned calc.rb source). Title-row click focuses the whole balloon.
  dxy=$("$VOCR" "$png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in sorted(d, key=lambda r: r['y']):
    if 'DocScribe' in o['text'] and '===' in o['text']:
        # LEFT part of the title row: the row's left end is the balloon's
        # text area; the row CENTER is already body text (proven 2026-09-17:
        # center-click + Cmd+A grabbed the editor). 10px right of left edge.
        print(f\"{int(o['x']/2)+10},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$dxy" ]] && { echo "doctor_report_clip $1: no balloon anchor" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$dxy; sleep 2" >/dev/null 2>&1
  # Select-all: Cmd+A via osascript is FOCUS-SENSITIVE (proven 2026-09-17:
  # after a balloon click the editor keeps focus, Cmd+A grabs calc.rb).
  # Triple-click instead: selects the balloon's paragraph in place — but
  # JetBrains balloons WRAP the report into separate visual lines, and a
  # triple-click grabs only ONE wrapped line (proven same day: 46 chars).
  # Robust path: drag INSIDE the balloon from the title row to the last
  # balloon row, THEN Cmd+C. Drag endpoints in logical coords.
  # CRITICAL (proven 2026-09-17): the drag must STAY inside the balloon
  # text column (x = title-row left + 10). Dragging from the tree panel
  # (x~112) selects the PROJECT TREE (pbpaste returns "Rakefile").
  local dnd
  dnd=$("$VOCR" "$png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
title = None
for o in sorted(d, key=lambda r: r['y']):
    if 'DocScribe' in o['text'] and '===' in o['text']:
        title = o
        break
if title is None: raise SystemExit
x = int(title['x']/2)+10
top = int((title['y']+title['h']/2)/2)
cands = [o for o in d if o['x'] > title['x']-100 and o['y'] > title['y']]
bot = max([int((o['y']+o['h']/2)/2) for o in cands]) if cands else top+200
print(f\"{x},{top}:{x},{bot}\")
")
  if [[ -n "$dnd" ]]; then
    local from="${dnd%%:*}" to="${dnd##*:}"
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dd:$from du:$to; sleep 1" >/dev/null 2>&1
  else
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick tc:$dxy; sleep 1" >/dev/null 2>&1
  fi
  osa 'tell application "System Events" to keystroke "c" using command down' >/dev/null 2>&1
  sleep 1
  gssh 'pbpaste' 2>/dev/null
}

# ocr_balloon <tag> — full balloon text via select+copy (fallback: OCR).
ocr_balloon() {
  if [[ -n "${BALLOON_TEXT:-}" ]]; then print -r -- "$BALLOON_TEXT"; else ocr_text; fi
}

# reset_calc — restore 2-undocumented-methods fixture (ALSO bumps the IDE
# clock: the annotator caches by modificationStamp, and a fast rewrite of
# identical bytes can reuse a stale stamp — proven 2026-09-15: 3c5 wrote
# the show-fixture but the IDE kept reporting the old add/sub content
# until an unrelated later event re-fired the pass).
reset_calc() {
  gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  def add(a, b)
    a + b
  end
  def sub(a, b)
    a - b
  end
end
EOF
sleep 12'
}
