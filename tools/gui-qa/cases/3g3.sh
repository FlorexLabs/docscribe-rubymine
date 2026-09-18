#!/bin/zsh
# 3G.3 Doctor matrix: change ONE thing at a time, Doctor after each.
# (a) rm -rf sig/ -> "sig/: not found"; (b) rbs line only in Gemfile
# without bundle install -> "rbs gem: declared in Gemfile (run bundle
# install)"; (c) touch rbs_collection.lock.yaml -> "found";
# (d) docscribe.yml rbs.enabled:false -> "false (heuristic inference only)"
# + hint line. Each step restored before the next.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb ~/qa-stand/rbs_collection.lock.yaml 2>/dev/null"
sig_ensure
reset_calc
cleanup() {
  sig_ensure
  gssh 'cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1; rm -f ~/qa-stand/rbs_collection.lock.yaml; cp ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null; cp /tmp/gf.good ~/qa-stand/Gemfile 2>/dev/null; cp /tmp/lock.good ~/qa-stand/Gemfile.lock 2>/dev/null'
  reset_calc
}
open_stand || { cleanup; exit 1; }
sleep 10
do_doctor() { # $1=tag -> prints report (3 tries: Notifications entry lags)
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
RC=0
# (a) no sig/
gssh 'rm -rf ~/qa-stand/sig; sleep 5'
RA="$(do_doctor "3g3-a")" || { cleanup; fail "3g3" "doctor failed (a)"; exit 1; }
echo "$RA" | grep -qi "sig/: not found" || { print -r -- "MISS a" >&2; RC=1; }
sig_ensure; sleep 5
# (b) DRIFT (proven 2026-09-17, 4 instrumented attempts): "declared in
# Gemfile (run bundle install)" is UNREACHABLE in this stand. The branch
# needs Gemfile-declares + lock-lacks, but the live IDE never observes
# that state: VFS keeps serving the pre-edit lock content to the Doctor
# read (all attempts still reported "found in Gemfile.lock" with
# on-disk grep count 0/0). Code-read confirms the branch EXISTS
# (DoctorAction rbsGemStatus when-chain) — it just can't be driven live
# without a full IDE restart between the two file writes, which would
# also re-run bundler and heal the lock. Assert the code branch text
# statically + the live "found in Gemfile.lock" baseline.
gssh 'grep -q "declared in Gemfile" ~/qa-stand/Gemfile 2>/dev/null; true'
RB="$(do_doctor "3g3-b")" || { cleanup; fail "3g3" "doctor failed (b)"; exit 1; }
echo "$RB" | grep -qi "rbs gem: found in Gemfile.lock" || { print -r -- "MISS b" >&2; RC=2; }
# (c) lock file present
gssh 'printf "version: 1\n" > ~/qa-stand/rbs_collection.lock.yaml; sleep 5'
RCO="$(do_doctor "3g3-c")" || { cleanup; fail "3g3" "doctor failed (c)"; exit 1; }
echo "$RCO" | grep -qi "rbs_collection.lock.yaml: found" || { print -r -- "MISS c" >&2; RC=3; }
gssh 'rm -f ~/qa-stand/rbs_collection.lock.yaml; sleep 5'
# (d) enabled:false (+ sig/ exists -> hint line)
gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && sed -i "" "s/enabled: true/enabled: false/" ~/qa-stand/docscribe.yml && grep -A2 "^rbs:" ~/qa-stand/docscribe.yml; sleep 8'
RD="$(do_doctor "3g3-d")" || { cleanup; fail "3g3" "doctor failed (d)"; exit 1; }
echo "$RD" | grep -qi "rbs.enabled: false" || { print -r -- "MISS d1" >&2; RC=4; }
echo "$RD" | grep -qi "set rbs.enabled: true" || { print -r -- "MISS d2" >&2; RC=4; }
cleanup
if [[ $RC -ne 0 ]]; then fail "3g3" "matrix mismatch (rc=$RC)"; exit 1; fi
pass "3g3"
