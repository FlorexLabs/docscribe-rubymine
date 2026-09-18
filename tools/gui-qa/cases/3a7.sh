#!/bin/zsh
# 3A.7 Missing gem: real qa-nogem2 PROJECT (Gemfile: rake only) -> Check
# Current File (menu path) -> ERROR "DocScribe: error running docscribe";
# version check runs ONCE (MISSING cached, CLI not re-run).
# Proven 2026-09-15: attached-tab mode is invalid (old window's Gemfile
# wins); must open the DIRECTORY in a new window (This Window dialog +
# Trust), then the file inside it.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

gssh 'rm -rf ~/qa-nogem2 && mkdir -p ~/qa-nogem2 && printf "source \"https://rubygems.org\"\ngem \"rake\"\n" > ~/qa-nogem2/Gemfile && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogem2/foo.rb && cd ~/qa-nogem2 && bundle install --quiet 2>&1 | tail -n 2' || exit 2

# --- open directory as project (foreground CLI, This Window, Trust) ---
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=60 admin@"$ip" \
  '$HOME/bin/rubymine "$HOME/qa-nogem2"' >/dev/null 2>&1
sleep 5
activate
shot "3a7-open-dlg"
if ocr_text | grep -qi "Open Project"; then
  XY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$XY" ]] && { fail "3a7" "no New Window button"; exit 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XY" >/dev/null 2>&1
  sleep 20
  activate
fi
shot "3a7-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-nogem2" || { fail "3a7" "front window is not qa-nogem2"; exit 1; }

# --- open foo.rb inside the project (tree double-click) ---
shot "3a7-tree"
TXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip() in ('foo.rb','V foo.rb'):
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$TXY" ]] && { fail "3a7" "no foo.rb in tree"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TXY" >/dev/null 2>&1
sleep 8
activate

menu_check() { # $1=tag — rclick editor text, hover DocScribe, click Check, shot balloon
  escape
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    'cliclick rc:500,140; sleep 3; screencapture -x /tmp/rm-qa.png' >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$1-rc.png" >/dev/null 2>&1
  DS=$("$VOCR" "$SHOT_DIR/$1-rc.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip().endswith('DocScribe'):
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$DS" ]] && { fail "3a7" "$1: no DocScribe group"; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick m:${DS/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$1-sub.png" >/dev/null 2>&1
  CC=$("$VOCR" "$SHOT_DIR/$1-sub.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='Check Current File':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$CC" ]] && { fail "3a7" "$1: no Check row"; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick c:${CC/,/,}; sleep 5; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$1.png" >/dev/null 2>&1
  LAST_SHOT="$SHOT_DIR/$1.png"
}

menu_check "3a7-b1" || exit 1
ocr_text | grep -qi "DocScribe: error running docscribe" || { echo "--- b1 ---" >&2; ocr_text >&2; fail "3a7" "balloon1 mismatch"; exit 1; }
sleep 3
menu_check "3a7-b2" || exit 1
ocr_text | grep -qi "DocScribe: error running docscribe" || { echo "--- b2 ---" >&2; ocr_text >&2; fail "3a7" "balloon2 mismatch"; exit 1; }

N="$(gssh 'grep -c "docscribe version check failed" ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log' 2>/dev/null)"
if [[ "$N" != "1" ]]; then
  echo "version check ran $N times (want 1)" >&2
  fail "3a7" "gem check not cached"
  exit 1
fi
pass "3a7"
