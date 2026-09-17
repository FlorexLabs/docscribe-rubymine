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
