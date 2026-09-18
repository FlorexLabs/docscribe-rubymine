#!/bin/zsh
# 3B.7 Corrupt Gemfile (garbage) + intention safe fix -> ERROR balloon,
# calc.rb unchanged (refresh only on success).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

reset_calc
MD0="$(gssh 'md5 -q ~/qa-stand/calc.rb')"
# Corrupt: half-erased + garbage (bundler cannot even parse).
gssh 'printf "source \"https://rubygems.org\"\ngem \"docscribe\", path: [[[GARBAGE\n(((not ruby at all\n" > ~/qa-stand/Gemfile; head -n 3 ~/qa-stand/Gemfile' || exit 2
# Fresh daemon (project-service cache would otherwise stay AVAILABLE).
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
open_stand || { fail "3b7" "reopen failed"; exit 1; }
sleep 15 # daemon re-probe (fails) + annotate settle
activate
# Cursor must sit ON a warning line (the intention offer is per-annotation;
# 3b6 proved rows exist only with cursor on underlined line). Click the
# first `def` line (annotated) before Alt+Enter.
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
shot "3b7-anchor"
DXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if 'def add' in o['text'] and o['x']>700]
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
if [[ -n "$DXY" ]]; then
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$DXY; sleep 2" >/dev/null 2>&1
fi
osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
sleep 4
shot "3b7-intention"
IT="$(ocr_text)"
echo "$IT" | grep -qi "Apply safe fix" || { echo "--- intention ---" >&2; echo "$IT" >&2; fail "3b7" "no safe-fix row"; exit 1; }
# Click the safe-fix row.
RXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Apply safe fix' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$RXY" ]] && { fail "3b7" "no row coords"; exit 1; }
# NOTE: mouse-click on an intention row is unreliable — the popup loses
# focus on hover-move and clicks land on the editor (proven 2026-09-15 x2:
# popup still open after click, no balloon). Keyboard path: the popup opens
# with selection on the first row, which is AGGRESSIVE (order: aggressive,
# safe, check — proven 2026-09-15 OCR). Down once lands on safe fix, Enter
# invokes it deterministically.
osa 'tell application "System Events" to key code 125' >/dev/null 2>&1
sleep 1
osa 'tell application "System Events" to key code 36' >/dev/null 2>&1
sleep 8
shot "3b7"
B="$(ocr_text)"
RC=0
echo "$B" | grep -qiE "failed to apply fix" || RC=1
# Restore stand FIRST, then restart IDE (next cases need green daemon).
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", path: "/Users/admin/docscribe"
gem "rbs"
EOF
cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1' >/dev/null 2>&1
MD1="$(gssh 'md5 -q ~/qa-stand/calc.rb')"
[[ "$MD0" == "$MD1" ]] || { echo "md5 $MD0 -> $MD1" >&2; RC=2; }
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -eq 1 ]]; then echo "--- b ---" >&2; echo "$B" >&2; fail "3b7" "no ERROR balloon"; exit 1; fi
if [[ $RC -eq 2 ]]; then fail "3b7" "calc.rb modified"; exit 1; fi
pass "3b7"
