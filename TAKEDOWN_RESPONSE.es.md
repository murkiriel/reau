# Cómo responder a una solicitud de retirada

English: [TAKEDOWN_RESPONSE.md](TAKEDOWN_RESPONSE.md) · Português (pt-BR): [TAKEDOWN_RESPONSE.pt-br.md](TAKEDOWN_RESPONSE.pt-br.md)

Esta es una guía de trabajo para el mantenedor de REau, guardada en abierto para que quien asuma el proyecto la encuentre. Dice
qué hacer si Royal Enfield, o cualquier otro titular de derechos, pide que se retire algo de REau. **No es asesoramiento
jurídico**: es una lista de comprobación construida sobre hechos públicos. Cuando llegue una solicitud, habla con un abogado
que conozca el software y el derecho de autor, y deja que él decida lo que la lista deja abierto (ver "A quién pedir ayuda").

## Lo que pasó con dos proyectos parecidos

Solo lo que los propios proyectos publicaron:

- **NorthStar**, un proyecto abierto para el mismo panel, tuvo todo su código retirado por su autor el 08/07/2026 (commit
  `f33f183`, "Remove source, project discontinued at Royal Enfield's request"). El repositorio quedó con un README corto y
  se archivó después. Ningún aviso aparece en el repositorio público de avisos de retirada de GitHub (buscado el
  18/11/2026), así que la solicitud parece haber llegado directamente al autor. Los forks hechos antes de la retirada
  siguieron en línea.
- **open-dash**, un proyecto que desciende de NorthStar, dice en su README que Royal Enfield lo contactó y que, tras
  conversaciones, quita de las próximas versiones los protocolos de conexión y proyección del panel, el código propietario y
  el fondo de pantalla del panel. El 21/09/2026 su rama principal se reescribió en un único commit (`a55fa90`), sin ese código.
- Ninguno de los dos publicó los fundamentos de la solicitud. Los dos tenían un aviso de "sin vínculo" y un archivo NOTICE, y
  eso no evitó la solicitud: el aviso ayuda, pero no es un escudo.
- Cuando un proyecto desaparece, su historia puede irse con él, y también la prueba de su licencia. Por eso el NOTICE de REau
  cita ahora commits que todavía se abren.

## Las primeras 24 horas

1. No respondas con enojo, no discutas en público y no borres nada todavía. Borrar parece esconder y destruye lo que vas a
   necesitar.
2. Guarda la solicitud exactamente como llegó: el texto completo, el remitente, la fecha y la hora, las cabeceras y los
   adjuntos, en una carpeta que solo lee el mantenedor. Anota cuándo la recibiste.
3. Comprueba quién la envió. Un mensaje de una empresa o de un despacho de abogados debe venir de una dirección que confirmes
   por un canal que encuentres tú mismo (el sitio de la propia empresa), no por los enlaces del mensaje. No abras adjuntos de
   un remitente que no puedes confirmar.
4. Averigua qué tipo de solicitud es: un mensaje amistoso, una carta de abogado, un aviso que GitHub reenvía (trae plazo e
   instrucciones: léelos el mismo día) o una orden judicial. Cada una pide una respuesta distinta.
5. Detén lo que pueda empeorar las cosas: ninguna versión, etiqueta, vídeo o anuncio nuevo hasta entender la solicitud. No
   toques el código que no se mencionó.
6. Haz una copia privada de todo como está ahora: un espejo completo del repositorio (`git clone --mirror`), la lista de las
   versiones con el hash de cada APK y las páginas del sitio. Es tu registro de lo que se publicó y cuándo.
7. Envía un acuse corto, sin admitir ni negar nada: que el mensaje llegó, que el proyecto lo lleva una persona en su tiempo
   libre y que la respuesta llegará en una fecha que puedas cumplir (unos días).
8. Busca un abogado el mismo día (ver "A quién pedir ayuda").

## Cómo evaluar la solicitud

Pregunta qué se reclama exactamente, quién lo reclama y con qué derecho. Una solicitud suele mezclar tres cosas distintas:

- **Marca.** Los nombres Royal Enfield, Tripper, Himalayan y similares, logotipos, iconos de producto y el aspecto de la
  app oficial. REau nombra el hardware solo para decir con qué funciona, y el README, el NOTICE y los Créditos dicen que no
  hay vínculo. Revisa todo lugar que pueda sugerir lo contrario: el icono, las capturas de pantalla, el sitio, los textos
  de la app, las notas de versión. Textos e imágenes son fáciles de cambiar.
- **Derecho de autor sobre material.** Código, imágenes, textos, traducciones, claves o firmware de la app oficial. La regla
  de REau es que nada de eso está en el repositorio, en la historia ni en el APK, y el mantenedor lo vuelve a comprobar
  siempre que una reclamación señala un archivo. Si el reclamante señala algo que de verdad está ahí, retíralo de inmediato,
  del árbol actual y de las versiones, y dile al abogado lo que guarda la historia.
- **Hechos de protocolo.** Qué byte espera el panel, y en qué orden, observado en el panel del propio mantenedor y escrito
  en código propio de REau. Si y cómo se pueden usar hechos así para la interoperabilidad depende del país, así que esta
  es la parte del abogado, no solo del mantenedor.

Después mira el lado de la licencia. REau está bajo la AGPL-3.0 y buena parte viene de otros proyectos (ver el NOTICE), así que:

