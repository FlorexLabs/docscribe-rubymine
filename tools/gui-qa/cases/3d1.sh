#!/bin/zsh
# 3D.1 Three RBS signals engage RBS end-to-end (GUI half; isolation matrix
# is framework: RbsDetectorTest sig-only/lock-only tmpdirs, blank=false).
# (a) sig/foo.rbs present (no direct rbs gem); (b) rbs in Gemfile.lock
# (transitive via parser — Gemfile has NO direct rbs) + gem declared (c).
# NOTE (2026-09-16): EVERY bundler project with docscribe has rbs in the
# lock (transitive via parser), so strict single-signal isolation is
# impossible live; framework tmpdir tests do the isolation. GUI asserts
# each described setup reports rbs=true in the collectRubyFiles log line.
# Checklist's `shouldUseRbs=true` log line does NOT exist in code (drift).
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ws.sh || exit 2

mkproj() { # $1=dir $2=rbs-gem? (1/0) — NEVER rm -rf an existing dir
  # (proven 2026-09-16: mkproj call N+1's rm -rf deleted project N's sig/
  # content — actually the whole N dir vanished, qa-rbs-bc gone entirely).
  # Reuse-or-create: mkdir -p only.
  gssh "mkdir -p ~/$1/sig && printf 'source \"https://rubygems.org\"\ngem \"docscribe\", path: \"/Users/admin/docscribe\"\ngem \"rake\"\n' > ~/$1/Gemfile"
  if [[ "$2" == "1" ]]; then
    gssh "printf 'gem \"rbs\"\n' >> ~/$1/Gemfile"
  fi
  gssh "printf 'class Foo\n  def go: (Integer x) -> Integer\nend\n' > ~/$1/sig/foo.rbs"
  gssh "printf 'class Foo\n  def go(x)\n    x\n  end\nend\n' > ~/$1/foo.rb && cd ~/$1 && bundle install --quiet 2>&1 | tail -n 1; test -s ~/$1/sig/foo.rbs && test -s ~/$1/foo.rb && test -s ~/$1/Gemfile && echo SIG_OK; grep -c 'rbs (' ~/$1/Gemfile.lock || true"
}
mkproj qa-rbs-a 0 || exit 1
mkproj qa-rbs-bc 1 || exit 1
# mkproj VERIFY: SIG_OK must appear TWICE (once per project). mkproj's own
# rm -rf + background IDE indexing can still eat a sig/ between the two
# calls (proven 2026-09-16: qa-rbs-bc/sig vanished entirely).

check_rbs_true() { # $1=dir $2=tag
  open_project_dir "$1" || return 1
  # Force the editor onto the project's own file: open_project_dir may
  # leave a STALE tab front (another project's file), and fire_workspace
  # would then check the WRONG project (proven 2026-09-16: qa-rbs-a window
  # open, but collectRubyFiles never ran for it — search fired elsewhere).
  open_tree_file "foo.rb" || return 1
  local M0; M0="$(log_mark)"
  fire_workspace "$2" || return 1
  sleep 10
  local L; L="$(ws_flags "$M0" "/Users/admin/$1")"
  echo "$L" | grep -q "rbs=true" || { echo "--- collectRubyFiles ---" >&2; echo "${L:-<none>}" >&2; return 1; }
}
check_rbs_true qa-rbs-a "3d1-a" || { fail "3d1" "sig-only project rbs!=true"; exit 1; }
check_rbs_true qa-rbs-bc "3d1-bc" || { fail "3d1" "lock/gem project rbs!=true"; exit 1; }
# Back to the stand.
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
pass "3d1"
