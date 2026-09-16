#!/bin/zsh
# 3B.4 Safe fix: menu Apply Safe Fixes -> INFORMATION "safe fix applied",
# file gains YARD comments. Oracle: balloon OCR + file content.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

reset_calc
open_stand || exit 1
menu_fire "3b4" "Apply Safe Fixes" || { fail "3b4" "menu fire failed"; exit 1; }
ocr_text | grep -qi "safe fix applied" || { echo "--- b ---" >&2; ocr_text >&2; fail "3b4" "balloon mismatch"; exit 1; }
sleep 6 # VFS refresh + reload settle
FC="$(gssh 'cat ~/qa-stand/calc.rb')"
echo "$FC" | grep -q "@param" || { echo "--- calc.rb ---" >&2; echo "$FC" >&2; fail "3b4" "no YARD added"; exit 1; }
pass "3b4"
