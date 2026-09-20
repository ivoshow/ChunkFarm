#!/usr/bin/env bash
# Crash-durability and anti-duplication test for ChunkFarm.
#
# Boot one stores a known amount, takes some out, and kills the JVM with
# Runtime.halt -- which skips shutdown hooks and onDisable entirely, the same way
# kill -9 or a hard crash would. Boot two checks what came back.
#
# The failure being hunted is duplication: a player's inventory is written by the
# server on its own schedule, so if the warehouse still held the withdrawn items after
# the crash, those items would exist twice.
#
# Runs twice. The second pass is a negative control with the urgent flush disabled,
# which must FAIL -- a test that cannot fail proves nothing.
#
# Usage:  ./testing/run-crash-test.sh /caminho/para/paper-26.2-126.jar
set -euo pipefail

PAPER_JAR="${1:-}"
if [[ -z "$PAPER_JAR" || ! -f "$PAPER_JAR" ]]; then
    echo "uso: $0 /caminho/para/paper-26.2-126.jar" >&2
    exit 1
fi
PAPER_JAR="$(realpath "$PAPER_JAR")"

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/chunkfarm-crash"
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
server-port=25603
spawn-protection=0
view-distance=4
simulation-distance=4
pause-when-empty-seconds=-1
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

echo ">> compilando o plugin de teste"
LIBS="$(find "$HOME/.gradle/caches/modules-2/files-2.1" "$SERVER/libraries" -name '*.jar' 2>/dev/null \
        | grep -v sources | tr '\n' ':')"
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
javac --release 25 -nowarn -cp "$API_JAR:$PLUGIN_JAR:$LIBS" \
      -d "$WORK/out" "$PROJECT/testing/crash/src/dev/ivoshow/crash/CrashPlugin.java"
cp "$PROJECT/testing/crash/res/plugin.yml" "$WORK/out/"
(cd "$WORK/out" && jar cf "$SERVER/plugins/ChunkFarmCrash.jar" .)

run_pass() {
    local label="$1" flush="$2"
    (cd "$SERVER" && rm -rf world world_nether world_the_end \
        plugins/ChunkFarmCrash plugins/ChunkFarm/data)
    if [[ -f "$SERVER/plugins/ChunkFarm/config.yml" ]]; then
        sed -i "s/^  flush-delay-seconds: .*/  flush-delay-seconds: $flush/" \
            "$SERVER/plugins/ChunkFarm/config.yml"
    fi
    (cd "$SERVER" && java -Xms1G -Xmx2G -jar paper.jar --nogui < /dev/null > a.log 2>&1) || true
    (cd "$SERVER" && java -Xms1G -Xmx2G -jar paper.jar --nogui < /dev/null > b.log 2>&1) || true
    echo
    echo "=== $label ==="
    sed 's/\x1b\[[0-9;]*m//g' "$SERVER/a.log" "$SERVER/b.log" | grep -E "\[CRASH\]" | sed 's/.*INFO]: //'
    grep -c "FALHOU" <(sed 's/\x1b\[[0-9;]*m//g' "$SERVER/a.log" "$SERVER/b.log") || true
}

# Pass 1 needs a config.yml to exist before it can be tweaked, so let the plugin
# generate one on the first boot of the first pass.
FAILS_OK=$(run_pass "PADRAO (flush 3s) -- deve passar" 3 | tail -1)
FAILS_CONTROL=$(run_pass "CONTROLE NEGATIVO (flush 600s) -- deve falhar" 600 | tail -1)

echo
echo "=============================="
if [[ "$FAILS_OK" == "0" && "$FAILS_CONTROL" != "0" ]]; then
    echo "OK: a retirada sobrevive ao crash, e o teste comprovadamente detecta quando nao sobrevive"
else
    echo "PROBLEMA: padrao=$FAILS_OK falha(s) (esperado 0), controle=$FAILS_CONTROL falha(s) (esperado >0)"
    exit 1
fi
