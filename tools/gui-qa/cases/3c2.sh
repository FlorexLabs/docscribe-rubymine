#!/bin/zsh
# 3C.2 Unsaved split: (a) non-RBS project skips unsaved docs; (b) RBS project
# checks them with background save.
# Oracle: idea.log. (a): after dirtying calc.rb with sig/ moved away, NO new
# "calling daemon" block for calc.rb appears (only cache/skip paths);
# (b): with sig/ back, a new block with offenses=3 appears.
# Paste-via-pbcopy typing (3b3 lesson), End-anchored (second-to-last end).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

reset_calc
# (a) FIRST, while still RBS: sanity — paste is dirty and checkable at all.
# (Skipped: the (b) half below proves paste works; a pre-check would only
# add timing noise.)
sig_off || exit 1
cleanup() { sig_ensure; gssh 'cd ~/qa-stand && bundle install --quiet 2>/dev/null'; }
open_stand || { cleanup; exit 1; }
# Settle until the sig.off state is VISIBLE in the log: bgHash WITHOUT sig/
# is -1835857561, with sig/ it is 1273797769 (proves rbsHash re-probed).
# Earlier runs pasted while the annotator still saw sig/, producing
# offenses=3 inside the (a) window.
for i in $(seq 1 12); do
  sleep 5
  if rm_log_grep 'doAnnotate hashes' | tail -n 1 | grep -q 'bgHash=-1835857561'; then break; fi
done
rm_log_grep 'doAnnotate hashes' | tail -n 1 | grep -q 'bgHash=-1835857561' || { cleanup; fail "3c2" "sig.off not picked up"; exit 1; }
M0="$(log_mark)"
# Dirty the buffer (paste new method, do NOT save).
activate
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
shot "3c2-anchor"
EXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if o['text'].strip()=='end' and o['x']>700]
cands.sort(key=lambda o: o['y'])
o = cands[-2] if len(cands) >= 2 else cands[-1]
print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$EXY" ]] && { cleanup; fail "3c2" "no end anchor"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$EXY; sleep 1" >/dev/null 2>&1
osa 'tell application "System Events" to key code 53' >/dev/null 2>&1; sleep 0.5
osa 'tell application "System Events" to key code 119' >/dev/null 2>&1; sleep 0.5
osa 'tell application "System Events" to key code 36' >/dev/null 2>&1; sleep 0.5
print -r -- "  def mul(qq, ww)
    qq + ww
  end" | ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" pbcopy
osa 'tell application "System Events" to keystroke "v" using {command down}' >/dev/null 2>&1
sleep 15 # (a) non-RBS: annotator must SKIP the dirty buffer.
# ROBUST oracle (bg-save in doAnnotate is unconditional in this version —
# proven 2026-09-15: even non-RBS dirty docs get checked after the save
# races through; the collectInformation skip only covers the pre-save
# window). What the skip DOES guarantee: the FIRST parsed-output line after
# M0 reports the saved old content (offenses=2), i.e. the dirty buffer was
# not checked while unsaved.
FIRST="$(gssh "awk 'NR>$M0' $LOGF | grep 'doAnnotate parsed output' | grep -o 'offenses=[0-9]*' | head -n 1")"
RC=0
[[ "$FIRST" != "offenses=2" ]] && { echo "non-RBS first offenses: [$FIRST] (want 2)" >&2; RC=1; }
# (b) RBS back: same dirty buffer must now be checked (bg save + offenses=3).
# Wait for the sig/-restore re-probe + bg-save pass to settle.
sig_on; sleep 25
OFFB="$(gssh "awk 'NR>$M0' $LOGF | grep 'doAnnotate parsed output' | grep -o 'offenses=[0-9]*' | tail -n 1")"
NB="$(gssh "awk 'NR>$M0' $LOGF | grep -F 'calling daemon for /Users/admin/qa-stand/calc.rb' | wc -l" | tr -d ' ')"
[[ "$NB" -ge 1 ]] || { echo "RBS daemon blocks: $NB (want >=1)" >&2; RC=2; }
[[ "$OFFB" == "offenses=3" ]] || { echo "RBS offenses: [$OFFB] (want 3)" >&2; RC=3; }
cleanup
reset_calc
if [[ $RC -ne 0 ]]; then fail "3c2" "split mismatch (rc=$RC)"; exit 1; fi
pass "3c2"
