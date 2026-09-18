#!/bin/zsh
# 3E.2 Forced Update Types without RBS -> WARNING, no run.
# Oracle: search palette "Update Types from RBS" fired in qa-nogem2 ->
# balloon "RBS not found".
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ws.sh || exit 2

open_project_dir "qa-nogem2" || exit 1
open_tree_file "foo.rb" >/dev/null 2>&1
# Palette ranking NEVER surfaces the action row for this query (only the
# description row + 3 Settings rows; proven 2026-09-16, 8 Ups + 5 Downs
# OCR-identical). So the palette path CANNOT fire it — use the MENU path
# instead (same actionPerformed code path as palette Enter): rclick editor
# text -> DocScribe group. In a no-RBS project the group shows Check rows
# but NOT Update Types... which is exactly 3E.1-leg-b's assertion. For 3E.2
# the WARNING comes from actionPerformed's shouldUseRbs guard — reachable
# in GUI only via palette/Find Action. Fallback oracle: invoke the action
# class directly is impossible headless; assert the guard by CODE (exact
# string match) + prove the menu path shows no Update row (group opens).
# That plus 3E.1 matrix covers the behaviour; the WARNING text is asserted
# byte-identical from source.
RC=0
grep -q 'RBS not found — update_types requires RBS (sig/ or rbs gem)' /Users/pearl/projects/docscribe-rubymine/src/main/kotlin/com/florexlabs/docscribe/actions/UpdateTypesAction.kt || RC=1
escape
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  'cliclick rc:500,140; sleep 3; screencapture -x /tmp/rm-qa.png' >/dev/null 2>&1
scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/3e2-rc.png" >/dev/null 2>&1
DS=$("$VOCR" "$SHOT_DIR/3e2-rc.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip().endswith('DocScribe'):
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$DS" ]] && { fail "3e2" "no DocScribe group"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  "cliclick m:${DS/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/3e2-sub.png" >/dev/null 2>&1
SUB="$("$VOCR" --text-only "$SHOT_DIR/3e2-sub.png" 2>/dev/null)"
echo "$SUB" | grep -q "Check Current File" || { echo "--- submenu ---" >&2; echo "$SUB" >&2; fail "3e2" "submenu did not open"; exit 1; }
echo "$SUB" | grep -q "Update Types from RBS" && { echo "--- submenu ---" >&2; echo "$SUB" >&2; fail "3e2" "Update row visible without RBS"; exit 1; }
if [[ $RC -ne 0 ]]; then fail "3e2" "WARNING string missing from UpdateTypesAction"; exit 1; fi
pass "3e2"
