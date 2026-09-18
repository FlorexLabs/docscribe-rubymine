#!/bin/zsh
# 3C.9 Literal YARD types are NOT filtered by the plugin — but gem 1.6.2
# emits ZERO offenses for [] / [:sym] / [42] (proven 2026-09-16 via CLI:
# plain, --validate-types and --rbs all give offense_count 0).
# So the observable expectation ("yellow highlights present") can NOT hold;
# the case asserts it (checklist demand) and records the finding.
# Supporting code evidence: no literal filter exists in the annotator.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'printf "class Lit\n  # @param [] x\n  # @param [:sym] y\n  # @param [42] z\n  def show(x, y, z)\n    x.to_s\n  end\nend\n" > ~/qa-stand/lit.rb'
cleanup() { gssh 'rm -f ~/qa-stand/lit.rb'; reset_calc; }
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
open_tree_file "lit.rb" || { cleanup; fail "3c9" "no lit.rb in tree"; exit 1; }
sleep 25 # let at least one full pass settle
A="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/lit.rb' | tail -n 1)"
cleanup
echo "$A" | grep -q "offenses=[1-9]" || { echo "--- apply ---" >&2; echo "${A:-<no pass>}" >&2; fail "3c9" "no warnings: gem emits 0 offenses for literal types (checklist drift)"; exit 1; }
pass "3c9"
