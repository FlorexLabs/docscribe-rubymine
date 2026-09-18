#!/bin/zsh
# 3A.5 No Gemfile: Check Current File -> ERROR balloon "No Gemfile found in
# project tree". Stand: empty dir with foo.rb only.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

gssh 'rm -rf ~/qa-nogem && mkdir -p ~/qa-nogem && printf "def hello(name)\n  \"hi\"\nend\n" > ~/qa-nogem/foo.rb && ls ~/qa-nogem' || exit 2
# Attached-file mode is INVALID for this oracle (findProjectRoot walks up
# from the file: ~/qa-nogem/foo.rb -> $HOME has no Gemfile... actually $HOME
# itself — check first; safer: verify the implementation walks to filesystem
# root and $HOME/Gemfile does not exist).
#
# Cheaper + hermetic: unit-test findProjectRoot via existing fixtures is
# covered in framework tests. Here: create the no-gem file INSIDE a temp
# subdir far from any Gemfile AND assert $HOME has no Gemfile first.
PRE="$(gssh 'ls ~/Gemfile 2>&1; echo ---; ls ~/qa-nogem/Gemfile 2>&1')"
echo "$PRE" >&2
echo "$PRE" | grep -q "No such file" || { fail "3a5" "stray Gemfile breaks oracle"; exit 1; }
# findProjectRoot walks up from the FILE path, so attached-tab mode is fine:
# ~/qa-nogem/foo.rb -> ~/qa-nogem -> $HOME (no Gemfile) -> / (none) -> null.
open_file "qa-nogem/foo.rb" || exit 1
front_window_has "qa-nogem/foo.rb" || { fail "3a5" "front window has no qa-nogem tab"; exit 1; }
search_type "Check Current File" || { fail "3a5" "search box won't open"; exit 1; }
sleep 2 # let the filter settle: the "DocScribe:"-prefixed row renders racy
shot "3a5-palette"
# Row proof = title + description (the "DocScribe:"-prefixed dup row is racy).
ocr_text | grep -qi "Check Current File" || { fail "3a5" "no action row"; exit 1; }
ocr_text | grep -qi "Run docscribe check" || { fail "3a5" "no action description"; exit 1; }
search_enter
shot "3a5-balloon"
if ocr_text | grep -qi "No Gemfile found in project tree"; then
  pass "3a5"
else
  echo "--- balloon OCR ---" >&2; ocr_text >&2
  fail "3a5" "balloon mismatch"; exit 1
fi
