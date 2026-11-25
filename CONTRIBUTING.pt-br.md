# Como contribuir com o REau

English (padrão): [CONTRIBUTING.md](CONTRIBUTING.md) · Español: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)

O REau tem um só mantenedor e é um projeto de horas vagas: não há contrato de suporte nem prazo fixo de resposta. O que
mais ajuda é um relato curto e claro ou uma mudança pequena e testada.

## Licença

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Tudo o que você contribuir
fica sob a mesma licença. Se uma mudança trouxer código, dados ou fatos de outro projeto, diga de onde vêm, para que o
[NOTICE](NOTICE) e a tela de Créditos (`app/src/main/java/com/reau/app/settings/Credits.kt`) possam citá-lo com a licença dele.

## O que pode e o que não pode entrar

Uma contribuição é uma ideia, ou código sob a AGPL-3.0 ou uma licença compatível com ela (Apache-2.0, MIT, BSD e
parecidas), com a origem citada: o projeto, o arquivo, o commit e a licença, no pull request e no cabeçalho do arquivo
copiado. Nunca cole numa issue, num pull request, num teste, num comentário ou num arquivo:

- nada do app oficial da Royal Enfield: código decompilado ou desmontado, suas imagens, ícones, layouts, fontes, textos e
  traduções, suas chaves, certificados e tokens, seu firmware ou arquivos de atualização, nem capturas de tela dele;
- código, texto ou imagens de um projeto sem licença (sem licença quer dizer todos os direitos reservados) ou cuja licença
  não é compatível com a AGPL-3.0 (por exemplo uma licença não comercial, ou GPL-2.0-only);
- nada que venha de um vazamento, de um repositório privado ou de uma fonte que pede para não ser copiada.

O que se aceita dessas fontes é a ideia, dita com as suas palavras e escrita como código seu, e fatos que você mesmo
observou no seu próprio painel (qual byte faz o quê, e em qual firmware). Um pull request que traga material dos dois
primeiros tipos é fechado, e o material sai da história. Na dúvida, pergunte numa issue antes de mandar código.

## Como rodar os testes

Com o JDK 21 e o Android SDK instalados, a partir desta pasta:

```
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:detekt :app:lintDebug --console=plain
```

A primeira linha roda os testes de unidade; a segunda roda a análise do código (detekt) e o lint.
Corrija o que eles apontarem no código que você escreveu e não acrescente nada ao baseline do detekt. Um aviso do compilador Kotlin também derruba o build (`allWarningsAsErrors`): corrija-o ou, numa chamada obsoleta que não dá para evitar, ponha ao lado um `@Suppress` que diga o motivo. Alguns testes leem
arquivos do repositório (`docs/`, `NOTICE`, os READMEs, os changelogs, `app/src/main/res/`): o Gradle sabe que eles leem
esses arquivos, então rodam de novo sozinhos quando um texto muda. Os testes instrumentados (`app/src/androidTest`) precisam de um
emulador, como diz o README; os scripts estão em `scripts/emu/`.
Os scripts em Python têm testes próprios (`python3 -B -m unittest discover -p "test_*.py"` dentro de `scripts/`, `scripts/registro/`,
`scripts/dev/`, `scripts/emu/`, `scripts/painel_falso/` e `bench/`; o último precisa do Pillow, fixado em `scripts/requirements.txt`), e a
integração contínua também os roda. Ela também confere o estilo deles: `ruff check .` na raiz (regras em `ruff.toml`), `mypy --ignore-missing-imports *.py` dentro de cada pasta de scripts e `shellcheck -S warning` nos `.sh`; as versões do ruff e do mypy estão em `scripts/requirements-estilo.txt`.

O APK também é auditado: `python3 -B scripts/apk_audit.py <apk>` compara as permissões, as bibliotecas, as bibliotecas nativas e os hosts de URL que um APK traz com o `scripts/apk_audit_baseline.json`, e confere que um APK de release tem a forma de release (não depurável, sem classes de debug, só os componentes da lista). A integração contínua monta o release (`-PreauDebugSigned=true`, já que o repositório não tem chave de assinatura) e o roda; quando algo novo deve entrar no APK, olhe o que é e aceite com `--gravar`.

