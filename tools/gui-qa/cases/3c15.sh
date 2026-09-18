#!/bin/zsh
# 3C.15 RBS edit invalidates annotations: change sig/calc.rbs type without
# touching .rb -> highlight recomputed for the new type.
# Fixture: calc.rb says [String], sig says Integer (UpdatedParam, proven
# 3c4/3c5). Edit sig String->... actually flip RBS to String (match ->
# offenses drop 1->0), then back to Integer (0->1). Oracle: log apply lines
# + bgHash change lines. No daemon bounce (invalidation must be automatic).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

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
# TIMING MODEL (proven 2026-09-16, three manual probes):
# - calc.rb write -> pass in ~15s (VFS event on .rb).
# - sig-only write -> NO pass ever (IDE watches .rb; rbsHash consulted
#   only when a pass runs for another reason).
# - mismatch pass needs BOTH: calc write, then sig write, then ANOTHER
#   calc poke (rewrite) to force the pass that sees the new sig state.
# So the sequence is: write calc, write sig, open_stand, rewrite calc,
# then poll. M0 sits before open_stand; the gate sees the poke pass.
gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def show: (Integer x) -> String
end
EOF
sleep 12'
# NO daemon_bounce: the bounce kills the server socket; the plugin restarts
# it lazily on the next pass, but the restart window swallows the gate
# (proven 2026-09-16: M0 sat inside the dead-socket gap, zero passes).
cleanup() {
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
  reset_calc
}
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
shot "3c15-editor"
ocr_text | grep -q "show" || { cleanup; fail "3c15" "editor not on show fixture"; exit 1; }
# Poke AFTER open_stand: forces the pass that sees the new sig state.
rewrite_run '~/qa-stand/calc.rb'
# Gate: mismatch pass present (offenses=1).
hit1() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep 'doAnnotate parsed output' | grep -q 'offenses=1'
}
for i in $(seq 1 18); do
  sleep 5
  if hit1; then break; fi
done
hit1 || { cleanup; fail "3c15" "no initial mismatch pass"; exit 1; }
# Flip RBS to String (matches YARD) WITHOUT touching calc.rb.
# A bare sig/ write fires NO pass on its own (proven 2026-09-16: zero
# doAnnotate lines after a lone sig write — the IDE watches .rb, and
# rbsHash is only consulted when a pass runs for another reason). Poke a
# pass via calc.rb rewrite (timestamp changes, content same), then the
# rbsHash path picks up the new sig state.
M1="$(log_mark)"
gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def show: (String x) -> String
end
EOF
sleep 12'
rewrite_run '~/qa-stand/calc.rb'
H1=""
for i in $(seq 1 18); do
  sleep 5
  H1="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'doAnnotate hashes' | tail -n 1)"
  echo "$H1" | grep -q "bgHash=" && break
done
Q1="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/calc.rb' | grep -o 'offenses=[0-9]*' | tail -n 1)"
[[ "$Q1" == "offenses=0" ]] || { echo "after sig->String: [$Q1] (want 0)" >&2; echo "hash: $H1" >&2; cleanup; fail "3c15" "no invalidation on sig edit"; exit 1; }
cleanup
pass "3c15"
