#!/usr/bin/env bash
# Load test for ChunkFarm.
#
# Builds 50 farms whose layer range is Y 0 to Y 200 -- 38.400 crops across three floors
# per chunk -- and measures what the harvest engine costs: its own time on the server
# thread, and what the server's TPS does around it. Then drops the chunk tickets to show
# what a farm in an unloaded chunk costs.
#
# Takes about four minutes: it waits for the TPS rolling average to clear the one-off
# cost of generating fifty chunks before it measures anything.
#
# Usage:  ./testing/run-stress-test.sh /caminho/para/paper-26.2-126.jar
set -euo pipefail

PAPER_JAR="${1:-}"
if [[ -z "$PAPER_JAR" || ! -f "$PAPER_JAR" ]]; then
    echo "uso: $0 /caminho/para/paper-26.2-126.jar" >&2
    exit 1
fi
PAPER_JAR="$(realpath "$PAPER_JAR")"

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/chunkfarm-stress"
SERVER="$WORK/server"
API_VERSION="26.2.build.126-stable"

echo ">> compilando o plugin"
(cd "$PROJECT" && ./gradlew build -q)
PLUGIN_JAR="$PROJECT/build/libs/ChunkFarm-1.0.0.jar"

mkdir -p "$SERVER/plugins" "$WORK/api"
cp -f "$PAPER_JAR" "$SERVER/paper.jar"
cp -f "$PLUGIN_JAR" "$SERVER/plugins/"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<'PROPS'
online-mode=false
level-name=world
level-seed=chunkfarm
max-players=2
spawn-protection=0
view-distance=6
simulation-distance=6
server-port=25601
pause-when-empty-seconds=-1
spawn-monsters=false
PROPS

API_JAR="$WORK/api/paper-api-$API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
    curl -sL -o "$API_JAR" \
        "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$API_VERSION/paper-api-$API_VERSION.jar"
fi

if [[ ! -d "$SERVER/libraries" ]]; then
    echo ">> primeira inicializacao (o servidor baixa as bibliotecas dele)"
    (cd "$SERVER" && timeout 240 java -jar paper.jar --nogui < /dev/null > boot.log 2>&1) || true
fi

echo ">> compilando o plugin de carga"
LIBS="$(find "$HOME/.gradle/caches/modules-2/files-2.1" "$SERVER/libraries" -name '*.jar' 2>/dev/null \
        | grep -v sources | tr '\n' ':')"
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
javac --release 25 -nowarn -cp "$API_JAR:$PLUGIN_JAR:$LIBS" \
      -d "$WORK/out" "$PROJECT/testing/stress/src/dev/ivoshow/stress/StressPlugin.java"
cp "$PROJECT/testing/stress/res/plugin.yml" "$WORK/out/"
(cd "$WORK/out" && jar cf "$SERVER/plugins/ChunkFarmStress.jar" .)

rm -rf "$SERVER/world" "$SERVER/world_nether" "$SERVER/world_the_end" "$SERVER/plugins/ChunkFarm"

echo ">> rodando (cerca de 4 minutos)"
(cd "$SERVER" && java -Xms2G -Xmx4G -jar paper.jar --nogui < /dev/null > stress.log 2>&1) || true

echo
sed 's/\x1b\[[0-9;]*m//g' "$SERVER/stress.log" | grep -E "\[STRESS\]" | sed 's/.*\[STRESS\] //'
