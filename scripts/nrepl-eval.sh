#!/usr/bin/env bash
# nrepl-eval.sh - Wrapper for bb nrepl-direct eval with history expansion disabled
#
# This script disables bash history expansion (!) before running eval,
# so you can safely use function names like swap!, reset!, init!, etc.
#
# Usage: bb nrepl-eval "(swap! my-atom inc)" --port 7888
#        bb nrepl-eval "(init! config)" --nickname scittle-dev

set +H  # Disable history expansion for this script
exec bb nrepl-direct eval "$@"
