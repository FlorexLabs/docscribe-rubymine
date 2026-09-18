#!/bin/zsh
# 3A.4 Visibility: notes.txt hides Check/Safe/Aggressive; task.rake and bare
# Rakefile show all 6. Oracle: submenu OCR item sets.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

vmsub() { # $1=guest path relative to $HOME (e.g. qa-stand/notes.txt) $2=tag
  unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
  local ip; ip="$(tart ip "$VM")"
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "\$HOME/bin/rubymine \"\$HOME/$1\" >/dev/null 2>&1 &" >/dev/null 2>&1
  sleep 18
  activate
  # editor anchor coords per file (logical px, verified 2026-09-15)
  local cx=491 cy=184
  case "$1" in
    *notes.txt) cx=463; cy=145;;
    *task.rake|*Rakefile) cx=452; cy=160;;
  esac
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick rc:$cx,$cy; sleep 3; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$2-rc.png" >/dev/null 2>&1
  local ds
  ds=$("$VOCR" "$SHOT_DIR/$2-rc.png" 2>/dev/null | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='DocScribe':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$ds" ]] && { echo "no DocScribe group for $1" >&2; return 2; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
    "cliclick m:${ds/,/,}; sleep 2; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
  scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/$2-sub.png" >/dev/null 2>&1
  "$VOCR" --text-only "$SHOT_DIR/$2-sub.png" 2>/dev/null | grep -i -E "Check Current File|Apply Safe Fixes|Apply Aggressive Fixes|Check Entire Workspace|Update Types from RBS|DocScribe Doctor"
}

gssh 'printf "hello\n" > ~/qa-stand/notes.txt' >/dev/null 2>&1

T="$(vmsub 'qa-stand/notes.txt' 3a4-txt)"; rc=$?
[[ $rc -eq 2 ]] && { fail "3a4" "txt: no group"; exit 1; }
echo "$T" | grep -q "Check Current File" && { echo "--- txt submenu ---" >&2; echo "$T" >&2; fail "3a4" "txt: Check visible"; exit 1; }
echo "$T" | grep -q "Apply Safe Fixes" && { fail "3a4" "txt: Safe visible"; exit 1; }
echo "$T" | grep -q "Check Entire Workspace" || { fail "3a4" "txt: Workspace missing"; exit 1; }

R="$(vmsub 'qa-stand/task.rake' 3a4-rake)"
for it in "Check Current File" "Apply Safe Fixes" "Apply Aggressive Fixes" "Check Entire Workspace" "Update Types from RBS" "DocScribe Doctor"; do
  echo "$R" | grep -q "$it" || { echo "--- rake submenu ---" >&2; echo "$R" >&2; fail "3a4" "rake: $it missing"; exit 1; }
done

F="$(vmsub 'qa-stand/Rakefile' 3a4-rf)"
for it in "Check Current File" "Apply Safe Fixes" "Apply Aggressive Fixes" "Check Entire Workspace" "Update Types from RBS" "DocScribe Doctor"; do
  echo "$F" | grep -q "$it" || { echo "--- Rakefile submenu ---" >&2; echo "$F" >&2; fail "3a4" "Rakefile: $it missing"; exit 1; }
done

pass "3a4"
