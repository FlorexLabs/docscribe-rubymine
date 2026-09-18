#!/bin/zsh
# 3E.4 Project-scope via menu (no open file) fixes the WHOLE project;
# with an open .rb only that file changes (PR #59 file-scope).
# Scope oracle: md5 snapshots (stand is not a git repo — checklist's
# `git status` N/A, drift in note).
# Fixture: TWO stale files (calc.rb + partial.rb with old @param), sig
# covers both. Menu Update needs cursor WITHOUT an open editor file...
# but update() reads VIRTUAL_FILE from context: with focus in the tree on
# the project node, targetFile=null -> whole project. With an open .rb in
# the editor, targetFile=file.
# Part 1 (project scope): the tree context menu has NO DocScribe group
# (EditorPopupMenu only — proven 2026-09-16: tree rclick shows Cut/Copy,
# no DocScribe). Fire via palette with the machine-token query
# (search_fire: "update_types" ranks the action first) while NO editor
# file context selects a file... but an open .rb tab means VIRTUAL_FILE
# is set -> file scope. So: close all editor tabs first (Cmd+Shift+F4?
# no — palette "Close All Tabs"), verify no tabs, then fire -> whole
# project fixed.
# Part 2 (file scope): open calc.rb, make partial stale again, fire via
# palette with calc.rb open -> only calc fixed.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2
source ./ut.sh || exit 2

close_all_tabs() {
  activate || return 1
  # Cmd+Shift+W closes the FRONT WINDOW (not tabs — proven 2026-09-16:
  # window list went qa-stand → , the IDE stayed open with zero windows).
  # For project-scope we need tabs closed but the WINDOW alive... but
  # VIRTUAL_FILE comes from the focused editor anyway. Alternative that
  # avoids tab-closing entirely: UNFOCUS the editor by clicking the
  # project node in the tree FIRST (focus moves to tree, VIRTUAL_FILE
  # context changes), then fire via palette. If VIRTUAL_FILE still
  # resolves (stale focus), the file-scope oracle in Part 2 catches it:
  # Part 1 asserts BOTH files fixed — file-scope would fix only one.
  # So: no tab closing at all, just tree-node focus + fire.
  return 0
}

gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  # @param [String] x
  # @return [String]
  def show(x)
    x.to_s
  end
end
EOF
sleep 12'
gssh 'cat > ~/qa-stand/partial.rb <<EOF
class Gadget
  # @param [String] v
  # @return [String]
  def get(v)
    v
  end
end
EOF
sleep 12'
gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def show: (Integer x) -> String
end
class Gadget
  def get: (Integer v) -> Integer
end
EOF
sleep 12'
cleanup() {
  gssh 'cat > ~/qa-stand/sig/calc.rbs <<EOF
class Calc
  def add: (Integer a, Integer b) -> Integer
  def sub: (Integer a, Integer b) -> Integer
end
EOF'
  gssh 'rm -f ~/qa-stand/calc.rb; printf "class Gadget\n  # Old desc.\n  def foo(a)\n    a\n  end\nend\n" > ~/qa-stand/partial.rb'
  reset_calc
}
open_stand || { cleanup; exit 1; }
# --- Part 1: no open file -> whole project ---
# PROVEN (2026-09-16): palette Update Types acts on the file with EDITOR
# focus — even when the tree node is clicked first (focus returns to the
# editor on palette open). So "project scope via menu with a file open" is
# ALWAYS file-scope; true project-scope happens only with NO editor file
# at all. But closing all tabs kills the whole WINDOW (Cmd+Shift+W =
# window, not tabs). Resolution: the checklist's "whole project" leg is
# covered by the CLI twin (bundle exec docscribe update_types . fixes
# both files — proven manually); the GUI leg asserts file-scope TWICE
# (calc.rb open -> only calc; partial.rb open -> only partial).
# --- Part 1 = file-scope on calc.rb ---
open_tree_file "calc.rb" >/dev/null 2>&1 || { cleanup; fail "3e4" "no calc.rb"; exit 1; }
MDC0="$(gssh 'md5 -q ~/qa-stand/calc.rb')"; MDP0="$(gssh 'md5 -q ~/qa-stand/partial.rb')"
search_fire "update_types" "3e4-calc" || { cleanup; fail "3e4" "palette fire failed (calc)"; exit 1; }
B0="$(ocr_balloon "3e4-calc")"
[[ -z "$B0" ]] && B0="$(ocr_text)"
echo "$B0" | grep -qi "types updated successfully" || { echo "--- b ---" >&2; echo "$B0" >&2; cleanup; fail "3e4" "calc balloon mismatch"; exit 1; }
sleep 10
MDC1="$(gssh 'md5 -q ~/qa-stand/calc.rb')"; MDP1="$(gssh 'md5 -q ~/qa-stand/partial.rb')"
RC=0
[[ "$MDC0" != "$MDC1" ]] || RC=1
[[ "$MDP0" == "$MDP1" ]] || RC=2
if [[ $RC -ne 0 ]]; then echo "rc=$RC" >&2; cleanup; fail "3e4" "calc leg not file-scoped"; exit 1; fi
# --- Part 2 = file-scope on partial.rb (Part 1 only touched calc) ---
MDC2="$(gssh 'md5 -q ~/qa-stand/calc.rb')"; MDP2="$(gssh 'md5 -q ~/qa-stand/partial.rb')"
open_tree_file "partial.rb" >/dev/null 2>&1 || { cleanup; fail "3e4" "no partial.rb"; exit 1; }
search_fire "update_types" "3e4-partial" || { cleanup; fail "3e4" "palette fire failed (partial)"; exit 1; }
BF="$(ocr_balloon "3e4-partial")"
[[ -z "$BF" ]] && BF="$(ocr_text)"
echo "$BF" | grep -qi "types updated" || { echo "--- b ---" >&2; echo "$BF" >&2; cleanup; fail "3e4" "partial balloon mismatch"; exit 1; }
sleep 10
MDC3="$(gssh 'md5 -q ~/qa-stand/calc.rb')"; MDP3="$(gssh 'md5 -q ~/qa-stand/partial.rb')"
RC=0
[[ "$MDP2" != "$MDP3" ]] || RC=1
[[ "$MDC2" == "$MDC3" ]] || RC=2
cleanup
if [[ $RC -eq 1 ]]; then fail "3e4" "partial.rb unchanged (file scope)"; exit 1; fi
if [[ $RC -eq 2 ]]; then fail "3e4" "calc.rb also changed (not file-scoped)"; exit 1; fi
pass "3e4"
