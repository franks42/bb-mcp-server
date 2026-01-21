#!/usr/bin/env bash
# nrepl-direct.sh - Direct nREPL CLI with history expansion disabled
#
# This wrapper disables bash history expansion (!) so you can safely use
# function names like swap!, reset!, init! and vars like !myatom.
#
# Usage: bb nrepl-direct eval "(swap! !myatom inc)" --port 7888
#        bb nrepl-direct load-local-file src/app.clj --nickname scittle-dev

set +H  # Disable history expansion for this script
exec bb scripts/nrepl_direct_cli.clj "$@"
