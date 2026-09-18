#!/bin/zsh
# 3G.5 Fresh version without restart: touch Gemfile.lock (mtime bump) ->
# Doctor re-probes (performGemCheck on every report) and still shows the
# CURRENT version. Stronger variant: pin 1.6.1 (relock), Doctor WITHOUT
# restart must show 1.6.1; then restore + Doctor shows 1.6.2 again.
# UNCHECKED variant (code-read): fresh IDE boot before ANY action ->
# status UNCHECKED -> "docscribe gem: not yet checked". That needs a boot
# with zero prior actions — covered by the same restart: Doctor is the
# FIRST action after boot, but performGemCheck runs inside buildReport,
# so UNCHECKED text is only visible if the check itself is skipped. Assert
# the version freshness (observable); UNCHECKED stays code-read.
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
RC=0
# Baseline 1.6.2.
R0="$(do_doctor "3g5-a")" || { cleanup; fail "3g5" "doctor failed (base)"; exit 1; }
echo "$R0" | grep -qi "Version: 1.6.2" || { print -r -- "MISS base" >&2; RC=1; }
# Pin 1.6.1 + relock, NO restart. Daemon keeps running (1.6.2 code) but
# Doctor's performGemCheck re-reads the version -> report must say 1.6.1.
gssh 'sed -i "" "s|gem \"docscribe\", path: \"/Users/admin/docscribe\"|gem \"docscribe\", \"1.6.1\"|" ~/qa-stand/Gemfile && cd ~/qa-stand && rm -f Gemfile.lock && bundle install --local 2>&1 | tail -n 1' >/dev/null 2>&1
sleep 5
R1="$(do_doctor "3g5-b")" || { cleanup; fail "3g5" "doctor failed (1.6.1)"; exit 1; }
echo "$R1" | grep -qi "Version: 1.6.1" || { print -r -- "MISS fresh" >&2; RC=2; }
print -r -- "fresh version line: [$(echo "$R1" | grep -i 'Version' | head -n 1)]" >&2
restore_all
open_stand || { cleanup; exit 1; }
sleep 10
R2="$(do_doctor "3g5-c")" || { cleanup; fail "3g5" "doctor failed (restored)"; exit 1; }
echo "$R2" | grep -qi "Version: 1.6.2" || { print -r -- "MISS restored" >&2; RC=3; }
cleanup
if [[ $RC -ne 0 ]]; then fail "3g5" "freshness mismatch (rc=$RC)"; exit 1; fi
pass "3g5"
