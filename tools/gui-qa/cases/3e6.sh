#!/bin/zsh
# 3E.6 Types visible immediately: after Update Types via lamp, the open
# editor shows the new types with NO manual refresh (VFS refresh + reload
# is done by the action/intention). Oracle: editor OCR contains Integer
# for the show method after the fire.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ut.sh || exit 2

M0="$(log_mark)"
mismatch_fixture
cleanup() {
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
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
hit_mm || { cleanup; fail "3e6" "no mismatch pass"; exit 1; }
update_via_intention "3e6" || { cleanup; fail "3e6" "intention fire failed"; exit 1; }
sleep 4 # refresh+reload settle (NOT a manual refresh — just settle time)
shot "3e6-editor"
E="$(ocr_text)"
echo "$E" | grep -q "Integer" || { echo "--- editor ---" >&2; echo "$E" >&2; cleanup; fail "3e6" "new types not visible"; exit 1; }
cleanup
pass "3e6"
