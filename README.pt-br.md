# REau

English (padrão): [README.md](README.md) · Español: [README.es.md](README.es.md)

Aplicativo Android que leva o **Android Auto do próprio celular** para o painel TFT da moto, pela rede Wi-Fi do
painel, com a tela do celular apagada. O REau não desenha mapa nenhum: repete no painel o que o app de navegação
(Waze, Google Maps) desenha no Android Auto.

> **Compatibilidade:** funciona com o painel **Tripper Dash**; foi verificado só com o firmware 13.10 do painel, e em
> outro firmware nada é garantido. O REau é um projeto independente, sem afiliação nem endosso da Royal Enfield, do
> Google, do Waze ou da Meta. Royal Enfield, Tripper, Android Auto e Waze pertencem aos seus donos.

## Como funciona

```
App de navegação ──▶ Android Auto do celular ──(vídeo H.264 por 127.0.0.1)──▶ REau
                                                                              │ decodifica, recorta, reduz
                                                                              ▼
                                                       codificador 528×304 ──RTP──▶ painel da moto
```

O REau se apresenta ao Android Auto como a central de um carro, usando o servidor da unidade principal que o
próprio Android Auto oferece no modo de desenvolvedor. Ele recebe o vídeo, ajusta a imagem à tela do painel e manda
pela rede Wi-Fi do painel.

## O que faz

- Mostra no painel a tela do Android Auto, com o enquadramento ajustável (o dos apps de navegação e o das outras telas,
  menu de apps incluído).
- Manda ao painel a manobra, a distância, a hora de chegada e o tempo restante da rota.
- Manda também a música tocando (quando a página do joystick tem um controle de música), as chamadas e o clima, para os
  cartões do painel.
- Usa o joystick do guidão para comandar o Android Auto (mapa, menu, música, chamadas e Gemini, que vem ligado e passa a funcionar quando você libera o microfone): tocando, a esquerda atende e a
  direita ou o centro recusam; em ligação, o centro desliga, a direita aumenta o volume e a esquerda diminui, e cima e baixo não fazem nada (com o aviso de chamada ligado, o padrão). Os toques também podem ser "Aumentar volume" e "Diminuir volume", "Alternar mapa e música", "Abrir mapa" e "Abrir música" (nenhum vem configurado), e o título do painel mostra o nome de cada ação por 1,5 s ("Nome da ação no painel", ligado por padrão); ainda não testado na moto.
