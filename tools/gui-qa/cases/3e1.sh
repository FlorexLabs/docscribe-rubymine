#!/bin/zsh
# 3E.1 Update row visibility matrix:
# (a) sig/ present, Gemfile WITHOUT direct rbs line -> VISIBLE;
# (b) no RBS at all (qa-nogem2: rake-only Gemfile) -> HIDDEN (Check visible);
# (c) no Gemfile at all (qa-nogemdir) -> HIDDEN (Check visible);
# (d) sig/ + rbs.enabled:false -> HIDDEN.
# Sibling proof: Check Current File row (extension-gated only) must be
# present in every opened submenu.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ws.sh || exit 2
source ./ut.sh || exit 2

need_visible() { # $1=rows-text $2=leg
  echo "$1" | grep -q "Check Current File" || { echo "--- $2 (no menu?) ---" >&2; echo "$1" >&2; fail "3e1" "$2: submenu did not open"; return 1; }
}
RC=0

# --- (a) sig/, no direct rbs line ---
gssh 'cp ~/qa-stand/Gemfile ~/qa-stand/Gemfile.bak && grep -v "^gem \"rbs\"$" ~/qa-stand/Gemfile.bak > ~/qa-stand/Gemfile && grep -c "^gem" ~/qa-stand/Gemfile && cd ~/qa-stand && bundle install --quiet 2>&1 | tail -n 1'
cleanup_a() { gssh 'mv ~/qa-stand/Gemfile.bak ~/qa-stand/Gemfile 2>/dev/null; cd ~/qa-stand && bundle install --quiet 2>/dev/null'; }
open_project_dir "qa-stand" || { cleanup_a; exit 1; }
open_tree_file "calc.rb" >/dev/null 2>&1
RA="$(submenu_rows "3e1-a")" || { cleanup_a; fail "3e1" "leg-a no submenu"; exit 1; }
need_visible "$RA" "leg-a" || { cleanup_a; exit 1; }
echo "$RA" | grep -q "Update Types from RBS" || { echo "--- leg-a ---" >&2; echo "$RA" >&2; echo "leg-a: Update row HIDDEN (want visible)" >&2; RC=1; }
cleanup_a

# --- (d) sig/ + explicit false ---
gssh 'cp ~/qa-stand/docscribe.yml ~/qa-stand/docscribe.yml.bak && printf "rbs:\n  enabled: false\n" > ~/qa-stand/docscribe.yml'
cleanup_d() { gssh 'mv ~/qa-stand/docscribe.yml.bak ~/qa-stand/docscribe.yml 2>/dev/null; cd ~/qa-stand && bundle install --quiet 2>/dev/null'; reset_calc; }
sleep 15 # rbsHash re-probe settle
RD="$(submenu_rows "3e1-d")" || { cleanup_d; fail "3e1" "leg-d no submenu"; exit 1; }
need_visible "$RD" "leg-d" || { cleanup_d; exit 1; }
echo "$RD" | grep -q "Update Types from RBS" && { echo "--- leg-d ---" >&2; echo "$RD" >&2; echo "leg-d: Update row VISIBLE (want hidden)" >&2; RC=2; }
cleanup_d

# --- (b) no RBS at all ---
gssh 'cat ~/qa-nogem2/Gemfile'
open_project_dir "qa-nogem2" || { fail "3e1" "no nogem2 window"; exit 1; }
open_tree_file "foo.rb" >/dev/null 2>&1
RB="$(submenu_rows "3e1-b")" || { fail "3e1" "leg-b no submenu"; exit 1; }
need_visible "$RB" "leg-b" || exit 1
echo "$RB" | grep -q "Update Types from RBS" && { echo "--- leg-b ---" >&2; echo "$RB" >&2; echo "leg-b: Update row VISIBLE (want hidden)" >&2; RC=3; }

# --- (c) no Gemfile ---
gssh 'rm -rf ~/qa-nogemdir && mkdir -p ~/qa-nogemdir && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogemdir/foo.rb; ls ~/qa-nogemdir'
open_project_dir "qa-nogemdir" || { fail "3e1" "no nogemdir window"; exit 1; }
open_tree_file "foo.rb" >/dev/null 2>&1
RN="$(submenu_rows "3e1-n")" || { fail "3e1" "leg-c no submenu"; exit 1; }
need_visible "$RN" "leg-c" || exit 1
echo "$RN" | grep -q "Update Types from RBS" && { echo "--- leg-c ---" >&2; echo "$RN" >&2; echo "leg-c: Update row VISIBLE (want hidden)" >&2; RC=4; }

# Back to the stand.
gssh 'pkill -x rubymine 2>/dev/null; sleep 5; nohup open -a RubyMine >/dev/null 2>&1 &' >/dev/null 2>&1
sleep 60
if [[ $RC -ne 0 ]]; then fail "3e1" "visibility matrix mismatch (rc=$RC)"; exit 1; fi
pass "3e1"
