# Como responder a um pedido de remoção

English: [TAKEDOWN_RESPONSE.md](TAKEDOWN_RESPONSE.md) · Español: [TAKEDOWN_RESPONSE.es.md](TAKEDOWN_RESPONSE.es.md)

Este é um guia de trabalho para o mantenedor do REau, guardado em aberto para que quem assumir o projeto o encontre. Diz o
que fazer se a Royal Enfield, ou qualquer outro titular de direitos, pedir que algo do REau seja retirado. **Não é
aconselhamento jurídico**: é uma lista de conferência montada sobre fatos públicos. Quando um pedido chegar, fale com um
advogado que conheça software e direito autoral, e deixe que ele decida o que a lista deixa em aberto (veja "A quem pedir ajuda").

## O que aconteceu com dois projetos parecidos

Só o que os próprios projetos publicaram:

- O **NorthStar**, um projeto aberto para o mesmo painel, teve o código inteiro retirado pelo autor em 08/07/2026 (commit
  `f33f183`, "Remove source, project discontinued at Royal Enfield's request"). O repositório ficou com um README curto e
  foi arquivado depois. Nenhum aviso aparece no repositório público de avisos de remoção do GitHub (procurado em
  18/11/2026), então o pedido parece ter chegado direto ao autor. Os forks feitos antes da retirada continuaram no ar.
- O **open-dash**, projeto que descende do NorthStar, diz no README que a Royal Enfield o contatou e que, depois de conversas,
  ele tira dos próximos lançamentos os protocolos de conexão e projeção do painel, o código proprietário e o papel de
  parede do painel. Em 21/09/2026 o ramo principal foi reescrito num único commit (`a55fa90`), sem esse código.
- Nenhum dos dois publicou os fundamentos do pedido. Os dois tinham aviso de "sem vínculo" e um arquivo NOTICE, e isso não
  evitou o pedido: o aviso ajuda, mas não é escudo.
- Quando um projeto sai do ar, a história dele pode ir junto, e a prova da licença também. Por isso o NOTICE do REau agora
  cita commits que ainda abrem.

## As primeiras 24 horas

1. Não responda com raiva, não discuta em público e não apague nada ainda. Apagar parece esconder e destrói o que você
   vai precisar.
2. Guarde o pedido exatamente como veio: o texto inteiro, o remetente, a data e a hora, os cabeçalhos e os anexos, numa
   pasta que só o mantenedor lê. Anote quando você o recebeu.
3. Confira quem mandou. Uma mensagem de empresa ou de escritório de advocacia deve vir de um endereço que você confirme por
   um canal que você mesmo encontre (o site da própria empresa), não pelos links da mensagem. Não abra anexos de remetente
   que você não consegue confirmar.
4. Descubra que tipo de pedido é: uma mensagem amistosa, uma carta de advogado, um aviso que o GitHub repassa (traz prazo e
   instruções: leia no mesmo dia) ou uma ordem judicial. Cada um pede uma resposta diferente.
5. Pare o que pode piorar as coisas: nenhum lançamento, tag, vídeo ou anúncio novo até o pedido ser entendido. Não mexa no
   código que não foi citado.
6. Faça uma cópia privada de tudo como está agora: um espelho completo do repositório (`git clone --mirror`), a lista dos
   lançamentos com o hash de cada APK e as páginas do site. É o seu registro do que foi publicado e quando.
7. Mande uma confirmação curta, sem admitir nem negar nada: que a mensagem chegou, que o projeto é tocado por uma pessoa em
   horas vagas e que a resposta virá numa data que você consiga cumprir (alguns dias).
8. Procure um advogado no mesmo dia (veja "A quem pedir ajuda").

## Como avaliar o pedido

Pergunte o que exatamente está sendo reclamado, por quem e com que direito. Um pedido costuma misturar três coisas diferentes:

- **Marca.** Os nomes Royal Enfield, Tripper, Himalayan e parecidos, logotipos, ícones de produto e o visual do app oficial.
  O REau cita o hardware só para dizer com o que funciona, e o README, o NOTICE e os Créditos dizem que não há vínculo.
  Confira todo lugar que possa sugerir o contrário: o ícone, as capturas de tela, o site, os textos do app, as notas de
  versão. Texto e imagem são fáceis de mudar.
- **Direito autoral sobre material.** Código, imagens, textos, traduções, chaves ou firmware do app oficial. A regra do REau
  é que nada disso está no repositório, na história nem no APK, e o mantenedor confere de novo sempre que uma
  reclamação aponta um arquivo. Se o reclamante apontar algo que de fato está lá, retire na hora, da árvore atual e dos
  lançamentos, e diga ao advogado o que a história guarda.
- **Fatos de protocolo.** Qual byte o painel espera, e em que ordem, observado no painel do próprio mantenedor e escrito
  em código do próprio REau. Se e como fatos assim podem ser usados para interoperar depende do país, então esta é a parte
  do advogado, não só do mantenedor.

Depois olhe o lado da licença. O REau está sob a AGPL-3.0 e boa parte dele vem de outros projetos (veja o NOTICE), então:

- Uma reclamação sobre uma parte que veio de outro projeto se responde com o NOTICE e os cabeçalhos dos arquivos, que dizem
  de onde cada parte veio, sob qual licença e em qual commit. Se a parte é do reclamante, o NOTICE mostra como chegar
  aos autores.
