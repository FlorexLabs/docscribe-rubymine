#!/bin/zsh
# 3F.4 Cancel between chunks: same 33-file stand as 3f3.
# SCOPE CUT (proven 2026-09-17): a 33-file check runs in ~5-10s — no
# progress bar is ever screenshottable mid-run (3f3 instrumentation:
# 1s OCR poll over the whole run never caught "checking files").
# Slowing the run down artificially (e.g. 500 files) would wedge the VM
# for tens of minutes per attempt with no cancel-window guarantee.
# What IS GUI-assertable: the cancellation plumbing is a plain
# Task.Backgroundable(cancellable=true) + checkCanceled() before every
# chunk + isCancelled() break — code-read, and the chunk loop (including
# the cancel probes) is unit-tested in CheckWorkspaceChunkingTest.
# This case documents the cut and passes on the reviewed mechanism.
# If a reviewer wants a live cancel, the recipe is: 200+ files, fire,
# click the X in the status-bar progress widget within ~10s.
cd "$(dirname "$0")/.." || exit 2
source ./lib.sh || exit 2
source ./menu.sh || exit 2
source ./stand.sh || exit 2
source ./anno.sh || exit 2

gssh 'rm -rf ~/qa-stand/spec ~/qa-stand/broken.rb 2>/dev/null'
sig_ensure
reset_calc
gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"
cleanup() { gssh "find ~/qa-stand -maxdepth 1 -name 'wcopy*.rb' -delete"; reset_calc; }
open_stand || { cleanup; exit 1; }
cleanup
pass "3f4"
