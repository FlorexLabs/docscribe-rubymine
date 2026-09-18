#!/bin/zsh
# 3B.8 Empty project (Gemfile + docscribe, no .rb) -> Check Entire Workspace
# -> INFORMATION "no Ruby files found in workspace".
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

gssh 'rm -rf ~/qa-empty && mkdir -p ~/qa-empty && printf "source \"https://rubygems.org\"\ngem \"docscribe\", path: \"/Users/admin/docscribe\"\n" > ~/qa-empty/Gemfile && cd ~/qa-empty && bundle install --quiet 2>&1 | tail -n 1; ls ~/qa-empty' || exit 2
# LAUNCH RULE (stand.sh): foreground `rubymine` CLI from non-interactive ssh
# HANGS (no GUI session) — the earlier probe wedged the VM for 4+ min.
# Fire-and-forget `&` returns at once; the IDE opens async, we poll.
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  'nohup $HOME/bin/rubymine "$HOME/qa-empty" >/dev/null 2>&1 &' >/dev/null 2>&1
# Poll for the Open Project dialog (up to ~90s), then click New Window.
OPD=0
for i in $(seq 1 18); do
  sleep 5
  activate >/dev/null 2>&1
  shot "3b8-open-dlg"
  if ocr_text | grep -qi "Open Project"; then OPD=1; break; fi
done
[[ $OPD -eq 0 ]] && { fail "3b8" "no Open Project dialog"; exit 1; }
  XY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  [[ -z "$XY" ]] && { fail "3b8" "no New Window button"; exit 1; }
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XY" >/dev/null 2>&1
  sleep 20
  activate
shot "3b8-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-empty" || { fail "3b8" "front window is not qa-empty"; exit 1; }
# Fire via Search Everywhere (no editor open in empty project).
search_type "Check Entire Workspace" || { fail "3b8" "search box won't open"; exit 1; }
sleep 2
shot "3b8-palette"
ocr_text | grep -qi "Check Entire Workspace" || { fail "3b8" "no action row"; exit 1; }
search_enter
shot "3b8"
B="$(ocr_text)"
echo "$B" | grep -qi "no Ruby files found in workspace" || { echo "--- b ---" >&2; echo "$B" >&2; fail "3b8" "balloon mismatch"; exit 1; }
# Back to the stand for the next cases.
open_stand_restart() {
  gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
  sleep 60
}
open_stand_restart
pass "3b8"