- Um anel de informações (vem ligado, com uma chave): chuva, vento, tempestade, UV, qualidade do ar, o sol, as temperaturas do dia e a bateria, o sinal e o GPS do celular, uma coisa de cada vez (de fábrica com todos os cartões; você desliga os que não quiser). De fábrica ele é escrito no título da faixa dourada do painel ("Informações no título (no lugar do anel)"); com isso desligado, é desenhado sobre a imagem, à direita do painel. Precisa da localização e da internet para o clima; a tela de clima também mostra a previsão, com a chuva em milímetros (ainda não testado na moto).
- Música com o Spotify: com o Spotify na frente e tocando, esquerda, direita e centro do joystick são faixa anterior, próxima e tocar/pausar, e a faixa dourada do painel mostra a música, o artista e os três botões (ainda não testado na moto).
- Um assistente de encaixe (tela do Android Auto > "Assistente de encaixe"; abre sozinho na primeira vez): ajuda um celular novo a conferir se a seta da manobra e a velocidade aparecem inteiras no painel. Ainda não foi testado em outros celulares.
- Avisos para o piloto no celular e no painel (Wi-Fi desligado, celular quente, Wi-Fi do painel travado, painel sem resposta, outro app usando a conexão do painel, esperando o Android Auto) e uma página de Dicas; os avisos de painel que para de responder e de outro app na conexão do painel ainda não foram testados na moto. Os avisos falados no fone são opcionais.
- Um aviso de volume no alto do vídeo do painel (vem ligado, com uma chave): uma pílula pequena com o volume da música ou da chamada sempre que ele muda, pelo celular, por um intercomunicador Bluetooth ou pelo joystick, no lugar do volume no título; depois de um toque de "Aumentar volume" ou "Diminuir volume" no joystick, por 5 s ele só aceita essas duas ações.
- Um aviso de chamada no alto do vídeo do painel (vem ligado, com uma chave): uma pílula com quem está ligando, as dicas do joystick e o tempo da chamada, no lugar da tela de chamada do painel, que cobre quase todo o vídeo; com ele ligado, durante uma chamada a direita e a esquerda são o volume da chamada e cima e baixo não fazem nada.
- Fala português, inglês e espanhol: segue o idioma do celular, e há um seletor nos Ajustes.
- Para o trânsito de mão inglesa, "Lado da via" (Automático, Mão direita ou Mão inglesa) diz ao painel para que lado virar onde o Android Auto não diz (ainda não testado na moto); e "Anunciar como moto" (vem ligado) diz ao Android Auto que o REau é a central de uma moto, então na primeira vez ele pode pedir de novo as permissões no celular.
- Ajusta a qualidade do vídeo pela distância da próxima manobra e pela velocidade, no ritmo que você escolhe: Original (o de fábrica, 4 e 2 fps, o padrão) ou REau (6 e 3 fps, mais fluido). O ritmo se escolhe em Ajustes > Perfis de vídeo > "Ritmo do vídeo". A imagem é fixa em 528×304, com no máximo 400 kbps e um quadro-chave por segundo, e o REau nunca passa de 6 fps: em testes na moto, acima disso o painel começou a falhar.
- Liga com um toque longo no ícone do REau ("Ligar") ou por um bloco das Configurações rápidas, para ligar em casa, antes do capacete (só com o celular desbloqueado).
- Opcional: mostra o Android Auto num navegador da rede local, protegido por PIN.
- Guarda um registro de diagnóstico no armazenamento interno do app (ligado por padrão, com uma chave nos Ajustes), para
  analisar no computador (`scripts/registro/`); nada sai do celular a não ser que você o compartilhe. Gravar o vídeo e as
  sessões é opcional (Ajustes > Depuração): vem desligado no app que você instala para andar e ligado na build de debug; as gravações ficam
  no celular, saem pelos mesmos dias e pelo mesmo limite de tamanho do registro e guardam o mapa, a rota e as mensagens do Android Auto.

## Requisitos

- Celular com Android 10 ou mais novo e o **Android Auto** instalado.
- O modo de desenvolvedor do Android Auto ligado, com o "servidor da unidade principal". O REau abre essa tela (uma vez cada vez que você liga, com o REau na tela) e você liga o servidor à mão: com o Android Auto 17.4 ou mais novo ele desliga quando a sessão termina, então é preciso ligá-lo de novo a cada vez; com o 17.3 ou mais velho, o REau pede ao Android Auto que se conecte e não há nada para ligar.
- Um painel compatível, com o Wi-Fi dele ao alcance.

## Instalar e atualizar

Instale o APK publicado, `REau-0.3.1.apk` (applicationId `com.reau.app`, nome "REau"). É um app único: o REau reconhece qual tela do Android Auto está aparecendo (mapa, menu, apps) pela própria imagem, com uma rede neural pequena que roda no celular.

Vem da antiga REau Lite (pacote `com.reau.app.lite`)? O REau 0.3.0 é um app novo para você: refaça os ajustes e o pareamento com o painel e depois desinstale a Lite antiga (o app novo mostra uma linha discreta e, se o Android Auto não responder, um aviso, enquanto a Lite antiga ainda estiver instalada). Vem do REau anterior (pacote `com.reau.app`)? É só atualizar por cima: ajustes e pareamento ficam. Duas instalações juntas disputam o único servidor do Android Auto, que aceita uma sessão por vez.

Na primeira vez, "Ligar" pede as permissões que o REau ainda precisa, e a projeção começa seja qual for a resposta; a tela **Permissões** lista todas, com um botão para pedir as que faltam e outro que abre as permissões do REau no Android. Uma vez por dia, com a internet ligada, o REau pergunta ao GitHub qual é o número da última versão e mostra uma notificação discreta por versão nova (Ajustes > Atualizações > "Avisar quando houver atualização", ligada por padrão; nada é baixado nem instalado; ainda não testado na moto). Para conferir o APK que você baixou, compare a impressão digital SHA-256 do certificado de assinatura dele com a do projeto: o comando e a impressão digital estão na página de instalação do site.

