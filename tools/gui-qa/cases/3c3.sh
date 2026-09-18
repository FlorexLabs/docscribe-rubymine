#!/bin/zsh
# 3C.3 Generation guard: rapid content changes -> stale results dropped, last
# applied. Checklist trigger is triple Cmd+S; equivalent observable trigger
# is 3 content appends spaced 6s (each = new modificationStamp = new pass;
# back-to-back rewrites collapse into one VFS event, proven 2026-09-15).
# Oracle: >=2 starts after mark (1 pre-existing IDE pass may interleave),
# every start pairs with returning/apply (no wedged pass), last offenses=2,
# IDE alive (case completes), file restored.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

reset_calc
open_stand || exit 1
sleep 8
M0="$(log_mark)"
gssh 'for i in 1 2 3; do echo "# gen $i" >> ~/qa-stand/calc.rb; sleep 6; done; sleep 15'
N="$(gssh "awk 'NR>$M0' $LOGF | grep -F 'doAnnotate start file=/Users/admin/qa-stand/calc.rb' | wc -l" | tr -d ' ')"
RET="$(gssh "awk 'NR>$M0' $LOGF | grep -F 'DocScribe apply file=/Users/admin/qa-stand/calc.rb' | wc -l" | tr -d ' ')"
LAST="$(gssh "awk 'NR>$M0' $LOGF | grep 'doAnnotate parsed output' | grep -o 'offenses=[0-9]*' | tail -n 1")"
reset_calc
[[ "$N" -ge 2 ]] || { echo "starts: $N (want >=2)" >&2; fail "3c3" "no rapid passes"; exit 1; }
[[ "$RET" -ge "$N" ]] || { echo "starts=$N apply=$RET (wedged pass?)" >&2; fail "3c3" "pass wedged"; exit 1; }
[[ "$LAST" == "offenses=2" ]] || { echo "last: [$LAST] (want 2)" >&2; fail "3c3" "final mismatch"; exit 1; }
pass "3c3"
