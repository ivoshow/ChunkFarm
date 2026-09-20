#!/usr/bin/env bash
# Starts a Paper server with ChunkFarm and a sandbox world you can actually join.
#
# Builds a "playground" plugin that levels a patch of naturally generated terrain,
# plants a field of ready crops on it, and gives every joining player op, creative and
# a handful of controllers. Normal world generation on purpose -- superflat hides how
# the scanner behaves with real terrain underneath.
#
# Usage:  ./testing/run-dev-server.sh /caminho/para/paper-26.2-126.jar [porta]
set -euo pipefail

PAPER_JAR="${1:-}"
PORT="${2:-25565}"
if [[ -z "$PAPER_JAR" || ! -f "$PAPER_JAR" ]]; then
    echo "uso: $0 /caminho/para/paper-26.2-126.jar [porta]" >&2
    exit 1
fi
PAPER_JAR="$(realpath "$PAPER_JAR")"

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/chunkfarm-dev"
SERVER="$WORK/server"
API_VERSION="26.2.build.126-stable"

echo ">> compilando o plugin"
(cd "$PROJECT" && ./gradlew build -q)
PLUGIN_JAR="$PROJECT/build/libs/ChunkFarm-1.0.0.jar"

mkdir -p "$SERVER/plugins" "$WORK/api"
cp -f "$PAPER_JAR" "$SERVER/paper.jar"
cp -f "$PLUGIN_JAR" "$SERVER/plugins/"
echo "eula=true" > "$SERVER/eula.txt"
if [[ ! -f "$SERVER/server.properties" ]]; then
    cat > "$SERVER/server.properties" <<PROPS
online-mode=false
level-name=world
level-seed=chunkfarm
motd=ChunkFarm - campo de testes
max-players=5
spawn-protection=0
view-distance=8
simulation-distance=8
server-port=$PORT
allow-flight=true
enforce-secure-profile=false
gamemode=creative
difficulty=peaceful
spawn-monsters=false
pause-when-empty-seconds=-1
PROPS
fi

API_JAR="$WORK/api/paper-api-$API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
    echo ">> baixando paper-api $API_VERSION"
    curl -sL -o "$API_JAR" \
        "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$API_VERSION/paper-api-$API_VERSION.jar"
fi

if [[ ! -d "$SERVER/libraries" ]]; then
    echo ">> primeira inicializacao (o servidor baixa as bibliotecas dele)"
    (cd "$SERVER" && timeout 240 java -jar paper.jar --nogui < /dev/null > boot.log 2>&1) || true
fi

echo ">> compilando o mundo de testes"
LIBS="$(find "$HOME/.gradle/caches/modules-2/files-2.1" "$SERVER/libraries" -name '*.jar' 2>/dev/null \
        | grep -v sources | tr '\n' ':')"
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
javac --release 25 -nowarn -Xlint:-removal -cp "$API_JAR:$PLUGIN_JAR:$LIBS" \
      -d "$WORK/out" "$PROJECT/testing/playground/src/dev/ivoshow/playground/PlaygroundPlugin.java"
cp "$PROJECT/testing/playground/res/plugin.yml" "$WORK/out/"
(cd "$WORK/out" && jar cf "$SERVER/plugins/ChunkFarmPlayground.jar" .)

# A fresh world every run, so the field is always in a known state.
rm -rf "$SERVER/world" "$SERVER/world_nether" "$SERVER/world_the_end" \
       "$SERVER/plugins/ChunkFarm" "$SERVER/plugins/ChunkFarmPlayground"

echo
echo ">> subindo o servidor -- conecte em localhost:$PORT (modo offline, qualquer nick)"
echo ">> Ctrl+C ou o comando 'stop' para encerrar"
echo
cd "$SERVER" && exec java -Xms2G -Xmx4G -jar paper.jar --nogui