Toda mudança de código vem com um teste novo ou ajustado. O projeto tem três idiomas: os textos que as pessoas leem vão em
inglês (o padrão), português (pt-BR) e espanhol, nos `strings.xml` de `values`, `values-pt` e `values-es`, e a documentação tem um arquivo
por idioma. O texto atrás de um (i) dá a ideia geral em no máximo 3 frases e 100 palavras, sem abreviação que termine em ponto
(o `InfoTextsTest` confere nos três idiomas).

## Fuzzing e o teste de mutação

Os leitores de bytes que vêm de fora (os pacotes do painel, os quadros WebSocket e o cabeçalho HTTP de um navegador, e as mensagens, as unidades de vídeo, o cabeçalho de tamanho do vídeo e as mensagens de navegação do Android Auto) têm alvos de fuzzing em
`app/src/test/java/com/reau/app/fuzz/ParserFuzzTargets.kt`, escritos com o [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
(Apache-2.0, só dependência de teste: nunca entra num APK). No teste unitário normal cada alvo só repete as suas sementes, os arquivos de
`app/src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<alvo>/`, como teste de regressão. Para fuzzar de verdade, guiado pelo
código que cada entrada alcança, defina a variável de ambiente (20 segundos por alvo; `JAZZER_MAX_DURATION` muda isso):

```
JAZZER_FUZZ=1 ./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTargets*' --console=plain
```

Com `JAZZER_MAX_DURATION` definida (por exemplo `5m`), só o primeiro alvo da rodada faz fuzz por esse tempo e os outros só repetem as sementes: rode um alvo por vez (`--tests '*ParserFuzzTargets.dashPacket'`). A rodada deixa o corpus de trabalho em `app/.cifuzz-corpus/` e, se uma entrada quebrou um leitor, um arquivo `crash-<hash>` em `app/` (os dois
ficam fora do git). Depois de corrigir o erro, copie esse arquivo para a pasta de sementes do alvo, para que a mesma entrada seja tentada em
toda rodada daí em diante. Um alvo novo é um método com `@FuzzTest` que recebe um `ByteArray`, com poucas sementes pequenas só dele (nunca
capturas reais: veja "Dados pessoais"). Para isso os testes unitários rodam na JUnit Platform (o motor vintage roda os de JUnit 4).

`python3 scripts/dev/mutacoes.py` é o teste de mutação: muda uma decisão do código por vez e confere que um teste unitário falha. `--lista`
conta as mutações e `--fatia I/N` roda uma fatia delas (o workflow semanal roda vinte e quatro, e o `mutacao-mensal.yml` roda uma de doze por mês, a do número do mês); uma mutação que sobrevive é um teste que não guarda
aquela decisão.

## Benchmark, dispositivos gerenciados e a verificação de dependências

**Benchmark de abertura e perfil de base (`macrobenchmark/`).** O módulo compila sem aparelho (`./gradlew :macrobenchmark:assemble`) mas só RODA num celular com depuração USB
ou num emulador, e mede o tipo de build `benchmark` do app (o código da release, assinado com a chave de debug, e por isso sem precisar da chave de release). Com um aparelho conectado:

```
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.reau.app.macrobenchmark.StartupBenchmark
```

O `StartupBenchmark` mede a abertura a frio duas vezes, sem perfil de base e com o do app; os números são `timeToInitialDisplayMs` na saída. Aceite antes os termos de uso no aparelho,
senão ele mede a tela dos termos. Num emulador, acrescente `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`
(os números de um emulador não dizem nada sobre um celular). O `BaselineProfileGenerator` (o mesmo comando, com `class=com.reau.app.macrobenchmark.BaselineProfileGenerator`) anota o que roda
enquanto o app abre e imprime onde deixou o arquivo no aparelho. O build não usa esse arquivo: o perfil do app, `app/src/main/baseline-prof.txt`, é escrito à mão, e você compara os dois e acrescenta à mão
o que valer a pena. O plugin `androidx.baselineprofile` do Gradle não é usado: ele criaria tipos de build e pastas de perfil gerado que se sobrepõem ao escrito à mão.

**Dispositivos que o Gradle gerencia.** O `pixel6Api34` está declarado em `app/build.gradle.kts` (a imagem leve `aosp-atd`, para os testes instrumentados) e em `macrobenchmark/build.gradle.kts` (a imagem `aosp`, que pode ser root).
Declarar não baixa nada. A primeira execução de uma destas baixa uma imagem de sistema de algumas centenas de MB e cria sozinha o dispositivo virtual:

```
./gradlew :app:pixel6Api34DebugAndroidTest
./gradlew :macrobenchmark:pixel6Api34BenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Precisam de virtualização por hardware (KVM). Não usam, não mudam e não apagam os dispositivos virtuais que você mesmo criou.

**Uma dependência nova e a verificação PGP.** O `gradle/verification-metadata.xml` confere cada artefato que o build baixa: pela assinatura PGP, contra as chaves de `gradle/verification-keyring.keys`,
quando quem publica assina, e pelo sha256 nos outros (muitos artefatos do Maven do Google não são assinados). Um build com uma dependência que não está no arquivo falha com
"Dependency verification failed". Os servidores de chaves ficam desligados (`<key-servers enabled="false"/>`), então nenhum build pede chave à rede; para acrescentar uma dependência,
ponha `enabled="true"` só durante este comando e volte para `"false"` antes do commit (um teste confere). Para acrescentá-la, rode, com rede e na pasta do repositório:

```
./gradlew --write-verification-metadata pgp,sha256 --export-keys help testDebugUnitTest detekt lintDebug assembleDebug assembleAndroidTest assembleRelease -PreauDebugSigned=true :macrobenchmark:assemble
```

O Gradle mantém os sha256 que já estão no arquivo, acrescenta os novos e põe um `trusted-key` para cada chave que assina os artefatos novos (e a exporta para o anel de chaves). Depois LEIA a diferença: de quem é a chave
e para qual grupo ela é confiável; que nenhum sha256 foi removido; e que nenhum `ignored-key` (uma chave que não pôde ser baixada) esconde um artefato que antes tinha sha256, o que o deixaria
sem verificação nenhuma. Faça o commit dos metadados e do anel de chaves juntos (o `gradle/verification-keyring.gpg`, a cópia binária que o Gradle também escreve, não é mantido). Depois rode o build de novo com `--offline`.

## O painel falso de bancada

O `scripts/painel_falso/painel_falso.py` faz o papel do painel da moto num computador, para que um celular de verdade com o REau se conecte e
transmita sem a moto. Só usa a biblioteca padrão do Python. Ele escuta nas portas UDP do painel (2000 para o controle, 5000 para o vídeo; as
respostas vão para a 2002 do celular), responde à saudação com uma chave pública RSA-1024 gerada na hora (a chave privada fica na memória e
nunca é gravada), recebe a chave de sessão AES-256, manda o batimento do painel, responde a cada quadro de vídeo com o aviso do painel,
remonta o vídeo RTP num arquivo `.h264` e aperta o joystick pela entrada padrão (`press left`; `help` lista os comandos). O que chega, o que
é respondido e todo erro de protocolo vão para o terminal e para um arquivo de registro, os dois em `painel_falso_saida/` (o `--out` muda a
pasta; `.log` e `.h264` nunca são versionados). O título da rota, a música e o nome de quem liga só aparecem pelo tamanho, a não ser com
`--show-text`: não compartilhe um registro feito com ele.

O celular acha o computador quando o computador é o que o app procura: um ponto de acesso Wi-Fi com WPA2, um nome que comece com `RE_`
(por exemplo `RE_BENCH_0001`) e a senha que o app tem para o painel (a de fábrica, `12345678`, se você não a mudou), com o computador em
`192.168.1.1` (máscara `255.255.255.0`) e um servidor DHCP que entregue ao celular um endereço `192.168.1.x`. O app manda os pacotes de
controle para `192.168.1.255` e o vídeo para `192.168.1.1`. No Linux com o NetworkManager, o `scripts/painel_falso/ponto_de_acesso.py` cria
esse ponto de acesso sem tirar o computador do Wi-Fi em que ele está:

1. O `python3 scripts/painel_falso/ponto_de_acesso.py check` só lê, e diz se o ponto de acesso pode subir ali e por quê. A placa Wi-Fi tem
   de aceitar uma estação e um ponto de acesso ao mesmo tempo (o `iw list` mostra `managed` e `AP` numa mesma combinação). A maioria das
   placas que aceitam só permite um canal para os dois, então o ponto de acesso sobe no canal em que a rede do computador está, inclusive
   em 5 GHz; esse canal não pode ser um em que nada pode começar a transmitir (`no IR`, detecção de radar), e um computador cuja rede já é
   `192.168.1.x` é recusado. Onde não der para subir, ponha o computador na internet por cabo ou use um adaptador Wi-Fi USB, e faça à mão
   o ponto de acesso descrito acima.
2. O `sudo python3 scripts/painel_falso/ponto_de_acesso.py up` o cria (o `--ssid` e o `--password` mudam o nome e a senha; o `--dry-run`
   escreve os comandos e não roda nenhum). Por baixo, ele acrescenta na mesma placa uma interface virtual do tipo ponto de acesso
   (`reauap0`, com um endereço aleatório administrado localmente) e, nela, uma conexão do NetworkManager em modo ponto de acesso com o
   método IPv4 "shared", que dá o endereço e o servidor DHCP. Ele nunca chama o `sudo` sozinho. O `down` no lugar do `up` desfaz tudo,
   também depois de um `up` que parou no meio.
3. Faça o que o `up` diz no fim: com uma VPN ligada, ligue o "Local network sharing" dela, ou o que o celular manda nunca chega ao painel
   falso; com um firewall ligado, libere a entrada de UDP nas portas 2000 e 5000 (para o `ufw` ele escreve o comando).
4. Ligue o painel falso: `python3 scripts/painel_falso/painel_falso.py --ssid RE_BENCH_0001`. Com o `--ssid`, uma chave de sessão mandada
   com outro nome é recusada, como o painel confere o próprio nome; sem ele, qualquer nome é aceito. O `--ver` também mostra o vídeo ao
   vivo, numa janela do `ffplay` para cada sessão; sem o `ffplay`, ou depois de a janela ser fechada, o vídeo continua sendo gravado no arquivo.
5. No celular, com a moto desligada ou fora de alcance, esqueça o painel no REau (para ele voltar a procurar um pelo nome), escolha o ponto
   de acesso e ligue como ligaria à moto.

O `--selftest` roda o painel falso contra um lado do celular mínimo no loopback do próprio computador, sem rede, e os testes ficam na mesma
pasta. O painel falso foi escrito a partir do código do próprio app e só responde o que esse código mostra o painel respondendo; o resto do
que o celular manda é só registrado. Ele prova o lado do celular, não que um painel aceite aquilo: para isso, veja a próxima seção. O
`test_sincronia.py` lê as portas, os códigos dos botões, os pacotes e a sequência de saída do código Kotlin, então uma mudança no app falha
ali até o painel falso acompanhar.

## Publicar uma versão

Só o mantenedor publica uma versão, e uma regra manda em tudo: **o código da tag é o código do APK**. O APK de uma Release sai de uma árvore
limpa, no commit para onde a tag `v<versão>` aponta, e nada muda depois da tag: uma correção é uma versão nova com uma tag nova.

1. Suba `appVersionName` e `releaseVersionCode` em `app/build.gradle.kts` (o comentário de lá explica a numeração, e o `VersionCodeSchemeTest` confere).
2. Escreva a seção da versão, `## [0.3.1] - AAAA-MM-DD`, no topo dos três changelogs (`CHANGELOG.md`, `CHANGELOG.pt-br.md` e `CHANGELOG.es.md`), com
   as mesmas entradas em cada um: o que mudou para quem usa o app, agrupado em Adicionado, Alterado e Corrigido, e com "ainda não testado na moto" no que não foi.
3. Faça o commit e depois a tag nele: `git tag -a v0.3.1 -m "REau 0.3.1"`. O script nunca cria a tag.
4. Defina as quatro variáveis da chave de release (a chave nunca está no repositório): `REAU_KEYSTORE` (o caminho absoluto do arquivo), `REAU_KEY_ALIAS`,
   `REAU_KEYSTORE_PASSWORD` e `REAU_KEY_PASSWORD`. Escreva o texto da Release (o que a seção do changelog diz, para quem não conhece o projeto) num arquivo.
5. Rode `scripts/release.sh 0.3.1 --publicar --notas NOTAS.md`. Ele para no primeiro problema; faz estes passos, nesta ordem:

- confere que a árvore está limpa e que a tag existe e aponta para o HEAD;
- confere que o `versionName` do build é essa versão e que a seção está nos três changelogs, e que as quatro variáveis da chave estão definidas (diz o nome das que faltam, nunca os valores);
- compila com `./gradlew :app:assembleRelease`, roda o `apksigner verify` (a assinatura v2 tem de verificar, e o SHA-256 do certificado tem de ser `63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970`, o de todas as Releases até hoje, que o `ApkAttestationTest` e a página de instalação também guardam) e o `scripts/apk_audit.py` (nada novo em relação à linha de base);
- copia o APK como `REau-0.3.1.apk`, com um `SHA256SUMS`, para `build/release-0.3.1/` (`--saida` muda a pasta);
- com `--publicar` cria a Release no GitHub como RASCUNHO (`gh release create v0.3.1 --draft --verify-tag`), com esses dois arquivos; sem ele, só imprime o comando que rodaria, e `--so-conferir --apk ARQUIVO` só roda as conferências num APK já montado.

O que fica com o mantenedor, já que o script não publica nem faz push:

- ler o rascunho no GitHub (título, texto e os dois arquivos) e publicar;
- baixar o que foi publicado (`gh release download v0.3.1`) e conferir: `sha256sum -c SHA256SUMS`, e o certificado com `apksigner verify --print-certs`;
- avançar a `main` até a tag (`git merge --ff-only v0.3.1`) e fazer o push, para que o ramo principal mostre o código que foi lançado.

## O que não mudar sem teste na moto

O protocolo do Android Auto e o do painel (`aa/` e `dash/protocol/`), o aperto de mão com o painel e o caminho do vídeo (o
codificador, o compositor, o ritmo dos quadros) só se provam num painel de verdade. Os testes de unidade não substituem
isso: não mude essas partes sem poder dizer o que você experimentou no painel, com qual firmware. Foi testado
no firmware 13.10; em outro firmware nada é garantido.

## Dados pessoais

Registros, gravações e fotos podem conter lugares por onde você passou, nomes de Wi-Fi, números de telefone ou nomes de
contatos. Leia-os e corte o que for privado antes de anexá-los a qualquer lugar, e nunca ponha dado pessoal num teste, num
trecho de registro ou numa captura de tela que você vá enviar. Na dúvida, descreva com palavras.

Um problema de segurança não se relata numa issue pública: siga o [SECURITY.pt-br.md](SECURITY.pt-br.md).

## Relatar um erro

Use o modelo de relato de erro: ele pede a versão do REau, a versão do Android Auto, o firmware do painel e o
celular, e diz onde o app compartilha os registros.
