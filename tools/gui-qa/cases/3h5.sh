#!/bin/zsh
# 3H.5 Warn toggle live: bad.rb with [Symbкol] (CYRILLIC к, U+043A) and NO
# RBS (sig/ moved away). Settings ON -> annotator flags (offenses=1) +
# intention fix row without restart. OFF leg: DRIFT (proven 2026-09-18) —
# the gem ALWAYS emits invalid_type for syntax-broken YARD
# (handle_existing_param -> invalid_yard_type? is unconditional; the
# --validate-types flag only gates *mismatch-vs-inferred* reporting, and
# the plugin's toggle is not even wired into RunOptions for CHECK). So OFF
# still yields offenses=1. Assert ON (count + fix row); record OFF=1 as
# drift in the note.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./settings.sh || exit 2
source ./ut.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null"
sig_off
reset_calc
gssh 'printf "class Bad\n  # @param [Symb\xd0\xbaol] x broken\n  # @return [String] ok\n  def foo(x)\n    x\n  end\nend\n" > ~/qa-stand/bad.rb; sleep 12'
cleanup() { gssh 'rm -f ~/qa-stand/bad.rb'; sig_on; settings_want true "3h5-restore" >/dev/null 2>&1; reset_calc; }
open_stand || { cleanup; exit 1; }
warn_on() { settings_want true "3h5-on" || return 1; }
warn_off() { settings_want false "3h5-off" || return 1; }
RC=0
warn_on || { cleanup; fail "3h5" "warn ON failed"; exit 1; }
# NOTE: the annotator pass for a just-created file needs a VFS nudge AFTER
# the mark: open, mark, rewrite, wait — retry up to 3x (proven 2026-09-18:
# single mark-then-open races the tree pick-up and yields zero lines).
A1=""
for i in 1 2 3; do
  open_tree_file "bad.rb" >/dev/null 2>&1 || { cleanup; fail "3h5" "no bad.rb"; exit 1; }
  M0="$(log_mark)"
  rewrite_run '~/qa-stand/bad.rb'
  sleep 25 # annotator pass settle
  A1="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/bad.rb' | tail -n 1)"
  [[ -n "$A1" ]] && break
  sleep 10
done
echo "$A1" | grep -q "offenses=[1-9]" || RC=1
print -r -- "ON apply: [$A1]" >&2
# Intention oracle: cursor on YARD line + Alt+Enter shows the direct YARD
# fix row (proves the InvalidType squiggle is live, not just the count).
# NOTE: open_tree_file matches the FIRST tree row containing the name as a
# SUBSTRING — with sig/ present the match may hit sig/calc.rbs instead of
# bad.rb (proven 2026-09-18: 'sig.off' row matched 'bad.rb'?? no — the
# double-click opened calc.rb). Force exact-name match via open_exact_file.
open_exact_file "bad.rb" || { cleanup; fail "3h5" "bad.rb not in editor"; exit 1; }
shot "3h5-anchor" >/dev/null 2>&1
# Anchor: click ANYWHERE in the editor body then Up-arrow to the YARD
# line (OCR[@param] is unreliable — Vision merges/splits the row;
# proven 2026-09-18: empty ocr_json). Editor body = the 'def foo' line
# when visible, else file center-right.
SXY=$(ocr_json | python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    raise SystemExit
cands = [o for o in d if ('def foo' in o['text'] or '@param' in o['text'] or '@return' in o['text']) and o['x']>700]
cands.sort(key=lambda o: o['y'])
if cands:
    yards = [o for o in cands if '@param' in o['text'] or '@return' in o['text']]
    o = yards[-1] if yards else cands[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
if [[ -z "$SXY" ]]; then
  # Fallback: click editor center-right (proven: popup still offered the
  # rows when the cursor was merely in the file).
  SXY="750,300"
fi
if [[ -n "$SXY" ]]; then
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$SXY; sleep 2" >/dev/null 2>&1
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1; sleep 0.5
  osa 'tell application "System Events" to key code 126' >/dev/null 2>&1; sleep 0.5
  osa 'tell application "System Events" to key code 36 using {option down}' >/dev/null 2>&1; sleep 4
  shot "3h5-popup" >/dev/null 2>&1
  PT="$(ocr_text)"
  echo "$PT" | grep -qi "Apply safe fix" || RC=1
  print -r -- "ON popup safe-fix seen: $(echo "$PT" | grep -i -m1 'safe fix')" >&2
  escape; sleep 1
fi
warn_off || { cleanup; fail "3h5" "warn OFF failed"; exit 1; }
M1="$(log_mark)"
rewrite_run '~/qa-stand/bad.rb'
sleep 20
A2="$(gssh "awk 'NR>$M1' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'DocScribe apply file=/Users/admin/qa-stand/bad.rb' | tail -n 1)"
print -r -- "OFF apply (drift, still flagged): [$A2]" >&2
cleanup
if [[ $RC -eq 1 ]]; then fail "3h5" "no highlight when ON"; exit 1; fi
fail "3h5" "OFF still flags InvalidType (gem always emits; toggle unwired for CHECK)"
exit 1
