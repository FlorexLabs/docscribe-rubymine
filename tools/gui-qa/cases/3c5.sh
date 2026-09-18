#!/bin/zsh
# 3C.5 Intention routing on RBS mismatch: with sig/ -> "Update types from
# RBS" row present; with sig/ off -> absent. Same 3c4 fixture (YARD String
# vs RBS Integer -> UpdatedParam, source=rbs).
# LESSONS (proven 2026-09-15):
# - safe/aggressive/check rows are GLOBAL intentions (withRubyPlugin.xml),
#   always in the popup. NEVER assert their absence; the routing signal is
#   presence/absence of the Update-types row ONLY.
# - The annotation sits on the YARD comment line (findYardTagLine), NOT the
#   def line. Cursor must be ON line 2: click `def show`, then Up x2.
#   (Clicking `show` + Alt+Enter shows only global rows — false red.)
# - Mark M0 BEFORE fixture writes: passes fire during the write sleeps;
#   marking after open_stand sees an idle IDE and the gate times out.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

M0="$(log_mark)"
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
cleanup() {
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
  sig_ensure
  reset_calc
}
hit_mismatch() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep 'doAnnotate parsed output' | grep -q 'offenses=1'
}
for i in $(seq 1 12); do
  sleep 5
  if hit_mismatch; then break; fi
done
hit_mismatch || { cleanup; fail "3c5" "no mismatch pass"; exit 1; }
open_stand || { cleanup; exit 1; }

# Cursor onto the YARD line: click `def show`, Up x2 (4->3->2).
goto_yard_line() {
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  shot "3c5-goto"
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
  [[ -z "$SXY" ]] && { echo "goto: no show anchor" >&2; return 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$SXY; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
  sleep 4
  shot "3c5-rbs"
}
goto_yard_line || { cleanup; fail "3c5" "no intention popup"; exit 1; }
IT="$(ocr_text)"
echo "$IT" | grep -qi "Update types from RBS" || { echo "--- popup ---" >&2; echo "$IT" >&2; cleanup; fail "3c5" "no Update-types row (RBS on)"; exit 1; }
# sig/ off -> same place routes away from Update Types.
sig_off
for i in $(seq 1 12); do
  sleep 5
  if rm_log_grep 'doAnnotate hashes' | tail -n 1 | grep -q 'bgHash=-1835857561'; then break; fi
done
goto_yard_line2() {
  activate || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  shot "3c5-goto2"
  local SXY
  # NOTE: when the intention popup is STILL OPEN from the previous half,
  # the editor is not screenshotted — anchor on the popup's own Update row.
  SXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if 'Update types from RBS' in o['text']]
if not cands:
    cands = [o for o in d if 'show' in o['text'] and o['x']>700]
    cands.sort(key=lambda o: o['y'])
if cands:
    o = cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
  [[ -z "$SXY" ]] && { echo "goto2: no show anchor" >&2; return 1; }
  # Dismiss a stale popup first (Escape), or the click lands inside it.
  escape
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$SXY; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1
  sleep 0.5
  osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
  sleep 4
  shot "3c5-off"
}
goto_yard_line2 || { sig_on; cleanup; fail "3c5" "no popup (RBS off)"; exit 1; }
IT2="$(ocr_text)"
sig_on
echo "$IT2" | grep -qi "Update types from RBS" && { echo "--- popup2 ---" >&2; echo "$IT2" >&2; cleanup; fail "3c5" "Update-types row present (RBS off)"; exit 1; }
cleanup
pass "3c5"
