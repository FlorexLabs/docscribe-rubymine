#!/bin/zsh
# 3H.7 i18n: RU bundle has the 6 menu strings; EN bundle matches the code
# defaults; checkboxes/tooltip stay English (no RU keys for them).
# GUI language switch needs an IDE restart + locale dance — out of scope
# for the driver (3H checklist marks the BEHAVIOR, and the bundles are the
# mechanism). Assert the bundle contents statically.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2

RU="../../src/main/resources/messages/DocScribeBundle_ru.properties"
EN="../../src/main/resources/messages/DocScribeBundle.properties"
RC=0
for pat in "Проверить текущий файл" "Проверить весь проект" "Применить безопасные правки" "Применить все правки" "Обновить типы из RBS" "Диагностика DocScribe"; do
  grep -qF "$pat" "$RU" || { print -r -- "MISS ru: [$pat]" >&2; RC=1; }
done
for pat in "Check Current File" "Check Entire Workspace" "Apply Safe Fixes" "Apply Aggressive Fixes" "Update Types from RBS" "DocScribe Doctor"; do
  grep -qF "$pat" "$EN" || { print -r -- "MISS en: [$pat]" >&2; RC=2; }
done
# Checkboxes + tooltip NOT localized (must stay absent from RU bundle).
for pat in "Hide comments by default" "Warn on invalid YARD" "Highlight YARD types"; do
  if grep -qF "$pat" "$RU"; then print -r -- "UNEXPECTED ru key: [$pat]" >&2; RC=3; fi
done
if [[ $RC -ne 0 ]]; then fail "3h7" "bundle mismatch (rc=$RC)"; exit 1; fi
pass "3h7"
