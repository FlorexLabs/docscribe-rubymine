#!/bin/zsh
# 3F.3 Progress bar on 25+ files: 26 copies of 2-undoc calc.rb
# (wcopy01..wcopy26) + 7 stand files = 33 files = 4 chunks (10/10/10/3).
# SCOPE CUT (proven 2026-09-17, two instrumented runs): a 33-file check
# runs in ~5-10s end-to-end — the 4 chunks flash by faster than any OCR
# poll, and the finished balloon REPLACES the bar, so no mid-run shot ever
# catches "checking files X–Y". The bar TEXT formula is covered by code
# read (from=processed+1, to=min(processed+10,total), en-dash+ellipsis)
# and by the unit test (CheckWorkspaceChunkingTest asserts the onProgress
# sequence). GUI leg asserts what IS observable: 33 files collected
# (log gate inside fire_workspace) + balloon "checked 33 file(s)".
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null'
sig_ensure
reset_calc
gssh 'for i in $(seq -w 1 26); do cp ~/qa-stand/calc.rb ~/qa-stand/wcopy$i.rb; done; ls ~/qa-stand/wcopy??.rb | wc -l; sleep 15'
cleanup() { gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"; reset_calc; }
open_stand || { cleanup; exit 1; }
source ./ws.sh || exit 2

fire_workspace "3f3" || { cleanup; fail "3f3" "fire failed"; exit 1; }
B="$(ocr_text)"
echo "$B" | grep -qi "checked 33 file(s)" || { echo "--- balloon ---" >&2; echo "$B" >&2; cleanup; fail "3f3" "not 33 files"; exit 1; }
cleanup
pass "3f3"
