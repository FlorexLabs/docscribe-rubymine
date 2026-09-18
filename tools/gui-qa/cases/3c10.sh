#!/bin/zsh
# 3C.10 Missing-gem open: real qa-nogem2 PROJECT (Gemfile: rake only) ->
# open any .rb -> NO red squiggles (missing gem never annotates) + ONE
# WARNING balloon "Docscribe gem not found in this project..." + button
# "Add to Gemfile". (3a7 pattern: directory as project, New Window, Trust.)
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'rm -rf ~/qa-nogem2 && mkdir -p ~/qa-nogem2 && printf "source \"https://rubygems.org\"\ngem \"rake\"\n" > ~/qa-nogem2/Gemfile && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogem2/foo.rb && cd ~/qa-nogem2 && bundle install --quiet 2>&1 | tail -n 2; ls ~/qa-nogem2' || exit 2
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  'nohup $HOME/bin/rubymine "$HOME/qa-nogem2" >/dev/null 2>&1 &' >/dev/null 2>&1
OPD=0
for i in $(seq 1 18); do
  sleep 5
  activate >/dev/null 2>&1
  shot "3c10-open-dlg"
  if ocr_text | grep -qi "Open Project"; then OPD=1; break; fi
done
[[ $OPD -eq 0 ]] && { fail "3c10" "no Open Project dialog"; exit 1; }
XY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$XY" ]] && { fail "3c10" "no New Window button"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XY" >/dev/null 2>&1
sleep 20
activate
shot "3c10-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-nogem2" || { fail "3c10" "front window is not qa-nogem2"; exit 1; }
# Open foo.rb from the tree.
shot "3c10-tree"
TXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if 'foo.rb' in o['text'] and o['x'] < 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$TXY" ]] && { fail "3c10" "no foo.rb in tree"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TXY" >/dev/null 2>&1
sleep 20 # gem-check + balloon settle
activate
shot "3c10"
B="$(ocr_text)"
RC=0
echo "$B" | grep -qi "Docscribe gem not found in this project" || RC=1
echo "$B" | grep -qi "Add to Gemfile" || RC=2
# Log: MISSING path taken (balloon function ran).
gssh "grep -i 'missing-docscribe notification\|docscribe gem is not installed\|version check failed' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | tail -n 2 | cut -c1-160" >&2
# Back to the stand.
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -eq 1 ]]; then echo "--- b ---" >&2; echo "$B" >&2; fail "3c10" "no missing-gem balloon"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- b ---" >&2; echo "$B" >&2; fail "3c10" "no Add-to-Gemfile button"; exit 1; fi
pass "3c10"