Uma build de teste (debug) e a oficial não se substituem: para trocar de uma para a outra, desinstale a primeira (depois de compartilhar os registros dela em Ajustes > Depuração, porque desinstalar apaga os dados do app). O passo a passo, com as permissões a liberar, e o FAQ estão no site: <https://murkiriel.github.io/reau/pt/>.

Dica: o Obtainium (app livre de terceiros) instala e atualiza o REau direto das Releases do repositório: em "Adicionar app", cole `https://github.com/Murkiriel/reau`; só valem as versões publicadas, sem as builds de teste, e o REau não muda nada por isso.

## Compilar

Com o JDK 21 e o Android SDK instalados:

```
./gradlew assembleRelease               # o APK: app/build/outputs/apk/release/
./gradlew assembleDebug                 # o APK de debug: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # testes de unidade
```

Os testes instrumentados (`app/src/androidTest`) rodam num emulador, contra um Android Auto falso e um painel falso.

Há um APK publicado na página de Releases do projeto; para compilar o seu em vez dele, o Gradle precisa achar o Android SDK (`ANDROID_HOME`, ou `sdk.dir` em `local.properties`), e, como o repositório não traz a chave de assinatura do projeto, o `assembleRelease` só assina com a chave de debug do Android se você pedir com `-PreauDebugSigned=true`. As Releases oficiais são assinadas com a chave do projeto, guardada fora do repositório; sem as variáveis de ambiente `REAU_KEYSTORE` (o caminho absoluto do arquivo da chave), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` e `REAU_KEY_PASSWORD`, o empacotamento do release para com um erro.

## Privacidade

O REau não tem conta, anúncio nem coleta de dados. A tela **"O REau e os seus dados"**, no próprio app, diz o que
fica guardado no celular e o que sai dele, com o motivo. O que sai do celular vai para o painel e, só com o servidor de rede
opcional ligado, para outros aparelhos da sua rede local; as únicas idas à internet são o clima (Open-Meteo, para o painel e para o anel de informações, que vêm ligados: nada é pedido até você liberar a localização, e cada um tem uma chave) e a verificação de atualização em Ajustes (o botão “Verificar agora” e, uma vez por dia, sozinha, a chave “Avisar quando houver atualização”, ligada de fábrica), que só pergunta ao GitHub qual é a última versão. A permissão de localização também deixa o Android dar ao REau a lista de redes Wi-Fi, para ver a do painel, com o app atrás de outro (Android Auto, Waze) ou com a tela apagada; o REau não lê a posição para isso, e o Android pode mostrar o ícone de localização durante a projeção.

## Segurança

Não mexa no celular enquanto pilota. Use o joystick do guidão e só com a moto em condição segura.

## Changelog

Todas as mudanças importantes estão em [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md) (em inglês: [CHANGELOG.md](CHANGELOG.md); em espanhol: [CHANGELOG.es.md](CHANGELOG.es.md)).

## Termos de uso

O app pede o aceite dos [termos de uso](TERMS_OF_USE.pt-br.md) na primeira abertura (em inglês: [TERMS_OF_USE.md](TERMS_OF_USE.md); em espanhol: [TERMS_OF_USE.es.md](TERMS_OF_USE.es.md)); eles ficam no fim da tela **"O REau e os seus dados"**, junto dos créditos.

## Contribuir e segurança

O REau é um projeto de horas vagas, com um só mantenedor. Como relatar um erro, rodar os testes e enviar uma mudança está em [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md) (em inglês: [CONTRIBUTING.md](CONTRIBUTING.md); em espanhol: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)). Um problema de segurança não se relata numa issue pública: veja o [SECURITY.pt-br.md](SECURITY.pt-br.md).

## Licença e créditos

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Ele incorpora código de
outros projetos abertos; o [NOTICE](NOTICE) lista cada um, com a licença e o que foi usado, e o app mostra os mesmos
créditos na tela **Créditos**, no fim de **"O REau e os seus dados"**, onde também está o endereço do código-fonte do
próprio REau (a AGPL pede que quem usa o app possa obtê-lo).
