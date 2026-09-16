#!/bin/zsh
# 3E.3 Intention update is FILE-scoped: only calc.rb changes on disk.
# Fixture: calc.rb String/String + sig Integer (mismatch). Fire via lamp
# (proven 3c5 path: click show, Up x2, Alt+Enter, index, Enter).
# Oracles: md5 snapshot of ALL stand files (only calc.rb differs) +
# intention balloon "types updated from RBS" via select+copy.
# NOTE: checklist says daemon {dir,file} / CLI `update_types <file>` —
# params are not logged (3d4 finding); file-scope is proven by the
# disk snapshot, and by code (UpdateTypesIntention passes vFile.path;
# UpdateTypesAction passes targetFile — never root-only).
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
# Gate: mismatch pass present (offenses=1), like 3c5.
hit_mm() {
  gssh 'awk "NR>$M0" ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log' | grep 'doAnnotate parsed output' | grep -q 'offenses=1'
}
for i in $(seq 1 18); do
  sleep 5
  if hit_mm; then break; fi
done
hit_mm || { cleanup; fail "3e3" "no mismatch pass"; exit 1; }
BEFORE="$(stand_md5)"
update_via_intention "3e3" || { cleanup; fail "3e3" "intention fire failed"; exit 1; }
BT="${BALLOON_TEXT:-$(ocr_text)}"
echo "$BT" | grep -qi "types updated from RBS" || { echo "--- balloon ---" >&2; echo "$BT" >&2; cleanup; fail "3e3" "balloon mismatch"; exit 1; }
sleep 6 # VFS refresh + reload settle
AFTER="$(stand_md5)"
DIFFN="$(python3 -c "
b = '''$BEFORE'''.split()
a = '''$AFTER'''.split()
print(sum(1 for x, y in zip(b, a) if x != y))")"
[[ "$DIFFN" == "1" ]] || { echo "changed files: $DIFFN (want 1)" >&2; cleanup; fail "3e3" "not file-scoped"; exit 1; }
# The one changed file must be calc.rb (first hash in stand_md5 order).
B0="$(echo "$BEFORE" | head -n 1)"
A0="$(echo "$AFTER" | head -n 1)"
[[ "$B0" != "$A0" ]] || { cleanup; fail "3e3" "calc.rb unchanged"; exit 1; }
cleanup
pass "3e3"
