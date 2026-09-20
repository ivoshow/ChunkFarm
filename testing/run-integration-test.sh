#!/usr/bin/env bash
# Integration test for ChunkFarm.
#
# Builds a throwaway "harness" plugin that plants a real field in one chunk, registers
# a farm over it and asserts what the harvest engine did to the world and to the
# warehouse. Then restarts the server to prove the data survives on disk.
#
# Usage:  ./testing/run-integration-test.sh /caminho/para/paper-26.2-126.jar
set -euo pipefail

PAPER_JAR="${1:-}"
if [[ -z "$PAPER_JAR" || ! -f "$PAPER_JAR" ]]; then
    echo "uso: $0 /caminho/para/paper-26.2-126.jar" >&2
    exit 1
fi
PAPER_JAR="$(realpath "$PAPER_JAR")"

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/chunkfarm-it"
SERVER="$WORK/server"
API_VERSION="26.2.build.126-stable"

echo ">> compilando o plugin"
(cd "$PROJECT" && ./gradlew build -q)
PLUGIN_JAR="$PROJECT/build/libs/ChunkFarm-1.0.0.jar"

echo ">> preparando o servidor em $SERVER"
mkdir -p "$SERVER/plugins" "$WORK/api" "$WORK/harness/out"
rm -rf "$SERVER/world" "$SERVER/world_nether" "$SERVER/world_the_end" \
       "$SERVER/plugins/ChunkFarm" "$SERVER/plugins/ChunkFarmHarness"
cp -f "$PAPER_JAR" "$SERVER/paper.jar"
cp -f "$PLUGIN_JAR" "$SERVER/plugins/"
echo "eula=true" > "$SERVER/eula.txt"
# Deliberately a normally generated world: real terrain fills the lower sections,
# which is what actually exercises the scanner. A superflat world is nearly all
# empty sections and flatters the section-skipping.
cat > "$SERVER/server.properties" <<'PROPS'
online-mode=false
level-seed=chunkfarm
level-name=world
max-players=2
spawn-protection=0
view-distance=4
simulation-distance=4
server-port=25599
PROPS

API_JAR="$WORK/api/paper-api-$API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
    echo ">> baixando paper-api $API_VERSION"
    curl -sL -o "$API_JAR" \
        "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$API_VERSION/paper-api-$API_VERSION.jar"
fi

# The server downloads its own libraries on first boot; reuse them for compiling.
echo ">> primeira inicializacao (baixa as bibliotecas do servidor)"
(cd "$SERVER" && timeout 180 java -Xms1G -Xmx2G -jar paper.jar --nogui < /dev/null > boot.log 2>&1) || true

echo ">> compilando o harness"
LIBS="$(find "$HOME/.gradle/caches/modules-2/files-2.1" "$SERVER/libraries" -name '*.jar' 2>/dev/null \
        | grep -v sources | tr '\n' ':')"
rm -rf "$WORK/harness/out"; mkdir -p "$WORK/harness/out"
javac --release 25 -nowarn -cp "$API_JAR:$PLUGIN_JAR:$LIBS" \
      -d "$WORK/harness/out" "$PROJECT/testing/src/dev/ivoshow/harness/HarnessPlugin.java"
cp "$PROJECT/testing/res/plugin.yml" "$WORK/harness/out/"
(cd "$WORK/harness/out" && jar cf "$SERVER/plugins/ChunkFarmHarness.jar" .)

echo ">> fase 1: colheita, replantio e dormencia"
(cd "$SERVER" && rm -rf world world_nether world_the_end plugins/ChunkFarm/data plugins/ChunkFarmHarness \
 && java -Xms1G -Xmx2G -jar paper.jar --nogui < /dev/null > phase1.log 2>&1)

echo ">> fase 2: o que voltou do disco"
(cd "$SERVER" && java -Xms1G -Xmx2G -jar paper.jar --nogui < /dev/null > phase2.log 2>&1)

echo
sed 's/\x1b\[[0-9;]*m//g' "$SERVER/phase1.log" "$SERVER/phase2.log" | grep -E "HARNESS"
echo
FAILS=$(sed 's/\x1b\[[0-9;]*m//g' "$SERVER/phase1.log" "$SERVER/phase2.log" | grep -c "FALHOU" || true)
echo "=============================="
if [[ "$FAILS" -eq 0 ]]; then
    echo "TODAS AS VERIFICACOES PASSARAM"
else
    echo "$FAILS VERIFICACAO(OES) FALHARAM"
    exit 1
fi
