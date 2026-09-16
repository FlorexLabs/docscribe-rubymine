#!/bin/zsh
# 3B.1 Check Current File: warning balloon with counts, clean file without OK.
# Oracle: balloon OCR + idea.log daemon result.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

# --- fixture: 2 undocumented methods ---
gssh 'cat > ~/qa-stand/calc.rb <<EOF
class Calc
  def add(a, b)
    a + b
  end
  def sub(a, b)
    a - b
  end
end
EOF' || exit 2
gssh "~/bin/rubymine ~/qa-stand/calc.rb >/dev/null 2>&1 &" >/dev/null 2>&1
sleep 20
activate

# --- fire Check Current File ---
search_type "Check Current File" || { fail "3b1" "search box won't open"; exit 1; }
sleep 2 # let the filter settle: the "DocScribe:"-prefixed row renders racy
shot "3b1-palette"
# Row proof = title + description (the "DocScribe:"-prefixed dup row is racy).
if ! ocr_text | grep -qi "Check Current File"; then
  fail "3b1" "action row missing in search"; exit 1
fi
if ! ocr_text | grep -qi "Run docscribe check"; then
  fail "3b1" "action description missing in search"; exit 1
fi
search_enter
shot "3b1-balloon"
if ocr_text | grep -qiE "DocScribe: checked 1 file\(s\)[^a-z]*2 issue\(s\) found"; then
  :
else
  echo "--- balloon OCR ---" >&2; ocr_text >&2
  fail "3b1" "balloon text mismatch"; exit 1
fi

# --- log oracle: daemon parsed offenses=2 ---
if rm_log_grep "doAnnotate parsed output" | grep -q "offenses=2"; then
  :
else
  fail "3b1" "log offenses!=2"; exit 1
fi

# --- clean file: no OK suffix ---
gssh 'cat > ~/qa-stand/clean.rb <<EOF
# documentation
class Foo; end
EOF' || exit 2
gssh "~/bin/rubymine ~/qa-stand/clean.rb >/dev/null 2>&1 &" >/dev/null 2>&1
sleep 15
activate
search_type "Check Current File"
search_enter
shot "3b1-clean"
TEXT="$(ocr_text)"
if echo "$TEXT" | grep -qiE "DocScribe: checked 1 file\(s\)"; then
  if echo "$TEXT" | grep -qiE "OK"; then
    echo "--- clean OCR ---" >&2; echo "$TEXT" >&2
    fail "3b1" "clean balloon has OK suffix"; exit 1
  fi
else
  echo "--- clean OCR ---" >&2; echo "$TEXT" >&2
  fail "3b1" "clean balloon missing"; exit 1
fi

pass "3b1"
