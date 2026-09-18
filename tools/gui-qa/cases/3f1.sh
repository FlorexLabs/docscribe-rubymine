#!/bin/zsh
# 3F.1 Workspace counters: WARNING "checked 7 file(s) — 3 issue(s) found"
# on the dirty stand; INFORMATION "checked 7 file(s) — OK" on the fully
# documented stand. Oracles: balloon OCR + collectRubyFiles log line
# (collected=7 filtered=7 — the only RBS/file log oracle, 3D-proven).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

DOC_CALC='# Adds two numbers.
  #
  # @param [Integer] a First number.
  # @param [Integer] b Second number.
  # @return [Integer] Sum.'
gssh "rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null; find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"
sig_ensure
reset_calc
gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 12'
cleanup() {
  reset_calc
  gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 5'
}
open_stand || { cleanup; exit 1; }
source ./ws.sh || exit 2

# --- Leg A: dirty stand -> WARNING with counters ---
M0="$(log_mark)"
fire_workspace "3f1-warn" || { cleanup; fail "3f1" "fire failed (warn leg)"; exit 1; }
BW="$(ocr_text)"
RC=0
echo "$BW" | grep -qi "checked 7 file(s)" || RC=1
echo "$BW" | grep -qi "3 issue(s) found" || RC=2
CF="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'collectRubyFiles collected=7 filtered=7' | tail -n 1)"
[[ -n "$CF" ]] || RC=4
if [[ $RC -ne 0 ]]; then echo "--- warn balloon (rc=$RC) ---" >&2; echo "$BW" >&2; echo "collect: [$CF]" >&2; cleanup; fail "3f1" "warning counters mismatch"; exit 1; fi

# --- Leg B: fully documented stand -> INFORMATION OK ---
gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  # Adds two numbers.
  #
  # @param [Integer] a First number.
  # @param [Integer] b Second number.
  # @return [Integer] Sum.
  def add(a, b)
    a + b
  end

  # Subtracts two numbers.
  #
  # @param [Integer] a First number.
  # @param [Integer] b Second number.
  # @return [Integer] Difference.
  def sub(a, b)
    a - b
  end
end
EOF
sleep 12'
gssh 'cat > ~/qa-stand/partial.rb <<EOF
class Gadget
  # Returns the value.
  #
  # @param [Object] a Any value.
  # @return [Object] The value.
  def foo(a)
    a
  end
end
EOF
sleep 12'
M1="$(log_mark)"
fire_workspace "3f1-ok" || { cleanup; fail "3f1" "fire failed (ok leg)"; exit 1; }
BO="$(ocr_text)"
RC=0
echo "$BO" | grep -qi "checked 7 file(s)" || RC=1
echo "$BO" | grep -qi "OK" || RC=2
echo "$BO" | grep -qi "issue(s) found" && RC=3
cleanup
if [[ $RC -eq 1 ]]; then echo "--- ok balloon ---" >&2; echo "$BO" >&2; fail "3f1" "ok file count mismatch"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- ok balloon ---" >&2; echo "$BO" >&2; fail "3f1" "no OK suffix"; exit 1; fi
if [[ $RC -eq 3 ]]; then echo "--- ok balloon ---" >&2; echo "$BO" >&2; fail "3f1" "clean stand reports issues"; exit 1; fi
pass "3f1"