- Una reclamación sobre una parte que vino de otro proyecto se responde con el NOTICE y las cabeceras de los archivos, que
  dicen de dónde vino cada parte, bajo qué licencia y en qué commit. Si la parte es del reclamante, el NOTICE muestra cómo
  llegar a sus autores.
- La AGPL-3.0 da a todo el que tiene una copia el derecho de conservarla y pasarla con los mismos términos. Retirar el
  repositorio de REau no recoge las copias que ya existen, y REau no puede dar licencia sobre material que no es suyo. No
  prometas una recogida que no se puede hacer.
- Si el APK sigue disponible, el código fuente correspondiente también debe seguir (la AGPL lo exige). Retirar solo el
  repositorio y seguir ofreciendo el APK rompería la licencia. Retira los dos, o ninguno.
- Si la queja es que no se cumplió una licencia (un aviso que falta, una licencia equivocada), corrígelo primero y anota
  cuándo. La AGPL-3.0 deja que quien cesa la infracción recupere la licencia, en los casos que describe su sección 8.
- Las contribuciones solo entran con origen conocido (ver CONTRIBUTING), justamente para que esta pregunta pueda responderse.

Una solicitud no es una orden. Aun así, un aviso formal ignorado puede acabar con la plataforma desactivando el repositorio, y
una respuesta equivocada puede usarse contra el proyecto. Toma la decisión con el abogado.

## Qué retirar, y en este orden

1. El material que el reclamante identificó como suyo, si de verdad está ahí: los archivos, los textos, las imágenes, los
   fotogramas de vídeo. Retíralo del árbol actual, de las versiones y del sitio.
2. Lo que está en disputa y no se resuelve rápido: retira solo esa parte (un archivo, una versión, una página del sitio, un
   vídeo) y deja el resto. Una retirada estrecha es fácil de deshacer y muestra buena fe.
3. Todo lo que pueda confundirse con la app oficial: el nombre, los logotipos y las capturas de pantalla que sugieren un vínculo.
4. El proyecto entero, solo como último recurso, y archivando el repositorio o haciéndolo privado, no borrándolo. Recuerda los
   APK ya instalados y las copias del código que ya existen: dilo en la respuesta en lugar de prometer lo que no se puede cumplir.

Los lugares que mirar, para que no se olvide nada: el repositorio (árbol, historia, etiquetas y ramas), las versiones y sus
APK, el sitio (`docs/`, sus imágenes y vídeos), el README, el NOTICE y el CONTRIBUTING, la pantalla de Créditos y los textos
dentro de la app, las plantillas de issue y los issues y pull requests abiertos.

Si algo tiene que salir de la historia, reescríbela solo con el acuerdo del abogado y di en la respuesta que los forks y los
clones antiguos conservan lo que ya tenían. Anota el cambio en el CHANGELOG en los tres idiomas si afecta a quien usa la app.

## Cómo responder

- Corta, cortés, objetiva y por escrito. Usa el idioma de la solicitud, y también el inglés si no era inglés.
- Di quién es el proyecto: software libre, un mantenedor, proyecto de tiempo libre, sin vínculo con el titular de los derechos
  y sin ningún material de la app oficial.
- Pide lo necesario para actuar: qué material, qué archivos o direcciones, qué derecho y quién habla en nombre del titular.
- Di lo que ya se hizo (por ejemplo, que las versiones están detenidas) y cuándo llegará la próxima respuesta.
- No amenaces, no acuses, no adivines los motivos del reclamante y no publiques la carta privada ni el nombre de quien la
  escribió. Una nota pública corta que diga qué se retiró y por qué basta.
- No envíes una contranotificación, no afirmes que el material es tuyo y no firmes nada bajo pena de perjurio sin un abogado:
  son actos jurídicos con consecuencias para quien firma.
- Guarda todos los mensajes del intercambio.

## Qué guardar

- La solicitud, las respuestas y las fechas, tal como se enviaron y recibieron.
- El espejo del repositorio, la lista de las versiones con el hash de los APK y las páginas del sitio como eran antes de
  cualquier cambio, y una lista de lo que se cambió después, con el commit de cada cambio.
- El registro de cómo se comprobó REau respecto a material oficial (qué se buscó, cuándo, con qué resultado).
- La prueba de licencia de cada proyecto del NOTICE: copia el texto de la licencia y el NOTICE de un proyecto de origen en la
  carpeta `licenses/` del repositorio cuando esté permitido, y comprueba de vez en cuando que las direcciones y los commits
  citados en el NOTICE todavía se abren (`gh api repos/DUEÑO/NOMBRE/commits/HASH`). El texto de la licencia de NorthStar se
  fue con su código.
- Nada del material reclamado, aparte de la copia privada del espejo: no lo redistribuyas y guárdalo aparte.

## Después de la respuesta

- Si la solicitud se resolvió, anota lo acordado y guarda la respuesta de la otra parte.
- Revisa lo que llevó a ella y cierra la brecha: una frase del README, una imagen, una contribución que entró sin fuente.
- No contactes a los dueños de forks en nombre del reclamante y no los ataques. No están bajo el control del proyecto.
- Avisa a los usuarios con claridad, en los tres idiomas, si algo que usan cambió.

## A quién pedir ayuda

A un abogado que conozca el software y el derecho de autor, en el país donde vive el mantenedor. Organizaciones que ayudan a
proyectos de software libre, como la Software Freedom Conservancy o la Electronic Frontier Foundation, también pueden orientar
la primera conversación. Nada de lo escrito aquí las sustituye, y nada de esto es una promesa de que un paso sea seguro.
