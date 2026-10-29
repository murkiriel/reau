# REau

English (padrão): [README.md](README.md) · Español: [README.es.md](README.es.md)

Aplicativo Android que leva o **Android Auto do próprio celular** para o painel TFT da moto, pela rede Wi-Fi do
painel, com a tela do celular apagada. O REau não desenha mapa nenhum: repete no painel o que o app de navegação
(Waze, Google Maps) desenha no Android Auto.

> **Compatibilidade:** funciona com o painel **Tripper Dash**. O REau é um projeto independente, sem ligação com a
> Royal Enfield, e não é endossado nem mantido por ela.

## Como funciona

```
App de navegação ──▶ Android Auto do celular ──(vídeo H.264 por 127.0.0.1)──▶ REau
                                                                              │ decodifica, recorta, reduz
                                                                              ▼
                                                       codificador 526×300 ──RTP──▶ painel da moto
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
  direita ou o centro recusam; em ligação, o centro desliga e cima e baixo mudam o volume.
- Um anel de informações sobre a imagem, à direita do painel (vem ligado, com uma chave): chuva, vento, tempestade, UV, qualidade do ar, o sol, as temperaturas do dia e a bateria, o sinal e o GPS do celular, uma coisa de cada vez. Precisa da localização e da internet para o clima.
- Fala português, inglês e espanhol: segue o idioma do celular, e há um seletor nos Ajustes.
- Ajusta a qualidade do vídeo pela distância da próxima manobra e pela velocidade.
- Liga com um toque longo no ícone do REau ("Ligar") ou por um bloco das Configurações rápidas, para ligar em casa, antes do capacete.
- Opcional: mostra o Android Auto num navegador da rede local, protegido por PIN.
- Guarda um registro de diagnóstico no armazenamento interno do app (ligado por padrão, com uma chave nos Ajustes), para
  analisar no computador (`scripts/registro/`); nada sai do celular a não ser que você o compartilhe. Gravar o vídeo e as
  sessões é opcional (Ajustes > Depuração): vem desligado no app que você instala para andar e ligado na build de debug; as gravações ficam
  no celular, saem pelos mesmos dias e pelo mesmo limite de tamanho do registro e guardam o mapa, a rota e as mensagens do Android Auto.

## Requisitos

- Celular com Android 10 ou mais novo e o **Android Auto** instalado.
- O modo de desenvolvedor do Android Auto ligado, com o "servidor da unidade principal". Na edição completa, o REau abre essa tela e
  liga o servidor sozinho quando a acessibilidade do app está ativa; na Lite, o REau também abre essa tela (uma vez cada vez que você liga, com o REau na tela) e você liga o servidor à mão.
- No Android 13 ou mais novo, para ligar o serviço de acessibilidade (edição completa) é preciso antes liberá-lo em
  Configurações › Apps › REau › ⋮ › "Permitir configurações restritas": o Android pede isso de qualquer app instalado
  de fora da Play Store, inclusive o APK publicado.
- Um painel compatível, com o Wi-Fi dele ao alcance.

## Duas edições

- **Completa** (`com.reau.app`): a de sempre. Usa um serviço de acessibilidade do Android para ligar o servidor do Android Auto
  sozinha e para saber pelo Android Auto qual tela está aparecendo (para trocar entre o encaixe da navegação e o dos outros).
- **Lite** (`com.reau.app.lite`, "REau Lite", **a recomendada**): **sem** serviço de acessibilidade. Com o Android Auto 17.4 ou mais novo, você liga o servidor da unidade principal à mão (ele desliga quando a sessão termina, então é preciso ligá-lo de novo a cada vez); com o 17.3 ou mais velho, o REau pede ao Android Auto que se conecte e não há nada para ligar.
  Ela reconhece qual tela está aparecendo pela própria imagem, para trocar o encaixe.

**Qual usar: a recomendada é a Lite.** Ela não pede o serviço de Acessibilidade: não ganha controle amplo do celular e não briga
com apps que se recusam a rodar com a Acessibilidade ligada (os de banco, por exemplo). O preço é um passo manual a cada uso:
ligar você mesmo o servidor da unidade principal do Android Auto. Escolha a edição completa só se quiser que o REau faça esse
passo sozinho e saiba a tela pelo Android Auto em vez de pela imagem.

As duas instalam juntas no mesmo celular, mas não devem rodar ao mesmo tempo (disputam o painel).

## Compilar

Com o JDK 21 e o Android SDK instalados:

```
./gradlew assembleRelease               # os dois APKs: app/build/outputs/apk/completa/release/ e .../lite/release/
./gradlew assembleCompletaRelease       # só a edição completa (com.reau.app); assembleLiteRelease: só a Lite
./gradlew :app:testDebugUnitTest        # testes de unidade
```

Os testes instrumentados (`app/src/androidTest`) rodam num emulador, contra um Android Auto falso e um painel falso.

Há um APK publicado na página de Releases do projeto; para compilar o seu em vez dele, o Gradle precisa achar o Android SDK (`ANDROID_HOME`, ou `sdk.dir` em `local.properties`), e, como o repositório não traz a chave de assinatura do projeto, o `assembleRelease` só assina com a chave de debug do Android se você pedir com `-PreauDebugSigned=true`. As Releases oficiais são assinadas com a chave do projeto, guardada fora do repositório; sem as variáveis de ambiente `REAU_KEYSTORE` (o caminho absoluto do arquivo da chave), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` e `REAU_KEY_PASSWORD`, o empacotamento do release para com um erro.

## Privacidade

O REau não tem conta, anúncio nem coleta de dados. A tela **"O REau e os seus dados"**, no próprio app, diz o que
fica guardado no celular e o que sai dele, com o motivo. O que sai do celular vai para o painel e, só com o servidor de rede
opcional ligado, para outros aparelhos da sua rede local; as únicas idas à internet são o clima (Open-Meteo, para o painel ou o anel de informações: vem ligado, mas não pede nada até você liberar a localização, e tem uma chave) e o botão “Verificar atualização” em Ajustes, que só pergunta ao GitHub quando você toca.

## Segurança

Não mexa no celular enquanto pilota. Use o joystick do guidão e só com a moto em condição segura.

## Changelog

Todas as mudanças importantes estão em [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md) (em inglês: [CHANGELOG.md](CHANGELOG.md); em espanhol: [CHANGELOG.es.md](CHANGELOG.es.md)).

## Termos de uso

O app pede o aceite dos [termos de uso](TERMS_OF_USE.pt-br.md) na primeira abertura (em inglês: [TERMS_OF_USE.md](TERMS_OF_USE.md); em espanhol: [TERMS_OF_USE.es.md](TERMS_OF_USE.es.md)); eles ficam no fim da tela **"O REau e os seus dados"**, junto dos créditos.

## Contribuir e segurança

O REau é um projeto de horas vagas, com um só mantenedor. Como relatar um erro, rodar os testes e enviar uma mudança está em [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md) (em inglês: [CONTRIBUTING.md](CONTRIBUTING.md); em espanhol: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)). Um problema de segurança não se relata numa issue pública: veja o [SECURITY.md](SECURITY.md) (só em inglês).

## Licença e créditos

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Ele incorpora código de
outros projetos abertos; o [NOTICE](NOTICE) lista cada um, com a licença e o que foi usado, e o app mostra os mesmos
créditos na tela **Créditos**, no fim de **"O REau e os seus dados"**, onde também está o endereço do código-fonte do
próprio REau (a AGPL pede que quem usa o app possa obtê-lo).
