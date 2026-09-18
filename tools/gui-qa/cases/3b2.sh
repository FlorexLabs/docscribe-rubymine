#!/bin/zsh
# 3B.2 Broken gem (Gemfile docscribe 9.9.9): Check Current File -> ERROR
# "DocScribe: error running docscribe". Restores Gemfile + bundle after.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2

# NOTE: restore = rewrite known-good content (no .good backup; it would copy
# broken content back when cases chain). Same trap bit 3b3.
# ORDER: break FIRST, restart IDE (fresh daemon), fire, restore, restart.
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", "9.9.9"
gem "rbs"
EOF
cd ~/qa-stand && bundle install 2>&1 | tail -n 1' || exit 2
reset_calc
# Fresh IDE process: DocscribeDaemon is a project service — docscribeStatus
# cache (AVAILABLE from earlier cases) survives menu fires in the same
# process (proven 2026-09-15: broken Gemfile still "checked 1 file").
# `pkill -x rubymine` + CLI reopen does NOT work (CLI exits headless with
# "Unable to detect graphics environment" -> IDE SHUTDOWN, proven
# 2026-09-15); must relaunch via `open -a RubyMine` (GUI session).
# Same for the restore path below (else next cases inherit MISSING).
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60 # cold boot + indexing
open_stand || { fail "3b2" "reopen failed"; exit 1; }
sleep 15 # daemon re-probe + annotate settle
menu_fire "3b2" "Check Current File" || { fail "3b2" "menu fire failed"; exit 1; }
RC=0
ocr_text | grep -qi "DocScribe: error running docscribe" || RC=1
# Restore FIRST (before any sleep): the daemon re-probes on Gemfile.lock
# mtime change, and other cases need a green stand. Then restart the IDE
# (else next cases inherit the MISSING daemon wedged by this case — proven
# 2026-09-15: 3b3 saw "gem is not installed" after 3b2's restore).
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", path: "/Users/admin/docscribe"
gem "rbs"
EOF
cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1' >/dev/null 2>&1
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -ne 0 ]]; then echo "--- b ---" >&2; ocr_text >&2; fail "3b2" "balloon mismatch"; exit 1; fi
pass "3b2"
