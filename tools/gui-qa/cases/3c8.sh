#!/bin/zsh
# 3C.8 space typo: [Sym bol] -> safe fix via lamp.
# DRIFT (proven 2026-09-16, gem 1.6.2): the fix yields [Object], NOT
# [Symbol] (CLI `-a` and `-A -k -B --rbs` both rewrite to [Object]).
# The mechanism (row offered, invoke works, file updated, balloon) is
# asserted; the literal [Symbol] is asserted as the checklist demands and
# is EXPECTED TO FAIL -> finding, not driver bug.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'printf "class Bad\n  # @param [Sym bol] x\n  def show(x)\n    x.to_s\n  end\nend\n" > ~/qa-stand/space.rb'
cleanup() { gssh 'rm -f ~/qa-stand/space.rb'; reset_calc; }
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
open_tree_file "space.rb" || { cleanup; fail "3c8" "no space.rb in tree"; exit 1; }
hit_space() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/space.rb' | grep -q 'offenses=[1-9]'
}
for i in $(seq 1 12); do
  sleep 5
  if hit_space; then break; fi
done
hit_space || { cleanup; fail "3c8" "no space.rb offenses pass"; exit 1; }
# Cursor onto the YARD line (annotation sits there, not on def): click
# `def show`, Up x2, Alt+Enter (3c5 pattern).
activate || { cleanup; exit 1; }
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
shot "3c8-goto"
SXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if 'show' in o['text'] and o['x']>700]
cands.sort(key=lambda o: o['y'])
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$SXY" ]] && { cleanup; fail "3c8" "no show anchor"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$SXY; sleep 2" >/dev/null 2>&1
osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
sleep 0.5
osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
sleep 0.5
osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
sleep 4
shot "3c8-popup"
IT="$(ocr_text)"
echo "$IT" | grep -qi "Apply safe fix" || { echo "--- popup ---" >&2; echo "$IT" >&2; cleanup; fail "3c8" "no safe-fix row"; exit 1; }
# Popup order (proven 3b7): aggressive, safe, check. Down once -> safe.
osa 'tell application "System Events" to key code 125' >/dev/null 2>&1
sleep 1
osa 'tell application "System Events" to key code 36' >/dev/null 2>&1
sleep 8
shot "3c8-done"
B="$(ocr_text)"
RC=0
echo "$B" | grep -qi "fix applied" || RC=1
sleep 4
DISK="$(gssh 'cat ~/qa-stand/space.rb')"
echo "$DISK" | grep -q "Sym bol" && RC=2
echo "$DISK" | grep -q "\[Symbol\]" || RC=3
cleanup
if [[ $RC -eq 1 ]]; then echo "--- b ---" >&2; echo "$B" >&2; fail "3c8" "no fix-applied balloon"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- disk ---" >&2; echo "$DISK" >&2; fail "3c8" "file untouched"; exit 1; fi
if [[ $RC -eq 3 ]]; then echo "--- disk ---" >&2; echo "$DISK" >&2; fail "3c8" "no [Symbol]: gem writes [Object] (checklist drift)"; exit 1; fi
pass "3c8"
