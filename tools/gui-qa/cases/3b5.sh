#!/bin/zsh
# 3B.5 Aggressive fix: menu Apply Aggressive Fixes -> INFORMATION
# "aggressive fix applied", file gains docs. Oracle: balloon + file.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

reset_calc
open_stand || exit 1
menu_fire "3b5" "Apply Aggressive Fixes" || { fail "3b5" "menu fire failed"; exit 1; }
ocr_text | grep -qi "aggressive fix applied" || { echo "--- b ---" >&2; ocr_text >&2; fail "3b5" "balloon mismatch"; exit 1; }
sleep 6
FC="$(gssh 'cat ~/qa-stand/calc.rb')"
echo "$FC" | grep -q "@param" || { echo "--- calc.rb ---" >&2; echo "$FC" >&2; fail "3b5" "no YARD added"; exit 1; }
pass "3b5"
