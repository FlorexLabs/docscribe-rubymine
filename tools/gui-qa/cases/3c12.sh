#!/bin/zsh
# 3C.12 Throttle: reopen .rb files in the missing-gem project -> NO second
# balloon within 15 min (BALLOON_THROTTLE_MS=900000, keyed by projectDir).
# Uses a FRESH dir (qa-nogem4) so the first balloon is guaranteed, then
# asserts absence on repeat opens.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'rm -rf ~/qa-nogem4 && mkdir -p ~/qa-nogem4 && printf "source \"https://rubygems.org\"\ngem \"rake\"\n" > ~/qa-nogem4/Gemfile && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogem4/foo.rb && printf "def bye\nend\n" > ~/qa-nogem4/bar.rb && cd ~/qa-nogem4 && bundle install --quiet 2>&1 | tail -n 1; ls ~/qa-nogem4' || exit 2
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
ip="$(tart ip "$VM")"
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" 'nohup $HOME/bin/rubymine "$HOME/qa-nogem4" >/dev/null 2>&1 &' >/dev/null 2>&1
OPD=0
for i in $(seq 1 18); do
  sleep 5
  activate >/dev/null 2>&1
  shot "3c12-open-dlg"
  if ocr_text | grep -qi "Open Project"; then OPD=1; break; fi
  if ocr_text | grep -qi "qa-nogem4"; then OPD=2; break; fi
done
[[ $OPD -eq 0 ]] && { fail "3c12" "no Open Project dialog"; exit 1; }
if [[ $OPD -eq 1 ]]; then
NXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='New Window':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
[[ -z "$NXY" ]] && { fail "3c12" "no New Window button"; exit 1; }
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick c:$NXY" >/dev/null 2>&1
sleep 20
activate
fi
shot "3c12-trust"
if ocr_text | grep -qi "Trust Project"; then
  click_text "$LAST_SHOT" "Trust Project" || exit 1
  sleep 25
  activate
fi
front_window_has "qa-nogem4" || { fail "3c12" "front window is not qa-nogem4"; exit 1; }
# Open foo.rb -> FIRST balloon must appear.
open_tree_file "foo.rb" || { fail "3c12" "no foo.rb in tree"; exit 1; }
sleep 15
activate
shot "3c12-first"
ocr_text | grep -qi "Docscribe gem not found" || { echo "--- first ---" >&2; ocr_text >&2; fail "3c12" "no first balloon"; exit 1; }
# Mark the log AFTER the first balloon; reopen bar.rb + foo.rb several
# times; assert NO new missing-gem mention (balloon path re-entered).
M0="$(log_mark)"
for f in bar.rb foo.rb bar.rb foo.rb; do
  open_tree_file "$f" >/dev/null 2>&1 || { fail "3c12" "no $f in tree"; exit 1; }
  sleep 8
done
shot "3c12-last"
B="$(ocr_text)"
# Absence oracle: the balloon function has NO log line, so count the
# daemon-failure lines for the project instead — they fire on every open
# (proof the code path re-ran), while the balloon must NOT re-fire.
# Screenshot oracle for absence: after reopens, the balloon must be GONE
# (balloons expire; a re-fired one would be visible).
NFAIL="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log | grep -F 'doAnnotate failed for /Users/admin/qa-nogem4' | wc -l" | tr -d ' ')"
[[ "$NFAIL" -ge 2 ]] || { echo "failure lines: $NFAIL (want >=2, path must re-run)" >&2; fail "3c12" "path did not re-run"; exit 1; }
echo "$B" | grep -qi "Docscribe gem not found" && { echo "--- last ---" >&2; echo "$B" >&2; fail "3c12" "balloon still visible after reopens"; exit 1; }
# Back to the stand.
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
pass "3c12"
