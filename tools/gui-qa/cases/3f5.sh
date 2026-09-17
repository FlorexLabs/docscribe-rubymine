#!/bin/zsh
# 3F.5 broken.rb in workspace: stand + unparseable `{{{` file (3c13-proven
# ProcessingError fixture). Check Entire Workspace must NOT abort: balloon
# shows checked-count + error(s) in summary; the run completes.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "rm -rf ~/qa-stand/spec 2>/dev/null; find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"
sig_ensure
reset_calc
gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 5'
gssh 'printf "\173\173\173\n" > ~/qa-stand/broken.rb; sleep 15'
cleanup() { gssh 'rm -f ~/qa-stand/broken.rb'; reset_calc; }
open_stand || { cleanup; exit 1; }
source ./ws.sh || exit 2

M0="$(log_mark)"
fire_workspace "3f5" || { cleanup; fail "3f5" "fire failed"; exit 1; }
# Balloon is width-capped: "...found, 1" + "errors)" wrap onto two OCR
# rows (proven 2026-09-17: full text "checked 7 file(s) - 3 issue(s)
# found, 1 errors)"). Match the tail across the whole shot text.
BT="$(ocr_text)"
RC=0
echo "$BT" | grep -qi "checked .* file(s)" || RC=1
{ echo "$BT" | grep -qi "error(s)"; } || { echo "$BT" | grep -qi "errors)"; } || RC=2
CF="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'collectRubyFiles collected=8 filtered=8' | tail -n 1)"
[[ -n "$CF" ]] || RC=4
print -r -- "balloon: [$(echo "$BT" | grep -i 'checked' | head -n 1)]" >&2
cleanup
if [[ $RC -eq 1 ]]; then echo "--- balloon ---" >&2; echo "$BT" >&2; fail "3f5" "no checked-count"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- balloon ---" >&2; echo "$BT" >&2; fail "3f5" "no error(s) in summary"; exit 1; fi
if [[ $RC -eq 4 ]]; then fail "3f5" "broken.rb not collected (8->8)"; exit 1; fi
pass "3f5"
