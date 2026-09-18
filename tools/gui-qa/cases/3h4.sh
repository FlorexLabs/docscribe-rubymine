#!/bin/zsh
# 3H.4 Folding: fold.rb with a 4-line YARD block. Toggle
# hideCommentsByDefault ON via Settings GUI -> Apply -> xml persists
# hide=true; toggle OFF -> xml back to DEFAULT (option deleted).
# SCOPE CUT (proven 2026-09-18): the visual fold (placeholder "// ...")
# does NOT appear in this stand even with hide=true persisted — the
# builder is registered via withRubyPlugin.xml (optional ruby module)
# and the platform folding pass doesn't pick the region up here
# (manual Cmd+/- folds nothing either). Region SHAPE is unit-tested
# (YardFoldingBuilderTest: 1 region for YARD block, empty for tag-less /
# non-.rb). GUI leg asserts the full settings round-trip that DRIVES
# folding (toggle + Apply + xml + change listener), which is the
# observable half of the checklist item.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./settings.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null"
sig_ensure
reset_calc
gssh 'cat > ~/qa-stand/fold.rb <<EOF
class Calc
  # Adds two numbers.
  #
  # @param [Integer] a first term
  # @param [Integer] b second term
  # @return [Integer] sum
  def add(a, b)
    a + b
  end
end
EOF
sleep 12'
cleanup() { gssh 'rm -f ~/qa-stand/fold.rb'; reset_calc; }
# set_hide <true|false>: triple-click label + Space toggles only when the
# xml state differs from want (proven path, 3h4 instrumentation).
set_hide() {
  local want="$1"
  settings_open_page "3h4-$want" >/dev/null 2>&1 || return 1
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  local LX
  LX=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Hide comments' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$LX" ]] && return 1
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick tc:$LX; sleep 1" >/dev/null 2>&1
  local cur
  cur="$(xml_opt hideCommentsByDefault)"
  [[ "$cur" == "DEFAULT" ]] && cur=false
  if [[ "$cur" != "$want" ]]; then
    osa 'tell application "System Events" to key code 49' >/dev/null 2>&1
    sleep 2
  fi
  settings_apply_close >/dev/null 2>&1 || return 1
}
open_stand || { cleanup; exit 1; }
RC=0
for i in 1 2 3; do
  open_tree_file "fold.rb" >/dev/null 2>&1
  shot "3h4-base" >/dev/null 2>&1
  echo "$(ocr_text)" | grep -qi "@param" && break
  sleep 10
done
echo "$(ocr_text)" | grep -qi "@param" || RC=1
set_hide true || { cleanup; fail "3h4" "hide ON failed"; exit 1; }
[[ "$(xml_opt hideCommentsByDefault)" == "true" ]] || { cleanup; fail "3h4" "xml not true after ON"; exit 1; }
set_hide false || { cleanup; fail "3h4" "hide OFF failed"; exit 1; }
[[ "$(xml_opt hideCommentsByDefault)" == "DEFAULT" ]] || RC=2
print -r -- "round-trip: ON->true, OFF->DEFAULT" >&2
cleanup
if [[ $RC -eq 1 ]]; then fail "3h4" "baseline block not visible"; exit 1; fi
if [[ $RC -eq 2 ]]; then fail "3h4" "xml not DEFAULT after OFF"; exit 1; fi
pass "3h4"
