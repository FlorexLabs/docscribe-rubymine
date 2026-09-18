#!/bin/zsh
# 3C.11 Add to Gemfile: click the balloon button -> \ngem "docscribe"\n
# appended to Gemfile ROOT (not group :development), Gemfile opened,
# INFORMATION confirmation. Then: no-Gemfile dir -> file CREATED with
# source line.
# NOTE: 3c10's balloon is long gone; rebuild the stand here (same pattern).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'rm -rf ~/qa-nogem3 && mkdir -p ~/qa-nogem3 && printf "source \"https://rubygems.org\"\ngem \"rake\"\n" > ~/qa-nogem3/Gemfile && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogem3/foo.rb && cd ~/qa-nogem3 && bundle install --quiet 2>&1 | tail -n 1' || exit 2
# Throttle note: lastGemBalloonShown is keyed by projectDir with a 15-min
# window (BALLOON_THROTTLE_MS). qa-nogem2's balloon fired in 3c10 <15 min
# ago, so a FRESH dir (qa-nogem3) guarantees a balloon here.
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
# Windows pile up across cases (3c10's nogem2 window is still here); the
# nogem2 CLI call may attach as a TAB (no dialog) or the dialog hides
# behind. Reuse-or-open: if a qa-nogem3 window already exists, bring it
# front instead of launching (no dialog expected); else launch + poll.
escape; sleep 2
WINNOW="$(osa 'tell application "System Events" to tell process "rubymine" to return name of every window' 2>/dev/null)"
if echo "$WINNOW" | grep -qi "qa-nogem3"; then
  activate || exit 1
  shot "3c11-reused"
else
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  'nohup $HOME/bin/rubymine "$HOME/qa-nogem3" >/dev/null 2>&1 &' >/dev/null 2>&1
OPD=0
for i in $(seq 1 18); do
  sleep 5
  activate >/dev/null 2>&1
  shot "3c11-open-dlg"
  if ocr_text | grep -qi "Open Project"; then OPD=1; break; fi
  # Attached as tab (no dialog)? Then the tree already shows qa-nogem3.
  if ocr_text | grep -qi "qa-nogem3"; then OPD=2; break; fi
done
[[ $OPD -eq 0 ]] && { fail "3c11" "no Open Project dialog"; exit 1; }
if [[ $OPD -eq 1 ]]; then
XY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$XY" ]] && { fail "3c11" "no New Window button"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$XY" >/dev/null 2>&1
sleep 20
activate
fi # end else (launch path); reuse path skips here
shot "3c11-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-nogem3" || { fail "3c11" "front window is not qa-nogem3"; exit 1; }
fi # end OPD==1 (New Window path)
# OPD==2 (attached tab) and reuse path converge here: tree -> foo.rb.
shot "3c11-tree"
TXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if 'foo.rb' in o['text'] and o['x'] < 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$TXY" ]] && { fail "3c11" "no foo.rb in tree"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$TXY" >/dev/null 2>&1
sleep 20
activate
# Find + click the "Add to Gemfile" balloon button.
shot "3c11-balloon"
BXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Add to Gemfile' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$BXY" ]] && { echo "--- b ---" >&2; ocr_text >&2; fail "3c11" "no Add button"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$BXY; sleep 6; screencapture -x /tmp/rm-qa.png" >/dev/null 2>&1
scp -o StrictHostKeyChecking=no admin@"$ip":/tmp/rm-qa.png "$SHOT_DIR/3c11-after.png" >/dev/null 2>&1
LAST_SHOT="$SHOT_DIR/3c11-after.png"
B2="$(ocr_text)"
RC=0
echo "$B2" | grep -qi "Added gem" || RC=1
G="$(gssh 'cat ~/qa-nogem3/Gemfile')"
echo "$G" | grep -q '^gem "docscribe"$' || RC=2
echo "$G" | grep -q "group :development" && RC=3
echo "$B2" | grep -qi "Gemfile" || RC=4
# Dismiss the stuck Open Project dialog from the wedged Part-2 attempt
# (foreground CLI hangs; dialog may linger). Escape is harmless otherwise.
escape
sleep 2
# Part 2: dir without any Gemfile -> created with source line.
# NOTE: foreground CLI from ssh HANGS (no GUI session, proven 3b8: 4+ min
# wedge). Fire-and-forget with & + poll for the dialog (3b8 pattern).
gssh 'rm -rf ~/qa-bare && mkdir -p ~/qa-bare && printf "def hi\nend\n" > ~/qa-bare/hi.rb; ls ~/qa-bare'
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" \
  'nohup $HOME/bin/rubymine "$HOME/qa-bare" >/dev/null 2>&1 &' >/dev/null 2>&1
OPD2=0
for i in $(seq 1 18); do
  sleep 5
  activate >/dev/null 2>&1
  shot "3c11-bare-dlg"
  if ocr_text | grep -qi "Open Project"; then OPD2=1; break; fi
done
[[ $OPD2 -eq 0 ]] && { echo "no bare Open Project dialog" >&2; RC=5; }
if [[ $OPD2 -eq 1 ]]; then
# NOTE: bare project opens in a NEW window (New Window button, like 3a7/3b8
# — This Window would kill the nogem2 evidence window mid-case).
NXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$NXY" ]] && { echo "no New Window button (bare)" >&2; RC=5; }
if [[ -n "$NXY" ]]; then
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$NXY" >/dev/null 2>&1
  sleep 20
  activate
fi
fi # end OPD2 branch
if [[ $RC -eq 0 ]]; then
shot "3c11-bare-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || true
  sleep 25
  activate
fi
shot "3c11-bare-tree"
HXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
rows = [o for o in d if 'hi.rb' in o['text'] and o['x'] < 600]
rows.sort(key=lambda o: o['y'])
if rows:
    o = rows[0]
    print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
")
[[ -z "$HXY" ]] && { echo "no hi.rb, continuing anyway" >&2; }
[[ -n "$HXY" ]] && ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$HXY" >/dev/null 2>&1
sleep 20
activate
shot "3c11-bare-balloon"
CXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if 'Add to Gemfile' in o['text']:
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$CXY" ]] && RC=5
if [[ -n "$CXY" ]]; then
  ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$CXY; sleep 6" >/dev/null 2>&1
  GB="$(gssh 'cat ~/qa-bare/Gemfile 2>/dev/null')"
  echo "$GB" | grep -q 'source "https://rubygems.org"' || RC=6
  echo "$GB" | grep -q '^gem "docscribe"$' || RC=7
fi
fi # end RC==0 branch (bare part runs only if part 1 clean)
# Back to the stand.
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -ne 0 ]]; then echo "rc=$RC" >&2; echo "--- after ---" >&2; echo "$B2" >&2; echo "--- Gemfile ---" >&2; echo "$G" >&2; echo "--- bare ---" >&2; echo "${GB:-<none>}" >&2; fail "3c11" "mismatch (rc=$RC)"; exit 1; fi
pass "3c11"
