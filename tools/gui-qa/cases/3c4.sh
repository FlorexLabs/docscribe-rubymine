#!/bin/zsh
# 3C.4 RBS type line: YARD says String, sig/calc.rbs says Integer ->
# daemon reports UpdatedParam (source=rbs); annotator underlines the YARD
# comment line (findYardTagLine), not the def.
# NOTE (2026-09-15): checklist example uses @param [Object], but gem's
# GenericCompatibility treats Object as top type (compatible with Integer,
# 0 offenses — proven via CLI). String vs Integer triggers the path with
# message "updated @param x from String to Integer" (wording drift vs
# checklist's «Incorrect type», same intent: wrong type, not Missing).
# Oracles: daemon JSON via CLI (cop/source/message) + log "apply" line.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

# NOTE: heredoc terminators MUST be `EOF'` (quote closes on terminator
# line, like reset_calc). A bare `EOF` leaves the quote open: two gssh
# heredocs glue together and the redirect lands on the HOST (proven
# 2026-09-15: "no such file /Users/pearl/qa-stand/..." x2).
gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  # @param [String] x
  # @return [String]
  def show(x)
    x.to_s
  end
end
EOF'
gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def show: (Integer x) -> String
end
EOF
cat ~/qa-stand/sig/calc.rbs'
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
open_stand || { cleanup; exit 1; }
# Daemon JSON oracle via CLI (same input the daemon sees; needs both flags).
J="$(gssh 'cd ~/qa-stand && bundle exec docscribe --format json --rbs --validate-types calc.rb 2>/dev/null')"
echo "$J" | grep -q "UpdatedParam" || { echo "--- json ---" >&2; echo "$J" >&2; cleanup; fail "3c4" "no UpdatedParam"; exit 1; }
echo "$J" | grep -q '"source":"rbs"' || { echo "--- json ---" >&2; echo "$J" >&2; cleanup; fail "3c4" "no rbs source"; exit 1; }
echo "$J" | grep -q "from String to Integer" || { echo "--- json ---" >&2; echo "$J" >&2; cleanup; fail "3c4" "no type-mismatch text"; exit 1; }
# Annotator oracle: apply line carries the offense (squiggle target = YARD
# line by findYardTagLine — framework-tested; GUI proves the pass ran).
M0="$(log_mark)"
rewrite_run '~/qa-stand/calc.rb'
A="$(offenses_for /Users/admin/qa-stand/calc.rb "$M0")"
echo "$A" | grep -q "offenses=1" || { echo "--- apply ---" >&2; echo "$A" >&2; cleanup; fail "3c4" "want offenses=1"; exit 1; }
cleanup
pass "3c4"
