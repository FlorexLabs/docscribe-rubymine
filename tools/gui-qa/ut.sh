#!/bin/zsh
# ut.sh — update_types helpers (3E).
# Submenu dump: rclick editor text (500,140 default), hover DocScribe,
# OCR the submenu rows. Returns rows text (no firing).
submenu_rows() { # $1=tag [rc-x] [rc-y]
  local tag="$1" cx="${2:-500}" cy="${3:-140}"
  # Kill man-page Terminal first: a stale overlay eats the click and
  # poisons the shot (proven 2026-09-16: "man Update Types" overlay, no
  # DocScribe group found).
  gssh 'pkill -9 -x Terminal 2>/dev/null; pkill -9 -x man 2>/dev/null; pkill -9 -x less 2>/dev/null' >/dev/null 2>&1
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
  [[ -z "$DS" ]] && { echo "submenu $tag: no DocScribe group" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick m:${DS/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$tag-sub.png" >/dev/null 2>&1
  "$VOCR" --text-only "$SHOT_DIR/$tag-sub.png" 2>/dev/null
}

# mismatch_fixture — calc.rb String/String show + sig Integer show (3c4-proven).
mismatch_fixture() {
  gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  # @param [String] x
  # @return [String]
  def show(x)
    x.to_s
  end
end
EOF
sleep 12'
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def show: (Integer x) -> String
end
EOF
sleep 12'
}

# stand_md5 — md5 of all tracked stand files (sorted), for change detection.
stand_md5() {
  gssh 'cd ~/qa-stand && md5 -q calc.rb clean.rb partial.rb partial2.rb tasks.rake notes.txt Rakefile Gemfile docscribe.yml sig/calc.rbs 2>/dev/null'
}

# update_via_intention <tag> — cursor onto YARD line (click show, Up x2),
# Alt+Enter, find Update row index among DocScribe: rows, Down x index,
# Enter. Leaves balloon shot in $SHOT_DIR/<tag>.png + BALLOON_TEXT.
update_via_intention() {
  local tag="$1"
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  shot "$tag-goto"
  local SXY
  SXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if 'show' in o['text'] and o['x']>700]
cands.sort(key=lambda o: o['y'])
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
  [[ -z "$SXY" ]] && { echo "update intention $tag: no show anchor" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$SXY; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
  sleep 4
  shot "$tag-popup"
  local IDX
  IDX=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = sorted([o for o in d if 'DocScribe:' in o['text']], key=lambda o: o['y'])
for i, o in enumerate(rows):
    if 'Update types from RBS' in o['text']:
        print(i)
        break
")
  [[ -z "$IDX" ]] && { echo "update intention $tag: no Update row" >&2; return 1; }
  local k
  for (( k = 0; k < IDX; k++ )); do
    osa 'tell application "System Events" to key code 125' >/dev/null 2>&1
    sleep 1
  done
  osa 'tell application "System Events" to key code 36' >/dev/null 2>&1
  sleep 8
  shot "$tag"
  BALLOON_TEXT="$(balloon_select "$tag")"
}
