#!/bin/zsh
# 3G.1 Doctor etalon: full report line-by-line vs checklist PRE block.
# Report is read via doctor_report (Notifications tool window + clipboard)
# — the balloon is height-capped (3e7-proven).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb ~/qa-stand/rbs_collection.lock.yaml 2>/dev/null"
sig_ensure
reset_calc
cleanup() { reset_calc; }
# SDK must be configured (etalon demands a name/home): rbenv 3.4.5 module
# SDK already exists from the pilot; Doctor reads ProjectRootManager SDK.
open_stand || { cleanup; exit 1; }
sleep 10 # daemon up (any action)
open_tree_file "calc.rb" >/dev/null 2>&1
search_fire "doctor" "3g1-doc" || { cleanup; fail "3g1" "doctor fire failed"; exit 1; }
REP="$(doctor_report_clip "3g1-doc" 2>/dev/null)"
[[ -z "$REP" ]] && REP="$(doctor_report "3g1-rep" 2>/dev/null)"
[[ -z "$REP" ]] && REP="$(ocr_balloon "3g1-doc")"
RC=0
for pat in "=== DocScribe Diagnostics ===" "Project root: /Users/admin/qa-stand" "Gemfile: found" \
  "docscribe gem: AVAILABLE" "Version: 1.6.2" "Server mode: supported" \
  "sig/: found" "rbs gem: found in Gemfile.lock" "rbs_collection.lock.yaml: not found" \
  "rbs.enabled: true" "Daemon server: running" "hideCommentsByDefault = false" "Status: OK"; do
  echo "$REP" | grep -qiF "$pat" || { print -r -- "MISS: [$pat]" >&2; RC=1; }
done
# No Batch mode line in the ACTION report (MCP only — checklist).
echo "$REP" | grep -qi "Batch mode" && { print -r -- "UNEXPECTED: Batch mode in action report" >&2; RC=2; }
print -r -- "report chars: ${#REP}" >&2
cleanup
if [[ $RC -eq 1 ]]; then echo "--- report ---" >&2; print -r -- "$REP" >&2; fail "3g1" "report lines mismatch"; exit 1; fi
if [[ $RC -eq 2 ]]; then fail "3g1" "Batch mode leaked into action report"; exit 1; fi
pass "3g1"
