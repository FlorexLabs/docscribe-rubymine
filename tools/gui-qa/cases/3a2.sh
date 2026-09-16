#!/bin/zsh
# 3A.2 EditorPopupMenu order: Check, Safe, Aggressive, sep, Workspace,
# Update Types, sep, Doctor. Oracle: submenu OCR y-order.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

gssh '~/bin/rubymine --line 2 --column 5 ~/qa-stand/calc.rb >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 20
activate
rclick_editor() {
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$(tart ip "$VM")" \
    'cliclick rc:491,184; sleep 3; screencapture -x /tmp/rm-qa.png' >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$(tart ip "$VM")":/tmp/rm-qa.png "$SHOT_DIR/3a2-rc.png" >/dev/null 2>&1
  LAST_SHOT="$SHOT_DIR/3a2-rc.png"
}
rclick_editor
DS="$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='DocScribe':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")"
[[ -z "$DS" ]] && { fail "3a2" "no DocScribe group"; exit 1; }
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$(tart ip "$VM")" \
  "cliclick m:${DS/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
scp -o StrictHostKeyChecking=no admin@"$(tart ip "$VM")":/tmp/rm-qa.png "$SHOT_DIR/3a2-sub.png" >/dev/null 2>&1
ORDER="$("$VOCR" "$SHOT_DIR/3a2-sub.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
want=['Check Current File','Apply Safe Fixes','Apply Aggressive Fixes','Check Entire Workspace','Update Types from RBS','DocScribe Doctor']
ys={}
for w in want:
    for o in d:
        if w.lower() in o['text'].lower():
            ys[w]=o['y']; break
if len(ys)!=6:
    print('MISSING:'+','.join(w for w in want if w not in ys)); sys.exit(1)
seq=sorted(ys,key=ys.get)
print('|'.join(seq))
")"
EXP="Check Current File|Apply Safe Fixes|Apply Aggressive Fixes|Check Entire Workspace|Update Types from RBS|DocScribe Doctor"
if [[ "$ORDER" == "$EXP" ]]; then pass "3a2"; else echo "got: $ORDER" >&2; fail "3a2" "menu order mismatch"; exit 1; fi
