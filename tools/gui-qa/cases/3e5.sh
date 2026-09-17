#!/bin/zsh
# 3E.5 Exit-code discipline: (a) clean run -> INFORMATION "types updated
# successfully" ONLY on exitCode==0; (b) broken Gemfile -> WARNING
# "update_types failed: ..." (no special 1==success; daemon-RPC success is
# always exit 0 — code read).
# (a) reuses the mismatch fixture + palette fire; (b) reuses the 3b2
# break-Gemfile + IDE-restart pattern, firing update_types via palette.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ut.sh || exit 2

# --- (a) success path ---
M0="$(log_mark)"
mismatch_fixture
cleanup_a() {
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
  reset_calc
}
open_stand || { cleanup_a; exit 1; }
hit_mm() {
  gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep 'doAnnotate parsed output' | grep -q 'offenses=[12]'
}
for i in $(seq 1 18); do
  sleep 5
  if hit_mm; then break; fi
done
hit_mm || { cleanup_a; fail "3e5" "no mismatch pass (a)"; exit 1; }
open_tree_file "calc.rb" >/dev/null 2>&1
search_fire "update_types" "3e5-ok" || { cleanup_a; fail "3e5" "palette fire failed (a)"; exit 1; }
B0="$(ocr_balloon "3e5-ok")"
[[ -z "$B0" ]] && B0="$(ocr_text)"
echo "$B0" | grep -qi "types updated successfully" || { echo "--- b(a) ---" >&2; echo "$B0" >&2; cleanup_a; fail "3e5" "success balloon mismatch"; exit 1; }
cleanup_a

# --- (b) broken Gemfile -> WARNING ---
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", "9.9.9"
gem "rbs"
EOF
cd ~/qa-stand && bundle install 2>&1 | tail -n 1'
mismatch_fixture
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
open_stand || { fail "3e5" "reopen failed (b)"; exit 1; }
open_tree_file "calc.rb" >/dev/null 2>&1
search_fire "update_types" "3e5-bad" || { fail "3e5" "palette fire failed (b)"; exit 1; }
B1="$(ocr_balloon "3e5-bad")"
[[ -z "$B1" ]] && B1="$(ocr_text)"
RC=0
echo "$B1" | grep -qi "update_types failed" || RC=1
# Restore + restart (next cases need green daemon).
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", path: "/Users/admin/docscribe"
gem "rbs"
EOF
cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1' >/dev/null 2>&1
gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
reset_calc
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -ne 0 ]]; then echo "--- b(b) ---" >&2; echo "$B1" >&2; fail "3e5" "failure balloon mismatch"; exit 1; fi
pass "3e5"
