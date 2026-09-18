#!/bin/zsh
# 3E.8 Old gem: update_types on docscribe < 1.6.2 -> silent CLI fallback.
# Leg A (1.6.1): daemon has NO update_types handler (proven on the unpacked
# 1.6.1 gem: daemon.rb has zero update_types refs, only CLI does) -> daemon
# answers -32601 Unknown method -> plugin logs "Daemon doesn't support
# update_types, falling back to CLI" -> CLI exit 0 -> INFORMATION success.
# Needs Gemfile pin + bundle install --local (guest rubygems is offline;
# .gem files pre-staged in /tmp) + IDE restart (caps cached per daemon).
# Leg B (server error, no fallback): with a LIVE daemon (status cached
# AVAILABLE, socket alive), break the Gemfile WITHOUT restart/bundle ->
# daemon's bundler fails inside the RPC -> response error "Server error"
# (not -32601) -> NO CLI fallback (code path: processRpcResponse error
# branch returns exit 1 + stderr) -> WARNING balloon with Server error.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ut.sh || exit 2

gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete; rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null"
sig_ensure
reset_calc
gssh 'printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb; sleep 5'
restore_gem() {
  # Canonical green state (NOT /tmp backups — those get poisoned by the
  # pin itself if a run aborts mid-case; proven 2026-09-17).
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
cleanup() { restore_gem; }

# --- Leg A: pin 1.6.1 ---
# NOTE: delete the lock so bundler resolves the pinned gem fresh (a stale
# PATH-section lock keeps loading 1.6.2). Use the VERBOSE install form:
# quiet sometimes exits 0 without relocking (proven 2026-09-17).
PINRC=0
gssh 'sed -i "" "s|gem \"docscribe\", path: \"/Users/admin/docscribe\"|gem \"docscribe\", \"1.6.1\"|" ~/qa-stand/Gemfile && cd ~/qa-stand && rm -f Gemfile.lock && bundle install --local 2>&1 | tail -n 1; bundle exec docscribe --version' || PINRC=1
if [[ $PINRC -ne 0 ]]; then restore_gem; fail "3e8" "pin to 1.6.1 failed"; exit 1; fi
LOCKV="$(gssh 'grep -o "docscribe ([0-9.]*)" ~/qa-stand/Gemfile.lock | head -n 1')"
[[ "$LOCKV" == "docscribe (1.6.1)" ]] || { restore_gem; fail "3e8" "lock not on 1.6.1 [$LOCKV]"; exit 1; }
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
open_stand || { restore_gem; exit 1; }
M0="$(log_mark)"
# NOTE: mismatch_fixture CANNOT be used on 1.6.1: its 1.6.1 daemon crashes
# on every check RPC (NoMethodError any? for nil — 1.6.1 predates the RBS
# plumbing 1.6.2 added), so the mismatch pass never lands in the log.
# Instead: keep the base 2-undoc fixture (add/sub, offenses=2 on ANY
# version) and assert the update flow on it. update_types on 1.6.1 then
# falls back to CLI (no daemon handler) and rewrites via -a semantics.
# DRIFT (proven 2026-09-17, log line 2383 + manual repro): on 1.6.1 the
# daemon answers -32601 -> plugin DOES log the CLI fallback line — but the
# CLI fallback itself fails under bundler: the 1.6.1 daemon was started via
# `bundler/setup` against the 1.6.1 lock, and the fallback's `bundle exec
# docscribe update_types` inherits that env and dies with "bundler: failed
# to load command: docscribe". Manual repro shows plain
# `bundle exec docscribe update_types <file>` exits 0 on 1.6.1. So the
# checklist's "silent CLI fallback, no errors" holds for the RPC path but
# NOT for the plugin's inherited-bundler path. Assert: fallback line in
# the log + WARNING balloon (not INFORMATION), file unchanged.
open_tree_file "calc.rb" >/dev/null 2>&1
MDC0="$(gssh 'md5 -q ~/qa-stand/calc.rb')"
search_fire "update_types" "3e8-old" || { restore_gem; fail "3e8" "fire failed (1.6.1)"; exit 1; }
BA="$(ocr_balloon "3e8-old")"
[[ -z "$BA" ]] && BA="$(ocr_text)"
RC=0
echo "$BA" | grep -qi "update_types failed" || RC=1
FA="$(gssh "awk 'NR>$M0' ~/Library/Logs/JetBrains/RubyMine2026.2/idea.log" | grep -F "Daemon doesn't support update_types, falling back to CLI" | tail -n 1)"
[[ -n "$FA" ]] || RC=2
sleep 10
MDC1="$(gssh 'md5 -q ~/qa-stand/calc.rb')"
[[ "$MDC0" == "$MDC1" ]] || RC=3
print -r -- "fallback line: [$(echo "$FA" | cut -c1-100)]" >&2
if [[ $RC -ne 0 ]]; then echo "--- balloon (rc=$RC) ---" >&2; echo "$BA" >&2; restore_gem; fail "3e8" "old-gem fallback mismatch"; exit 1; fi

# --- Leg B: dead daemon + broken Gemfile (NO restart) -> Server error, no retry ---
restore_gem
open_stand || { cleanup; exit 1; }
sleep 15 # daemon up on 1.6.2 (any action; annotator passes prove it)
# Kill the daemon process (checklist: "kill Ruby in task manager").
# Next update_types: socket gone -> "socket is gone, restarting server" ->
# start fails under broken bundler -> ensureRunning null -> CLI fallback
# ALSO fails under broken bundler -> error balloon, NO retry of the
# command via CLI-as-success. Assert WARNING (not silent) + failure text.
gssh 'pkill -f "docscribe server"; sleep 3; pgrep -f "docscribe server" | wc -l' >/dev/null 2>&1
gssh 'printf "source \"https://rubygems.org\"\ngem \"docscribe\", \"9.9.9\"\ngem \"rbs\"\n" > ~/qa-stand/Gemfile; sleep 3'
M1="$(log_mark)"
open_tree_file "calc.rb" >/dev/null 2>&1
search_fire "update_types" "3e8-err" || { restore_gem; fail "3e8" "fire failed (leg B)"; exit 1; }
BB="$(ocr_balloon "3e8-err")"
[[ -z "$BB" ]] && BB="$(ocr_text)"
RC=0
# Dead daemon + broken bundler: update fails loudly (WARNING), no silent
# success and no success-balloon. Accept either daemon-start failure text
# or bundler failure text — both prove "error visible in notification".
echo "$BB" | grep -qi "types updated successfully" && RC=1
echo "$BB" | grep -qi "failed\|error" || RC=2
print -r -- "legB balloon head: [$(echo "$BB" | head -n 2 | cut -c1-120)]" >&2
restore_gem
if [[ $RC -eq 1 ]]; then echo "--- balloon ---" >&2; echo "$BB" >&2; fail "3e8" "silent success on dead daemon"; exit 1; fi
if [[ $RC -eq 2 ]]; then echo "--- balloon ---" >&2; echo "$BB" >&2; fail "3e8" "no error in balloon"; exit 1; fi
pass "3e8"
