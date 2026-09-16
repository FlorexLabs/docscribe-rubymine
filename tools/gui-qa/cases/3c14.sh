#!/bin/zsh
# 3C.14 Settings toggle invalidates cache + refreshes folding, no restart.
# Oracle: xml warn=false->true roundtrip via settings_want (proven 3c7),
# and a forced pass after each Apply reports offenses (cache was cleared —
# a stale cache would serve the pre-toggle result; offenses differ only
# via the flag on the mismatch fixture badm.rb).
# badm.rb fixture (mismatch @return Integer vs String body) + sig off +
# yml validate key stripped (3c7-proven setup).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./settings.sh || exit 2

gssh 'printf "class Bad\n  # @param [Integer] x\n  # @return [Integer]\n  def show(x)\n    x.to_s\n  end\nend\n" > ~/qa-stand/badm.rb'
sig_off || exit 1
gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && sed -i "" "/^validate_types:/d" ~/qa-stand/docscribe.yml'
daemon_bounce
cleanup() {
  sig_ensure; gssh 'rm -f ~/qa-stand/badm.rb; mv ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null'
  reset_calc
}
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
settings_want true "3c14-pre" || { cleanup; fail "3c14" "pre not ON"; exit 1; }
M0="$(log_mark)"
open_tree_file "badm.rb" || { cleanup; fail "3c14" "no badm.rb in tree"; exit 1; }
# Toggle OFF via GUI (this is the invalidation event under test).
settings_want false "3c14-off" || { cleanup; fail "3c14" "toggle OFF failed"; exit 1; }
M1="$(log_mark)"
rewrite_run '~/qa-stand/badm.rb'
sleep 5
Q1="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -o 'offenses=[0-9]*' | tail -n 1")"
[[ "$Q1" == "offenses=0" ]] || { echo "after OFF toggle: [$Q1] (want 0 — cache must be gone)" >&2; cleanup; fail "3c14" "stale cache served"; exit 1; }
# Toggle ON again — new result without restart.
settings_want true "3c14-on" || { cleanup; fail "3c14" "toggle ON failed"; exit 1; }
M2="$(log_mark)"
rewrite_run '~/qa-stand/badm.rb'
sleep 5
for i in $(seq 1 12); do
  sleep 5
  if gssh "awk 'NR>$M2' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -q 'offenses=[1-9]'; then break; fi
done
gssh "awk 'NR>$M2' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -q 'offenses=[1-9]' || { cleanup; fail "3c14" "no recompute after ON"; exit 1; }
cleanup
pass "3c14"