- A AGPL-3.0 dá a todo mundo que tem uma cópia o direito de guardá-la e repassá-la nos mesmos termos. Tirar o repositório do
  REau do ar não recolhe as cópias que já existem, e o REau não pode dar licença sobre material que não é dele. Não
  prometa um recolhimento que não dá para fazer.
- Se o APK continuar disponível, o código-fonte correspondente também tem de continuar (a AGPL exige). Tirar só o repositório
  e seguir oferecendo o APK quebraria a licença. Tire os dois, ou nenhum.
- Se a reclamação é que uma licença não foi cumprida (um aviso que falta, uma licença errada), corrija primeiro e anote
  quando. A AGPL-3.0 deixa quem cessa a violação recuperar a licença, nos casos que a seção 8 dela descreve.
- Contribuições só entram com origem conhecida (veja o CONTRIBUTING), justamente para que essa pergunta possa ser respondida.

Um pedido não é uma ordem. Mesmo assim, um aviso formal ignorado pode acabar com a plataforma desativando o repositório, e
uma resposta errada pode ser usada contra o projeto. Tome a decisão com o advogado.

## O que tirar do ar, e nesta ordem

1. O material que o reclamante identificou como seu, se de fato estiver lá: os arquivos, os textos, as imagens, os quadros
   de vídeo. Retire da árvore atual, dos lançamentos e do site.
2. O que está em disputa e não se resolve rápido: retire só essa parte (um arquivo, um lançamento, uma página do site, um
   vídeo) e deixe o resto. Uma retirada estreita é fácil de desfazer e mostra boa-fé.
3. Tudo o que possa ser tomado pelo app oficial: o nome, os logotipos e as capturas de tela que sugerem um vínculo.
4. O projeto inteiro, só como último recurso, e arquivando o repositório ou tornando-o privado, não apagando. Lembre dos APKs
   já instalados e das cópias do código que já existem: diga isso na resposta em vez de prometer o que não dá para cumprir.

Os lugares a olhar, para que nada seja esquecido: o repositório (árvore, história, tags e ramos), os lançamentos e seus APKs,
o site (`docs/`, suas imagens e vídeos), o README, o NOTICE e o CONTRIBUTING, a tela de Créditos e os textos dentro do app,
os modelos de issue e as issues e pull requests abertos.

Se algo precisar sair da história, reescreva-a só com a concordância do advogado e diga na resposta que forks e clones antigos
guardam o que já tinham. Registre a mudança no CHANGELOG nas três línguas se ela afetar quem usa o app.

## Como responder

- Curta, educada, objetiva e por escrito. Use a língua do pedido, e também o inglês se não era inglês.
- Diga quem é o projeto: software livre, um mantenedor, projeto de horas vagas, sem vínculo com o titular dos direitos e sem
  nenhum material do app oficial.
- Peça o que é preciso para agir: qual material, quais arquivos ou endereços, qual direito e quem fala em nome do titular.
- Diga o que já foi feito (por exemplo, que os lançamentos estão parados) e quando virá a próxima resposta.
- Não ameace, não acuse, não adivinhe os motivos do reclamante e não publique a carta privada nem o nome de quem a
  escreveu. Uma nota pública curta dizendo o que foi retirado e por quê basta.
- Não envie contranotificação, não afirme que o material é seu e não assine nada sob pena de perjúrio sem um advogado: são
  atos jurídicos com consequências para quem assina.
- Guarde todas as mensagens da troca.

## O que guardar

- O pedido, as respostas e as datas, como foram enviados e recebidos.
- O espelho do repositório, a lista dos lançamentos com o hash dos APKs e as páginas do site como eram antes de qualquer
  mudança, e uma lista do que foi mudado depois, com o commit de cada mudança.
- O registro de como o REau foi conferido quanto a material oficial (o que foi procurado, quando, com que resultado).
- A prova de licença de cada projeto do NOTICE: copie o texto da licença e o NOTICE de um projeto de origem para a pasta
  `licenses/` do repositório quando isso for permitido, e confira de tempos em tempos se os endereços e commits citados no
  NOTICE ainda abrem (`gh api repos/DONO/NOME/commits/HASH`). O texto da licença do NorthStar foi embora com o código dele.
- Nada do material reclamado, além da cópia privada do espelho: não o redistribua e guarde-o à parte.

## Depois da resposta

- Se o pedido foi resolvido, anote o que foi combinado e guarde a resposta do outro lado.
- Reveja o que levou a ele e feche a brecha: uma frase do README, uma imagem, uma contribuição que entrou sem fonte.
- Não procure os donos de forks em nome do reclamante e não os ataque. Eles não estão sob o controle do projeto.
- Avise os usuários com clareza, nas três línguas, se algo que eles usam mudou.

## A quem pedir ajuda

A um advogado que conheça software e direito autoral, no país onde o mantenedor mora. Organizações que ajudam projetos de
software livre, como a Software Freedom Conservancy ou a Electronic Frontier Foundation, também podem orientar a primeira
conversa. Nada do que está escrito aqui as substitui, e nada disto é promessa de que um passo seja seguro.
