#!/bin/zsh
# 3F.6 Old-gem CLI fallback: docscribe 1.5.0 (< 1.5.2, no check_batch,
# server mode yes since 1.5.1 — so the batch gate fires, server gate does
# not). Stand Gemfile pinned to 1.5.0 + bundle install + IDE restart
# (project-service caches caps per daemon process). Fire Check Entire
# Workspace: must complete WITHOUT hang/crash — balloon checked-count
# appears; log shows "check_batch not available, using CLI directory scan".
# Restores 1.6.2 + restarts IDE after.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh "rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null; find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"
sig_ensure
reset_calc
gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 5'
gssh 'cat > ~/qa-stand/Gemfile <<EOF
source "https://rubygems.org"
gem "docscribe", "1.5.0"
gem "rbs"
EOF
cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1; bundle exec docscribe --version'
restore() {
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
cleanup() { restore; }
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
open_stand || { restore; exit 1; }
source ./ws.sh || exit 2
sleep 15 # daemon re-probe on old gem + annotate settle

M0="$(log_mark)"
fire_workspace "3f6" || { restore; fail "3f6" "fire failed (hang?)"; exit 1; }
B="$(ocr_text)"
RC=0
echo "$B" | grep -qi "checked .* file(s)" || RC=1
FB="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F 'check_batch not available, using CLI directory scan' | tail -n 1)"
[[ -n "$FB" ]] || RC=2
print -r -- "fallback line: [$(echo "$FB" | cut -c1-120)]" >&2
restore
if [[ $RC -eq 1 ]]; then echo "--- balloon ---" >&2; echo "$B" >&2; fail "3f6" "no result balloon on old gem"; exit 1; fi
if [[ $RC -eq 2 ]]; then fail "3f6" "no CLI-scan fallback line"; exit 1; fi
pass "3f6"
