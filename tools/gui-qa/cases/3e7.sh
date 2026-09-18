#!/bin/zsh
# 3E.7 No collection lock: update works via --rbs without warnings;
# Doctor reports "not found" for the lock but overall OK.
# Stand has no rbs_collection.lock.yaml (verified); mismatch fixture +
# palette update -> success balloon; then palette Doctor -> balloon text
# must contain "rbs_collection.lock.yaml: not found" and "Status: OK".
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ut.sh || exit 2

gssh 'rm -f ~/qa-stand/rbs_collection.lock.yaml; ls ~/qa-stand/rbs_collection.lock.yaml 2>&1'
# NOTE: docscribe.yml sets rbs.collection=true, so with NO lock file the
# daemon errors ("rbs_collection.lock.yaml not found") and the annotator
# reports offenses=0 — the mismatch pass NEVER comes. Without collection
# mode the daemon falls back to --rbs and flags the mismatch. So disable
# collection for this case (restored in cleanup).
gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && sed -i "" "s/  collection: true/  collection: false/" ~/qa-stand/docscribe.yml && grep -A2 "^rbs:" ~/qa-stand/docscribe.yml'
open_stand || { cleanup; exit 1; }
M0="$(log_mark)"
mismatch_fixture
cleanup() {
  gssh 'mv ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null; cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
  reset_calc
}
open_stand || { cleanup; exit 1; }
hit_mm() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep 'doAnnotate parsed output' | grep -q 'offenses=[12]'
}
for i in $(seq 1 18); do
  sleep 5
  if hit_mm; then break; fi
done
hit_mm || { cleanup; fail "3e7" "no mismatch pass"; exit 1; }
open_tree_file "calc.rb" >/dev/null 2>&1
search_fire "update_types" "3e7-upd" || { cleanup; fail "3e7" "update fire failed"; exit 1; }
BU="$(ocr_balloon "3e7-upd")"
[[ -z "$BU" ]] && BU="$(ocr_text)"
echo "$BU" | grep -qi "types updated successfully" || { echo "--- update balloon ---" >&2; echo "$BU" >&2; cleanup; fail "3e7" "update balloon mismatch"; exit 1; }
# Doctor via palette (machine token ranks the action first — proven 3e4),
# then FULL report via Notifications tool window: the Doctor balloon is
# height-capped (~4 lines OCR-visible), the tool window holds the complete
# text (proven 2026-09-16: clipboard copy yields all 20 lines).
search_fire "doctor" "3e7-doc" || { cleanup; fail "3e7" "doctor fire failed"; exit 1; }
BD="$(doctor_report "3e7-rep")"
[[ -z "$BD" ]] && BD="$(ocr_balloon "3e7-doc")"
[[ -z "$BD" ]] && BD="$(ocr_text)"
RC=0
echo "$BD" | grep -qi "DocScribe Diagnostics" || RC=4
echo "$BD" | grep -qi "rbs_collection.lock.yaml: not found" || RC=1
echo "$BD" | grep -qi "Status: OK" || RC=2
if [[ $RC -ne 0 ]]; then echo "--- doctor balloon (rc=$RC) ---" >&2; echo "$BD" >&2; cleanup; fail "3e7" "doctor report mismatch"; exit 1; fi
cleanup
pass "3e7"
