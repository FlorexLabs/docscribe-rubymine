#!/bin/zsh
# 3D.7 trail.rb: trailing value examples after types do not suppress YARD
# warnings. Oracle: annotator apply line offenses>=1 (E2E via daemon).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'printf "class Lit\n  # @param [String []] x\n  # @param [Symbol :sym] y\n  # @param [Integer 42] z\n  def show(x, y, z)\n    x.to_s\n  end\nend\n" > ~/qa-stand/trail.rb; cat ~/qa-stand/trail.rb'
cleanup() { gssh 'rm -f ~/qa-stand/trail.rb'; reset_calc; }
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
open_tree_file "trail.rb" || { cleanup; fail "3d7" "no trail.rb in tree"; exit 1; }
hit() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/trail.rb' | grep -q 'offenses=[1-9]'
}
for i in $(seq 1 12); do
  sleep 5
  if hit; then break; fi
done
hit || { echo "--- log tail ---" >&2; rm_log_grep 'trail.rb' | tail -n 3 | cut -c1-160 >&2; cleanup; fail "3d7" "trail warnings suppressed"; exit 1; }
cleanup
pass "3d7"
