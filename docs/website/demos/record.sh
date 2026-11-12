#!/usr/bin/env bash
# Records the front page demos (see README.md), with a released version of Almond, whose
# kernels get installed in a temporary directory. Arguments are passed to record.mjs.
#
# Requires cs (coursier), uv, Node.js, ffmpeg, and Chrome or Chromium.
set -euo pipefail

demos_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$demos_dir/../../.." && pwd)"

almond_version="${ALMOND_VERSION:-0.15.0}"
scala_version="${SCALA_VERSION:-2.13.18}"
cs="${CS:-cs}"

jupyter_dir="$(mktemp -d)"
trap 'rm -rf "$jupyter_dir"' EXIT

# Spark accesses JDK internals, that need to be opened on Java 17 and later
add_opens=""
for pkg in java.lang java.lang.invoke java.lang.reflect java.io java.net java.nio java.util \
  java.util.concurrent java.util.concurrent.atomic sun.nio.ch sun.nio.cs sun.security.action \
  sun.util.calendar; do
  add_opens="$add_opens --add-opens=java.base/$pkg=ALL-UNNAMED"
done

# --use-notebook-coursier-logger: show dependency downloads in the notebook, rather than as
# "Downloading ..." / "Downloaded ..." lines on stderr
"$cs" launch --use-bootstrap "almond:$almond_version" --scala "$scala_version" -- \
  --install --force --id scala213 --display-name "Scala 2.13" \
  --use-notebook-coursier-logger \
  --jupyter-path "$jupyter_dir/kernels" \
  --env "JDK_JAVA_OPTIONS=${add_opens# }"
# Almond 0.15.0 depends on jvm-repr 0.4.0, that's only published on JitPack (later versions of
# Almond vendor its sources)
"$cs" launch --use-bootstrap -r jitpack "sh.almond:echo_3:$almond_version" -- \
  --install --force --jupyter-path "$jupyter_dir/kernels"

[ -d "$demos_dir/node_modules" ] || (cd "$demos_dir" && npm ci --no-audit --no-fund)

# plotly brings the jupyterlab-plotly extension, that renders plotly-scala plots
JUPYTER_PATH="$jupyter_dir" node "$demos_dir/record.mjs" \
  --jupyter-cmd "uv run --project $repo_root/examples --frozen --with plotly jupyter lab" \
  --kernel scala213 \
  "$@"
