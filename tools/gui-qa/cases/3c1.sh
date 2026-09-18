#!/bin/zsh
# 3C.1 Annotator live: open calc.rb (2 undoc) -> daemon offenses=2 in log;
# then apply safe fix via menu, touch -> offenses=0. Proves yellow WARNING
# path end-to-end (squiggle pixels are not OCR-readable; log is the oracle).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

reset_calc
open_stand || exit 1
# NOTE: bare `touch` does NOT retrigger the annotator (VFS content stamp
# unchanged — proven 2026-09-15: touch_run produced no new apply line).
# Rewrite the file bytes instead (same content, new mtime+stamp).
M0="$(log_mark)"
gssh 'printf "%s\n" "$(cat ~/qa-stand/calc.rb)" > ~/qa-stand/calc.rb; sleep 12'
A1="$(offenses_for /Users/admin/qa-stand/calc.rb "$M0")"
echo "$A1" | grep -q "offenses=2" || { echo "--- apply ---" >&2; echo "$A1" >&2; fail "3c1" "want offenses=2"; exit 1; }
# Document them via safe fix, then re-check: 0. The annotator fires on the
# fix's own save+reload (async, ~10-20s) AND on our touch; poll up to 60s
# for the offenses=0 line instead of asserting the first post-touch line.
menu_fire "3c1" "Apply Safe Fixes" || { fail "3c1" "menu fire failed"; exit 1; }
A2=""
for i in $(seq 1 12); do
  sleep 5
  A2="$(offenses_for /Users/admin/qa-stand/calc.rb "$M0")"
  echo "$A2" | grep -q "offenses=0" && break
done
echo "$A2" | grep -q "offenses=0" || { echo "--- apply2 ---" >&2; echo "$A2" >&2; fail "3c1" "want offenses=0 after fix"; exit 1; }
reset_calc
pass "3c1"
