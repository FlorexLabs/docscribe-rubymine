#!/bin/zsh
# 3B.6 Intentions: cursor on warning line, Alt+Enter -> rows "DocScribe:
# Apply safe fix / Apply aggressive fix / Check current file"
# (familyName=DocScribe). Oracle: intention popup OCR (code asserts
# familyName in framework tests; here visible rows).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

reset_calc
open_stand || exit 1
sleep 10 # annotator settle: warnings must exist before Alt+Enter
activate
# Option+Enter (Alt+Enter): key code 36 with option modifier
osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1
sleep 4
shot "3b6-intention"
IT="$(ocr_text)"
MISS=0
for row in "Apply safe fix" "Apply aggressive fix" "Check current file"; do
  echo "$IT" | grep -qi "$row" || { echo "missing row: $row" >&2; MISS=1; }
done
if [[ $MISS -ne 0 ]]; then echo "--- intention ---" >&2; echo "$IT" >&2; fail "3b6" "rows missing"; exit 1; fi
echo "$IT" | grep -qi "DocScribe" || { echo "--- intention ---" >&2; echo "$IT" >&2; fail "3b6" "no DocScribe group"; exit 1; }
pass "3b6"
