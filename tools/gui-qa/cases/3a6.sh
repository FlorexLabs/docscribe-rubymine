#!/bin/zsh
# 3A.6 SDK: idea.log shows ruby=<sdk>/bin/ruby + BUNDLE_GEMFILE=<root>/Gemfile;
# bundle launched via absolute path. Oracle: guest idea.log.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

LOG="$(rm_log_grep 'gem check env' | tail -n 3)"
echo "$LOG" >&2
echo "$LOG" | grep -q "ruby=/Users/admin/.rbenv/versions/3.4.5/bin/ruby" || { fail "3a6" "no sdk ruby path"; exit 1; }
echo "$LOG" | grep -q "BUNDLE_GEMFILE=/Users/admin/qa-stand/Gemfile" || { fail "3a6" "no BUNDLE_GEMFILE"; exit 1; }
# absolute bundle: bundleCommand() = bundlePathFor(rubyCommand()) — SDK bin dir.
# Oracle: gem check env ruby=.../3.4.5/bin/ruby (plugin.xml KDoc:
# "using the absolute path guarantees the project's Ruby SDK is used") —
# /usr/bin/bundle would resolve to system ruby, not rbenv 3.4.5.
echo "$LOG" | grep -q "PATH=/Users/admin/.rbenv/versions/3.4.5/bin" || { fail "3a6" "no sdk PATH"; exit 1; }
pass "3a6"
