#!/bin/zsh
# 3B.3 Unsaved changes: append a method to the EDITOR buffer, fire Check
# Current File -> notification reflects the EDITOR text (3 methods).
# Oracles:
#  1. balloon prefix "checked 1 file" (counts overflow the viewport);
#  2. disk has `def mul` after (buffer reached disk via the action's
#     saveDocument — or the annotator's own; either way current text wins);
#  3. idea.log annotator block for calc.rb after the fire shows offenses=3
#     (2 old + 1 new) — proves the check saw the new method.
# Lessons (proven 2026-09-15):
# - Paste, don't type: per-keystroke typing trips keyword completion
#   (`b` -> `begin...end`) and takes >15s.
# - Click lands MID-word (OCR box center splits `end` -> `en`+`d`, smart
#   indent then makes `  d`). Always End (key 119) after click.
# - NO pre-fire "disk must lack method" assertion: the annotator's
#   saveDocument fires within seconds on ANY dirty buffer (unconditional
#   in this version), so such an oracle is inherently racy.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

reset_calc
open_stand || exit 1
sleep 8 # annotator settle
# N0 BEFORE the paste: the annotator may save+run on our dirty buffer before
# the action fires (its saveDocument is unconditional) — those blocks still
# prove the check saw the editor text. (Proven 2026-09-15: N0-after-paste
# missed the offenses=3 block because the action's save was a no-op.)
N0="$(gssh 'wc -l < ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log' | tr -d ' ')"
activate
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
shot "3b3-anchor"
EXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
cands = [o for o in d if o['text'].strip()=='end' and o['x']>700]
cands.sort(key=lambda o: o['y'])
# Second-to-last: the LAST end closes the class; the new method goes
# INSIDE (proven 2026-09-15: last-end anchor pasted at top level).
o = cands[-2] if len(cands) >= 2 else cands[-1]
print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$EXY" ]] && { fail "3b3" "no end anchor"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$EXY; sleep 1" >/dev/null 2>&1
osa 'tell application "System Events" to key code 53' >/dev/null 2>&1
sleep 0.5
osa 'tell application "System Events" to key code 119' >/dev/null 2>&1 # End: true EOL
sleep 0.5
osa 'tell application "System Events" to key code 36' >/dev/null 2>&1 # newline
sleep 0.5
print -r -- "  def mul(qq, ww)
    qq + ww
  end" | ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" pbcopy
osa 'tell application "System Events" to keystroke "v" using {command down}' >/dev/null 2>&1
sleep 3
menu_fire "3b3" "Check Current File" || { fail "3b3" "menu fire failed"; exit 1; }
ocr_text | grep -qi "checked 1 file" || { echo "--- b ---" >&2; ocr_text >&2; fail "3b3" "no check balloon"; exit 1; }
sleep 12 # save + annotator re-run settle
DISK="$(gssh 'cat ~/qa-stand/calc.rb')"
echo "$DISK" | grep -q "def mul(qq, ww)" || { echo "--- disk ---" >&2; echo "$DISK" >&2; fail "3b3" "method lost"; exit 1; }
echo "$DISK" | grep -qE "^  d$|begin" && { echo "--- disk ---" >&2; echo "$DISK" >&2; fail "3b3" "completion mangled text"; exit 1; }
OFF="$(gssh "awk 'NR>$N0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | grep 'doAnnotate parsed output' | grep -o 'offenses=[0-9]*' | tail -n 1")"
[[ "$OFF" == "offenses=3" ]] || { echo "log tail offenses: [$OFF]" >&2; fail "3b3" "check did not see 3 methods"; exit 1; }
pass "3b3"
