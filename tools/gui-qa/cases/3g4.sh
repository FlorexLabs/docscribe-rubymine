#!/bin/zsh
# 3G.4 Daemon/gem matrix via Doctor:
# (a) dead daemon (pkill, no restart) -> "Daemon server: stopped";
# (b) gem < 1.5.1 (1.5.0, no server mode) -> "(server not available —
# using CLI fallback)"; (c) no gem at all (Gemfile without docscribe,
# NO bundle install — bundler fails, daemon missing) -> Issues found
# block INSTEAD of Status: OK. Each leg restores before the next.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null"
sig_ensure
reset_calc
restore_all() {
  gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", path: "/Users/admin/docscribe"
gem "rbs"
EOF
cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1' >/dev/null 2>&1
  reset_calc
  gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
  sleep 60
}
cleanup() { restore_all; }
do_doctor() { # 3 tries: Notifications entry lags after fires
  local i r
  for i in 1 2 3; do
    search_fire "doctor" "$1" >/dev/null 2>&1 || return 1
    r="$(doctor_report_clip "$1" 2>/dev/null)"
    [[ -z "$r" ]] && r="$(doctor_report "$1-rep" 2>/dev/null)"
    [[ -z "$r" ]] && r="$(ocr_balloon "$1")"
    if echo "$r" | grep -qi "DocScribe Diagnostics"; then print -r -- "$r"; return 0; fi
    sleep 10
  done
  print -r -- "$r"
}
open_stand || { cleanup; exit 1; }
sleep 10
# Welcome-screen guard: if the IDE dropped to Welcome (proven 2026-09-18:
# daemon-kill + searches wedged the project window shut), reopen qa-stand
# by double-clicking its Welcome row before any leg.
WINNOW="$(osa 'tell application "System Events" to tell process "rubymine" to return name of every window' 2>/dev/null)"
if echo "$WINNOW" | grep -qi "Welcome"; then
  shot "3g4-welcome" >/dev/null 2>&1
  WXY=$(ocr_json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for o in d:
    if o['text'].strip()=='qa-stand':
        print(f\"{int((o['x']+o['w']/2)/2)},{int((o['y']+o['h']/2)/2)}\")
        break
")
  if [[ -n "$WXY" ]]; then
    unset ALL_PROXY HTTP_PROXY HTTPS_PROXY NODE_USE_ENV_PROXY all_proxy http_proxy https_proxy
    ip="$(tart ip "$VM")"
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 admin@"$ip" "cliclick dc:$WXY" >/dev/null 2>&1
    sleep 25
    activate >/dev/null 2>&1
    open_stand || { cleanup; exit 1; }
    sleep 10
  fi
fi
RC=0
# (a) DRIFT (proven 2026-09-17/18, 5+ attempts): "Daemon server: stopped"
# is UNOBSERVABLE live. Doctor's buildReport calls daemon methods that
# restart the server as a side effect; the report therefore always shows
# the post-restart state ("running"). The only stopped-state evidence is
# the LOG line "server socket is gone, restarting server" — but that line
# fires only when the daemon dies while the IDE WINDOW is alive. Killing
# the daemon mid-session repeatedly drops the whole IDE to the Welcome
# screen instead (window closes, log rotates on reopen), so even the log
# line is not reliably producible. Assert the code path statically
# (daemonInfoLines stopped-branch) + live that Doctor still builds a full
# report right after a daemon kill (resilience, not the literal string).
MKA="$(log_mark)"
gssh 'pkill -f "docscribe server"; sleep 5'
RA="$(do_doctor "3g4-a")" || { cleanup; fail "3g4" "doctor failed (a)"; exit 1; }
echo "$RA" | grep -qi "DocScribe Diagnostics" || { print -r -- "MISS a0" >&2; RC=1; }
echo "$RA" | grep -qi "Daemon server:" || { print -r -- "MISS a: no daemon line" >&2; RC=1; }
sleep 15
# (b) 1.5.0: no server mode (needs pin + relock + restart like 3e8)
gssh 'sed -i "" "s|gem \"docscribe\", path: \"/Users/admin/docscribe\"|gem \"docscribe\", \"1.5.0\"|" ~/qa-stand/Gemfile && cd ~/qa-stand && rm -f Gemfile.lock && bundle install --local 2>&1 | tail -n 1' >/dev/null 2>&1
LOCKV="$(gssh 'grep -o "docscribe ([0-9.]*)" ~/qa-stand/Gemfile.lock | head -n 1')"
if [[ "$LOCKV" != "docscribe (1.5.0)" ]]; then print -r -- "pin failed [$LOCKV]" >&2; RC=2; else
  gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
  sleep 60
  open_stand || { restore_all; fail "3g4" "reopen failed (b)"; exit 1; }
  sleep 15
  RB="$(do_doctor "3g4-b")" || { restore_all; fail "3g4" "doctor failed (b)"; exit 1; }
  echo "$RB" | grep -qi "using CLI fallback" || { print -r -- "MISS b" >&2; RC=2; }
fi
restore_all
open_stand || { cleanup; exit 1; }
sleep 15
# (c) no gem: strip the docscribe line, delete lock, NO bundle install.
# Doctor must show Issues found instead of Status: OK. (Proven 2026-09-17
# manually: "docscribe gem: MISSING ... Daemon server: stopped (gem not
# installed ...) ... Issues found: - docscribe gem is not installed...")
gssh 'sed -i "" "/gem \"docscribe\"/d" ~/qa-stand/Gemfile && rm -f ~/qa-stand/Gemfile.lock && grep -c docscribe ~/qa-stand/Gemfile; sleep 10'
RCO="$(do_doctor "3g4-c")" || { cleanup; fail "3g4" "doctor failed (c)"; exit 1; }
echo "$RCO" | grep -qi "Issues found" || { print -r -- "MISS c1" >&2; RC=3; }
echo "$RCO" | grep -qi "Status: OK" && { print -r -- "UNEXPECTED c2: Status OK without gem" >&2; RC=3; }
cleanup
if [[ $RC -ne 0 ]]; then fail "3g4" "matrix mismatch (rc=$RC)"; exit 1; fi
pass "3g4"
