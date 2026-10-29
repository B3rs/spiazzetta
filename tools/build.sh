#!/usr/bin/env bash
set -euo pipefail
source /home/bers/Projects/mypiazzetta-app/tools/env.sh
cd /home/bers/Projects/mypiazzetta-app
gradle "$@"
