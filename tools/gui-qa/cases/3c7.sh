#!/bin/zsh
# 3C.7 warnOnInvalidYardTypes gate: what the checkbox REALLY controls.
# FINDINGS (proven 2026-09-16, gem 1.6.2 — checklist text drifts, see note):
# - YARD *syntax* errors ([Symbкol], [Symbo2l], [Array<]) are DEFAULT gem
#   behavior (InvalidType with no flags, no yml key, ON or OFF). The
#   checkbox can NEVER silence them; "OFF → тихо" is unachievable.
# - The flag gates *mismatch* detection (UpdatedParam/UpdatedReturn):
#   `@return [Integer]` + `x.to_s` body, no RBS -> 0 offenses without the
#   flag, 1 (UpdatedReturn) with it.
# Fixture: badm.rb (valid types, mismatched return), sig/ off, yml
# validate_types key stripped (yml:true would mask OFF).
# Oracles: log apply offenses 1 (ON) -> 0 (OFF) -> 1 (ON); xml true/false.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./settings.sh || exit 2

gssh 'printf "class Bad\n  # @param [Integer] x\n  # @return [Integer]\n  def show(x)\n    x.to_s\n  end\nend\n" > ~/qa-stand/badm.rb'
sig_off || exit 1
gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && sed -i "" "/^validate_types:/d" ~/qa-stand/docscribe.yml && cat ~/qa-stand/docscribe.yml'
# Daemon reads yml ONCE at server start into its base config; overrides only
# ADD keys (never send validate_types:false). Without a bounce the stale
# base (validate:true) masks OFF (proven 2026-09-16: offenses=1 after OFF).
# Bounce AFTER the strip so the fresh base has no key; toggles then work
# purely via the override map ({validate:true} vs {rbs:true}).
daemon_bounce
cleanup() {
  sig_ensure; gssh 'rm -f ~/qa-stand/badm.rb; mv ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null'
  reset_calc
}
M0="$(log_mark)"
open_stand || { cleanup; exit 1; }
# Normalize FIRST: xml may carry false from an aborted run, and with the
# stripped yml + fresh daemon that means 0 offenses at the gate below
# (proven 2026-09-16: gate timed out on leftover false).
settings_want true "3c7-pre" || { cleanup; fail "3c7" "pre not ON"; exit 1; }
M0="$(log_mark)"
open_tree_file "badm.rb" || { cleanup; fail "3c7" "no badm.rb in tree"; exit 1; }
hit_bad() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -q 'offenses=[1-9]'
}
for i in $(seq 1 12); do
  sleep 5
  if hit_bad; then break; fi
done
hit_bad || { cleanup; fail "3c7" "no mismatch pass (ON)"; exit 1; }

# OFF: mismatch detection stops. (No second PRE normalize needed — the
# pre above already drove state to ON.)
settings_want false "3c7-off" || { cleanup; fail "3c7" "toggle OFF failed"; exit 1; }
M1="$(log_mark)"
# Apply clears the cache (settingsChanged) but does NOT re-run passes by
# itself (proven 2026-09-15: zero lines in 20s+). Force one via rewrite.
rewrite_run '~/qa-stand/badm.rb'
sleep 5
Q1="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -o 'offenses=[0-9]*' | tail -n 1)"
[[ "$Q1" == "offenses=0" ]] || { echo "off after OFF: [$Q1] (want 0)" >&2; cleanup; fail "3c7" "mismatch not silenced when OFF"; exit 1; }

# ON again: mismatch returns.
settings_want true "3c7-on" || { cleanup; fail "3c7" "toggle ON failed"; exit 1; }
M2="$(log_mark)"
rewrite_run '~/qa-stand/badm.rb'
sleep 5
for i in $(seq 1 12); do
  sleep 5
  if gssh "awk 'NR>$M2' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -q 'offenses=[1-9]'; then break; fi
done
gssh "awk 'NR>$M2' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/badm.rb' | grep -q 'offenses=[1-9]' || { cleanup; fail "3c7" "no mismatch when ON again"; exit 1; }
cleanup
pass "3c7"
