# ChunkFarm

Colheita e replantio automáticos **por chunk** para Paper 26.2, com um armazém feito para
servidores que produzem milhões de itens por dia.

O jogador coloca um **Controlador de Fazenda** em qualquer lugar de um chunk. A partir
daí o chunk inteiro passa a ser varrido entre duas camadas de Y configuráveis: toda
plantação madura é colhida, replantada com a própria semente, e o resultado vai para o
armazém daquela fazenda.

- **Paper** 26.2 (`26.2.build.126-stable`) · **Java** 25+
- Sem dependências em runtime — o jar tem ~127 KB
- GriefPrevention é opcional

---

## Índice

- [Por que existe](#por-que-existe)
- [Instalação](#instalação)
- [Começando](#começando)
- [Comandos de jogador](#comandos-de-jogador)
- [Comandos de administrador](#comandos-de-administrador)
- [Permissões](#permissões)
- [Compartilhar uma fazenda](#compartilhar-uma-fazenda)
- [Culturas suportadas](#culturas-suportadas)
- [Configuração](#configuração)
- [Como funciona por dentro](#como-funciona-por-dentro)
- [Desempenho medido](#desempenho-medido)
- [Durabilidade e anti-duplicação](#durabilidade-e-anti-duplicação)
- [Compilar](#compilar)
- [Testes](#testes)
- [Limitações conhecidas](#limitações-conhecidas)

---

## Por que existe

Um baú guarda `54 × 64 = 3.456` itens. Isso não serve para uma farm que gera milhões por
dia.

O armazém do ChunkFarm **não é um inventário**. É um contador: um `long` por tipo de item.
Uma fazenda com 50 milhões de trigo ocupa uma linha no arquivo de dados e uma entrada de
mapa na memória. O limite por tipo é 10¹⁵ (mil trilhões), e por padrão não há limite
configurado.

Duas consequências:

- **A colheita nunca cria entidades `Item` no mundo.** Sem drop no chão, sem funil
  puxando, sem entidade para o servidor tickar. É a diferença entre uma farm que derruba
  o TPS e uma que não aparece no profiler.
- **A quantidade real aparece no nome do item**, tipo `Trigo ×1.234.567`. O número no
  canto do ícone foi removido de propósito: o cliente nunca desenha mais que um stack
  ali, então aquele `64` lia como se fosse o total e não era.

---

## Instalação

1. Coloque `ChunkFarm-1.0.0.jar` em `plugins/`.
2. Reinicie o servidor (não use `/reload`).
3. Entregue um controlador a alguém: `/cf entregar <jogador>`.

Os arquivos `config.yml` e `messages.yml` são gerados em `plugins/ChunkFarm/` no primeiro
boot. Ambos podem ser recarregados com `/cf recarregar`, sem reiniciar.

---

## Começando

1. Um administrador roda `/cf entregar Fulano`.
2. Fulano coloca o controlador em qualquer bloco do chunk que quer automatizar.
3. Clique direito no controlador abre o armazém.
4. O botão de comparador abre as configurações: quais culturas colher, até onde alcançar,
   e para onde vai a colheita.

Por padrão a fazenda cobre 7 camadas — do Y do controlador menos 3 até mais 3. Para uma
faixa grande, use o comando em vez do menu:

```
/cf camadas -50 256
```

---

## Comandos de jogador

Alias: `/chunkfarm`, `/cf` e `/fazenda`. Todos operam na fazenda do **chunk onde você
está**.

| Comando | O que faz | Exige |
|---|---|---|
| `/cf ajuda` | Lista os comandos disponíveis para você | — |
| `/cf abrir` | Abre o armazém | acesso |
| `/cf info` | Estado da fazenda: dono, camadas, culturas, armazém, tempo da última varredura | acesso |
| `/cf membros` | Quem pode usar esta fazenda | acesso |
| `/cf lista` | Suas fazendas, com localização e total guardado | — |
| `/cf colher` | Força uma colheita imediata, ignorando o orçamento do ciclo | gerente |
| `/cf camadas <min> <max>` | Define o alcance de Y de uma vez, ex. `/cf camadas -50 256` | gerente |
| `/cf confiar <jogador> [nível]` | Libera a fazenda para alguém | só o dono |
| `/cf desconfiar <jogador>` | Tira o acesso | só o dono |
| `/cf recolher` | Desfaz a fazenda e devolve o controlador | só o dono |

Níveis aceitos em `/cf confiar`: `acesso`, `retirada` (padrão) e `gerente`.

### O armazém

| Ação | Resultado |
|---|---|
| Clique esquerdo | Retira um stack |
| Clique direito | Retira uma unidade |
| Shift + clique | Enche o inventário com aquele item |
| Botão do funil | Puxa um stack de cada tipo até o inventário encher |

O que não couber no inventário volta para o contador. Nada se perde.

---

## Comandos de administrador

Todos exigem `chunkfarm.admin` e funcionam também pelo console.

| Comando | O que faz |
|---|---|
| `/cf entregar <jogador> [qtd]` | Entrega controladores. Quantidade de 1 a 64, padrão 1. O jogador precisa estar online |
| `/cf remover` | Apaga a fazenda do chunk atual **junto com o armazém**. Não devolve o controlador |
| `/cf recarregar` | Recarrega `config.yml` e `messages.yml` sem reiniciar |
| `/cf stats` | Números globais e o custo real do plugin |

`chunkfarm.admin` também dá acesso total a qualquer fazenda, em qualquer nível.

### Lendo o `/cf stats`

```
Fazendas: 214 (12 dormentes) | guardado: 48.221.906 | ciclos: 51.204 | blocos colhidos: 9.881.412
Custo: ultimo ciclo 3,90ms | media 4,14ms | pico 18,20ms | no ultimo ciclo: 31 trabalhadas, 183 puladas
Servidor: TPS 20,00 | tick medio 1,38ms | alcance LOADED
Protecao de terreno: GriefPrevention | confianca exigida Inventory
```

- **pico** é o número a vigiar. Se passar de ~25 ms, baixe `harvest.blocks-per-cycle`.
- **puladas** alto é normal e saudável: são fazendas em chunks descarregados, que custam
  uma checagem e nada mais.

---

## Permissões

| Permissão | Padrão | Para quê |
|---|---|---|
| `chunkfarm.use` | todos | Usar o comando e abrir fazendas próprias |
| `chunkfarm.place` | todos | Colocar um controlador |
| `chunkfarm.limit.bypass` | op | Ignorar o limite de fazendas por jogador |
| `chunkfarm.admin` | op | Acesso a qualquer fazenda e aos comandos administrativos |

---

## Compartilhar uma fazenda

O dono mantém uma lista de membros **dentro do próprio plugin** — não precisa de mais
nada instalado:

```
/cf confiar Fulano            entra como "retirada"
/cf confiar Fulano acesso     só olha o armazém, não tira nada
/cf confiar Fulano gerente    também muda configurações e pode quebrar o controlador
/cf desconfiar Fulano
```

Os níveis são cumulativos:

| Nível | Pode |
|---|---|
| `acesso` | Abrir o menu e ver o que está guardado |
| `retirada` | Tudo acima, mais tirar itens |
| `gerente` | Tudo acima, mais mudar culturas, camadas e destino, forçar colheita, e quebrar o controlador |

Mexer na lista de membros é sempre só do dono. Há um menu de membros (botão de cabeça nas
configurações): clique troca o nível, shift+clique remove.

### GriefPrevention

Opcional, por cima disso. Com ele instalado e `protection.use-land-trust: true` (padrão),
quem o terreno confia no nível configurado também abre a fazenda — então o
`/containertrust` que você já usa no terreno vale para a farm, sem confiar de novo.

Dois detalhes que importam:

- **Fora de uma claim, a fazenda é só do dono e da lista de membros.** Terreno não
  reivindicado não concede nada; se concedesse, qualquer um abriria qualquer farm em área
  livre.
- **Confiança de terreno nunca dá `gerente`.** Quem reconfigura ou desfaz a fazenda é o
  dono e quem ele nomeou explicitamente.

Se o GriefPrevention estiver com regras `Creative` no mundo, colocar o controlador vai
exigir uma claim antes — o ChunkFarm respeita o cancelamento e não registra a fazenda.

---

## Desfazer uma fazenda

Quebrar o controlador devolve o item **direto no inventário** de quem quebrou. Se o
armazém tiver conteúdo, a fazenda fica **dormente** em vez de sumir, e recolocar o
controlador no mesmo chunk devolve tudo — inclusive em outro ponto do chunk, com o
alcance de Y acompanhando.

Se o bloco sumir sem passar por isso (outro plugin, WorldEdit, um acidente), o dono tem
`/cf recolher`, que não depende de evento de bloco nenhum.

Controladores também são removidos da lista de blocos de qualquer explosão.

---

## Culturas suportadas

| Cultura | Comportamento |
|---|---|
| Trigo, Cenoura, Batata, Beterraba, Fungo do Nether, Cacau | Colhe no estágio máximo e volta ao estágio 0, consumindo uma semente do próprio drop |
| Frutas Silvestres | Colhe as frutas e volta o arbusto ao estágio 1, como um clique direito |
| Frutas Brilhantes | Tira as frutas, a trepadeira continua |
| Cana, Cacto, Bambu, Alga | Corta tudo acima da base e deixa a base rebrotar |
| Melancia, Abóbora | Colhe só o fruto que tem um caule apontando para ele |

Cada uma liga e desliga individualmente, por fazenda, no menu.

**O replantio sai da própria colheita:** uma semente é tirada dos drops antes do resto ir
para o armazém. A fazenda é neutra em sementes, não uma impressora delas. Quando o drop
não traz semente (trigo pode dropar zero), o plugin tenta tirar uma do armazém;
`replant.allow-free-seed` decide o que fazer se também não houver.

A fazenda colhe **qualquer** planta suportada dentro do chunk e da faixa de Y, inclusive
as que o mundo gerou sozinho — cana e bambu selvagens, e *cave vines* se a faixa alcançar
cavernas. Se não é o desejado, desligue a cultura ou estreite as camadas.

---

## Configuração

`plugins/ChunkFarm/config.yml`. Todo valor tem comentário no arquivo; o resumo:

### O que mais importa

| Chave | Padrão | Para quê |
|---|---|---|
| `harvest.blocks-per-cycle` | `25000` | **A válvula de TPS.** ~1 ms por 1000 unidades |
| `chunks.mode` | `LOADED` | Até onde o plugin alcança |
| `controller.max-per-player` | `3` | Limite de fazendas por jogador |
| `area.max-layers` | `384` | Teto de camadas (mundo inteiro) |
| `warehouse.delivery` | `WAREHOUSE` | Contador, ou dropar no chão |

### Até onde o plugin alcança

Uma fazenda só trabalha num chunk que o servidor tem na memória — o plugin nunca carrega
um chunk só para colher. `chunks.mode` decide o que conta:

| Modo | Quando roda | Custo |
|---|---|---|
| `LOADED` | Qualquer chunk já carregado, por qualquer motivo | Fazenda em chunk descarregado custa uma checagem e nada mais |
| `PLAYER_RADIUS` | Só a até `chunks.player-radius` chunks de um jogador | Mais restrito: ignora spawn e chunks que outros plugins seguram |
| `ALWAYS` | Sempre, mesmo com o servidor vazio | O plugin segura um ticket por chunk. **Faz o servidor tickar o chunk inteiro** — mobs, redstone, fluidos |

### Destino da colheita

| Modo | O que faz |
|---|---|
| `WAREHOUSE` | Vira contador. Nenhuma entidade no mundo |
| `DROP` | Os itens caem onde a planta estava, para funil, água ou minecart |

`DROP` existe porque algumas construções querem os itens no mundo, mas é caro por
definição: cada item vira uma entidade que o servidor tem que tickar, e some em 5 minutos
se ninguém recolher.

### Demais seções

- `controller` — material, nome e limite por jogador
- `worlds` — lista branca e lista negra de mundos
- `area` — camadas padrão e teto, e culturas iniciais
- `protection` — integração com terreno e limite de membros
- `warehouse` — capacidade, comportamento ao encher, badge do ícone
- `crops.column-keep-blocks` — quantos blocos ficam de pé em cana/cacto/bambu/alga
- `storage` — salvamento, flush de retiradas, backups e log de saques

---

## Como funciona por dentro

A cada ciclo (1 segundo por padrão), o plugin percorre uma fatia das fazendas em rodízio e
gasta um **orçamento total de blocos**. Duas fases:

**1. Varredura.** As posições das plantações são mapeadas a cada
`cache-refresh-seconds` e guardadas num array de `long` — posição empacotada, sem alocar
objeto por bloco. A leitura passa por um `ChunkSnapshot`, que devolve o material sem
alocação **e sabe quais seções de 16 blocos são só ar**: numa fazenda que vai de Y -50 a
256, o céu vazio custa uma comparação por seção em vez de 256 leituras por camada.

A varredura é *retomável*: se o orçamento acabar no meio, continua de onde parou no ciclo
seguinte, em vez de varrer o chunk num tick só. Plantar ou quebrar dentro da área revarre
na hora.

O intervalo de revarredura **se adapta ao custo medido**: uma fazenda cuja varredura
demora mais que o intervalo configurado espera o dobro do próprio tempo antes de repetir,
em vez de ficar varrendo para sempre e roubando orçamento da colheita.

**2. Colheita.** Percorre as posições em cache a partir de um cursor também retomável,
colhe o que estiver maduro e soma no armazém.

Se o servidor apertar, o único número que precisa mudar é `harvest.blocks-per-cycle`.

---

## Desempenho medido

`./testing/run-stress-test.sh` — **50 fazendas, faixa Y 0–200 (201 camadas cada), 38.400
cultivos**, mundo normal, após esperar a média de TPS limpar o custo de gerar os chunks.

| Cenário | Custo do plugin por ciclo | TPS |
|---|---|---|
| Tudo maduro de uma vez (pior caso) | 4,1 ms média / 18,2 ms pico | 19,99 |
| Regime, rebrota no ritmo vanilla | 3,4 ms média / 6,2 ms pico | 20,00 |
| Chunks descarregados, ninguém online | 0,05 ms | 20,00 |

Regra prática: **~1 ms por 1000 unidades de `harvest.blocks-per-cycle`**. O padrão de
25.000 dá ~25 ms no pior caso.

---

## Durabilidade e anti-duplicação

O armazém é um contador, não itens no mundo. Isso muda o modelo de falha: uma duplicação
não deixa rastro de item — o número simplesmente não desce.

**Retirada é salva na hora.** O inventário do jogador é gravado pelo servidor no ritmo
dele. Se o servidor caísse entre o jogador tirar 1 milhão de itens e o autosave de 5
minutos, os itens ficariam no inventário **e** no armazém. Por isso toda saída de item —
e toda mudança de fazenda ou membro — força uma gravação em `storage.flush-delay-seconds`
(3s), agrupando cliques num único arquivo.

**Gravação atômica.** Escreve num temporário e só então troca: um crash no meio nunca
deixa um `farms.yml` truncado.

**Backups rotativos** em `data/backups/`. A troca atômica protege contra arquivo
truncado; não protege contra um valor errado gravado corretamente.

**Guarda contra apagamento em massa.** Um salvamento que gravaria zero fazendas havendo
fazendas carregadas é abortado e o arquivo anterior preservado.

**Registro de saques** em `plugins/ChunkFarm/saques.log`: quem, de qual fazenda, o quê,
quanto, e quanto sobrou.

### Superfícies de duplicação fechadas

| Vetor | Tratamento |
|---|---|
| Crash entre retirada e autosave | Gravação forçada em 3s |
| Arrastar, shift-clique, tecla numérica, offhand no menu | Todo clique no menu é cancelado antes de qualquer coisa |
| Clonagem no modo criativo | `InventoryCreativeEvent` herda a HandlerList de `InventoryClickEvent`, então cai no mesmo cancelamento |
| Dois jogadores sacando ao mesmo tempo | Contador sincronizado, remoção clampeada; o segundo leva o que sobrou |
| Inventário cheio no meio do saque | O que não coube volta ao contador; se o contador recusar, cai no chão |
| `/cf recolher` repetido | O controlador é pago uma vez por bloco de pé |
| Quebra cancelada por outro plugin depois da nossa | Efeitos irreversíveis movidos para `MONITOR` |

---

## Compilar

```bash
./gradlew build
```

O jar sai em `build/libs/ChunkFarm-1.0.0.jar`. Compilado com `--release 25`, que é o que o
Paper 26.2 exige. Adventure/MiniMessage e fastutil já vêm com o servidor, então não há
shading.

Para subir um servidor com o plugin, há um `start.sh` de produção na pasta acima deste
projeto.

---

## Testes

**Unitários** — `./gradlew test` (20 testes): aritmética do armazém acima de
`Integer.MAX_VALUE`, limites por tipo, round-trip da chave de item, empacotamento de
posições com Y negativo, formatação, e integridade do YAML (chave com ponto, chave que o
YAML 1.1 lê como booleano, e toda chave de mensagem pedida pelo código).

Os três abaixo sobem um Paper de verdade e precisam do jar do servidor:

**Integração** — `./testing/run-integration-test.sh <paper.jar>` (40 verificações). Planta
um campo real num mundo gerado normalmente e confere o que o motor fez com o mundo e com o
armazém; depois reinicia para provar que os dados voltam do disco.

**Crash e duplicação** — `./testing/run-crash-test.sh <paper.jar>`. Mata a JVM com
`Runtime.halt`, que pula `onDisable` e shutdown hooks igual a um `kill -9`. Roda duas
vezes: a configuração padrão (deve passar) e um controle negativo com o flush desligado
(deve falhar).

**Carga** — `./testing/run-stress-test.sh <paper.jar>`. Os números da seção de desempenho
saem daqui.

**Servidor para jogar** — `./testing/run-dev-server.sh <paper.jar>`. Sobe um mundo de
testes em `localhost:25565` com campos maduros de todas as culturas, um chunk com três
andares de trigo e outro com trigo em Y -50, 0, 60, 120, 200 e 256. Quem entra recebe
criativo e 8 controladores; o primeiro a entrar também recebe op.

---

## Limitações conhecidas

- **As GUIs não foram verificadas com jogador conectado.** A matemática está coberta por
  teste, mas o clique em si nunca rodou numa sessão real.
- **`/cf confiar` só resolve quem já entrou no servidor** — usa o cache de perfis, sem
  consulta externa bloqueante.
- **`/cf entregar` exige o jogador online.**
- **A integração de proteção cobre GriefPrevention.** Towny, Lands e WorldGuard ainda não
  têm hook; neles a fazenda fica só no dono e na lista de membros, o que é seguro, só não
  compartilha pelo terreno. A interface `ProtectionHook` existe para adicionar mais.
- **Folia não é suportado.** O ciclo roda no `GlobalRegionScheduler`.
- **Trocar `controller.material` no config** faz as fazendas existentes ficarem dormentes
  na próxima varredura, porque o bloco no mundo deixa de bater com o configurado.
- **Torchflower e Pitcher Plant** não são suportados: mudam de bloco ao amadurecer em vez
  de só mudar de estágio.
- **O log de saques cobre a GUI**, que é por onde todo saque de jogador passa.
