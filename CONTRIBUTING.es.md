# Contribuir a REau

English: [CONTRIBUTING.md](CONTRIBUTING.md) · Português (pt-BR): [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md)

REau tiene un único mantenedor y es un proyecto de tiempo libre: no hay contrato de soporte ni un plazo fijo de respuesta. Un
informe corto y claro, o un cambio pequeño y probado, es lo que más ayuda.

## Licencia

REau está bajo la **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Todo lo que aportes queda bajo la
misma licencia. Si un cambio trae código, datos o hechos de otro proyecto, di de dónde vienen, para que el
[NOTICE](NOTICE) y la pantalla Créditos (`app/src/main/java/com/reau/app/settings/Credits.kt`) puedan nombrarlo con su licencia.

## Qué se puede y qué no se puede traer

Una contribución es una idea, o código bajo la AGPL-3.0 o una licencia compatible con ella (Apache-2.0, MIT, BSD y
similares), con su origen citado: el proyecto, el archivo, el commit y la licencia, en el pull request y en la
cabecera del archivo copiado. Nunca pegues en un issue, un pull request, una prueba, un comentario o un archivo:

- nada de la app oficial de Royal Enfield: código descompilado o desensamblado, sus imágenes, iconos, diseños, fuentes,
  textos y traducciones, sus claves, certificados y tokens, su firmware o archivos de actualización, ni capturas de pantalla suyas;
- código, texto o imágenes de un proyecto sin licencia (sin licencia significa todos los derechos reservados) o cuya
  licencia no es compatible con la AGPL-3.0 (por ejemplo una licencia no comercial, o GPL-2.0-only);
- nada que venga de una filtración, de un repositorio privado o de una fuente que pide que no se copie.

Lo que se acepta de esas fuentes es la idea, dicha con tus palabras y escrita como código tuyo, y hechos que tú mismo
observaste en tu propio tablero (qué byte hace qué, y en qué firmware). Un pull request que traiga material de los dos
primeros tipos se cierra, y el material sale de la historia. Si dudas, pregunta en un issue antes de enviar código. Lo que
hace el mantenedor cuando un titular de derechos pide retirar algo está en
[TAKEDOWN_RESPONSE.es.md](TAKEDOWN_RESPONSE.es.md) (en inglés: [TAKEDOWN_RESPONSE.md](TAKEDOWN_RESPONSE.md); en portugués:
[TAKEDOWN_RESPONSE.pt-br.md](TAKEDOWN_RESPONSE.pt-br.md)).

## Ejecutar las pruebas

Con JDK 21 y el Android SDK instalados, desde esta carpeta:

```
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:detekt :app:lintDebug --console=plain
```

La primera línea ejecuta las pruebas unitarias; la segunda ejecuta el análisis de código (detekt) y el lint. Corrige lo
que informen en el código que escribiste, y no agregues nada al baseline de detekt. Una advertencia del compilador de Kotlin también rompe la compilación (`allWarningsAsErrors`): corrígela o, en una llamada obsoleta que no se puede evitar, pon al lado un `@Suppress` que diga el motivo. Algunas pruebas leen archivos del
repositorio (`docs/`, `NOTICE`, los README, los historiales de cambios, `app/src/main/res/`): Gradle sabe que leen esos archivos, así que
se ejecutan de nuevo solas cuando cambia un texto. Las pruebas instrumentadas (`app/src/androidTest`) necesitan un
emulador, como dice el README; `scripts/emu/` tiene los scripts.
Los scripts en Python tienen sus propias pruebas (`python3 -B -m unittest discover -p "test_*.py"` dentro de `scripts/`, `scripts/registro/`,
`scripts/dev/`, `scripts/emu/`, `scripts/painel_falso/` y `bench/`; el último necesita Pillow, fijado en `scripts/requirements.txt`), y la
integración continua también las ejecuta. También revisa su estilo: `ruff check .` en la raíz (reglas en `ruff.toml`), `mypy --ignore-missing-imports *.py` dentro de cada carpeta de scripts y `shellcheck -S warning` en los `.sh`; las versiones de ruff y mypy están en `scripts/requirements-estilo.txt`.

El APK también se audita: `python3 -B scripts/apk_audit.py <apk>` compara los permisos, las bibliotecas, las bibliotecas nativas y los hosts de URL que trae un APK con `scripts/apk_audit_baseline.json`, y comprueba que un APK de release tenga la forma de release (no depurable, sin clases de debug, solo los componentes de la lista). La integración continua monta el release (`-PreauDebugSigned=true`, ya que el repositorio no tiene clave de firma) y lo ejecuta; cuando algo nuevo deba entrar en el APK, mira qué es y acéptalo con `--gravar`.

Todo cambio de código viene con una prueba nueva o ajustada. El proyecto tiene tres idiomas: los textos que lee la gente van en inglés
(el predeterminado), portugués (pt-BR) y español, en los `strings.xml` de `values`, `values-pt` y `values-es`, y los documentos tienen un archivo
por idioma. El texto detrás de un (i) da la idea general en como máximo 3 frases y 100 palabras, sin abreviaturas que terminen en punto
(`InfoTextsTest` lo comprueba en los tres idiomas).

## Fuzzing y la prueba de mutación

Los lectores de bytes que vienen de fuera (los paquetes del tablero, las tramas WebSocket y la cabecera HTTP de un navegador, y los mensajes, las unidades de video, la cabecera de tamaño del video y los mensajes de navegación de Android Auto) tienen
objetivos de fuzzing en `app/src/test/java/com/reau/app/fuzz/ParserFuzzTargets.kt`, escritos con [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
(Apache-2.0, solo dependencia de prueba: nunca entra en un APK). En la prueba unitaria normal cada objetivo solo repite sus semillas, los
archivos de `app/src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<objetivo>/`, como prueba de regresión. Para hacer fuzzing de
verdad, guiado por el código que alcanza cada entrada, define la variable de entorno (20 segundos por objetivo; `JAZZER_MAX_DURATION` lo cambia):

```
JAZZER_FUZZ=1 ./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTargets*' --console=plain
```

Con `JAZZER_MAX_DURATION` definida (por ejemplo `5m`), solo el primer objetivo de la ejecución hace fuzz durante ese tiempo y los demás solo repiten sus semillas: ejecuta un objetivo por vez (`--tests '*ParserFuzzTargets.dashPacket'`). La ejecución deja su corpus de trabajo en `app/.cifuzz-corpus/` y, si una entrada rompió un lector, un archivo `crash-<hash>` en `app/` (los dos
quedan fuera de git). Tras corregir el error, copia ese archivo a la carpeta de semillas del objetivo, para que la misma entrada se pruebe en
cada ejecución desde entonces. Un objetivo nuevo es un método con `@FuzzTest` que recibe un `ByteArray`, con unas pocas semillas pequeñas
propias (nunca capturas reales: mira "Datos personales"). Para esto las pruebas unitarias corren en la JUnit Platform (el motor vintage corre las de JUnit 4).

`python3 scripts/dev/mutacoes.py` es la prueba de mutación: cambia una decisión del código cada vez y comprueba que una prueba unitaria falla.
`--lista` cuenta las mutaciones y `--fatia I/N` ejecuta una porción (el workflow semanal ejecuta veinticuatro, y `mutacao-mensal.yml` ejecuta una de doce cada mes, la del número del mes); una mutación que sobrevive es una prueba
que no guarda esa decisión.

## Cobertura, benchmark, dispositivos gestionados y la verificación de dependencias

**Cobertura de las pruebas unitarias (Kover).** `./gradlew :app:koverHtmlReportDebug` ejecuta las pruebas de JVM de la variante debug y escribe el informe HTML en
`app/build/reports/kover/htmlDebug/` (`:app:koverXmlReportDebug` escribe el XML). No forma parte de `check`, de `assemble` ni de la integración continua, y el APK
no se toca. Las clases generadas `R` y `BuildConfig`, las `ComposableSingletons` del compilador de Compose y los mensajes de Android Auto que genera protoc (`com.reau.app.aa.proto`) quedan fuera de la cuenta.

**Benchmark de arranque y perfil base (`macrobenchmark/`).** El módulo compila sin dispositivo (`./gradlew :macrobenchmark:assemble`) pero solo SE EJECUTA en un teléfono con depuración USB
o en un emulador, y mide el tipo de build `benchmark` de la app (el código de la release, firmado con la clave de debug, y por eso sin necesitar la clave de release). Con un dispositivo conectado:

```
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.reau.app.macrobenchmark.StartupBenchmark
```

`StartupBenchmark` mide el arranque en frío dos veces, sin perfil base y con el de la app; los números son `timeToInitialDisplayMs` en la salida. Acepta antes los términos de uso en el dispositivo,
o medirá la pantalla de los términos. En un emulador añade `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`
(los números de un emulador no dicen nada sobre un teléfono). `BaselineProfileGenerator` (el mismo comando, con `class=com.reau.app.macrobenchmark.BaselineProfileGenerator`) anota lo que se ejecuta
mientras la app abre e imprime dónde dejó el archivo en el dispositivo. El build no usa ese archivo: el perfil de la app, `app/src/main/baseline-prof.txt`, se escribe a mano, y se comparan los dos y se añade a mano
lo que valga la pena. El plugin `androidx.baselineprofile` de Gradle no se usa: crearía tipos de build y carpetas de perfil generado que se superponen al escrito a mano.

**Dispositivos que Gradle gestiona.** `pixel6Api34` está declarado en `app/build.gradle.kts` (la imagen ligera `aosp-atd`, para las pruebas instrumentadas) y en `macrobenchmark/build.gradle.kts` (la imagen `aosp`, que puede ser root).
Declararlos no descarga nada. La primera ejecución de uno de ellos descarga una imagen de sistema de algunos cientos de MB y crea sola el dispositivo virtual:

```
./gradlew :app:pixel6Api34DebugAndroidTest
./gradlew :macrobenchmark:pixel6Api34BenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Necesitan virtualización por hardware (KVM). No usan, no cambian y no borran los dispositivos virtuales que hayas creado tú.

**Una dependencia nueva y la verificación PGP.** `gradle/verification-metadata.xml` comprueba cada artefacto que el build descarga: por la firma PGP, contra las claves de `gradle/verification-keyring.keys`,
cuando quien publica firma, y por el sha256 en los demás (muchos artefactos del Maven de Google no están firmados). Un build con una dependencia que no está en el archivo falla con
"Dependency verification failed". Los servidores de claves están desactivados (`<key-servers enabled="false"/>`), así que ningún build pide una clave a la red; para añadir una dependencia,
pon `enabled="true"` solo durante este comando y vuelve a `"false"` antes del commit (una prueba lo comprueba). Para añadirla, ejecuta, con red y en la carpeta del repositorio:

```
./gradlew --write-verification-metadata pgp,sha256 --export-keys help testDebugUnitTest detekt lintDebug assembleDebug assembleAndroidTest assembleRelease -PreauDebugSigned=true :macrobenchmark:assemble
```

Gradle mantiene los sha256 que ya están en el archivo, añade los nuevos y pone un `trusted-key` para cada clave que firma los artefactos nuevos (y la exporta al llavero). Después LEE la diferencia: de quién es la clave
y para qué grupo es de confianza; que no se quitó ningún sha256; y que ningún `ignored-key` (una clave que no se pudo descargar) esconde un artefacto que antes tenía sha256, lo que lo dejaría
sin ninguna comprobación. Haz el commit de los metadatos y del llavero juntos (`gradle/verification-keyring.gpg`, la copia binaria que Gradle también escribe, no se guarda). Después ejecuta el build otra vez con `--offline`.

## El tablero falso de banco

`scripts/painel_falso/painel_falso.py` hace el papel del tablero de la moto en un ordenador, para que un teléfono de verdad con REau se conecte y
transmita sin la moto. Solo usa la biblioteca estándar de Python. Escucha en los puertos UDP del tablero (2000 para el control, 5000 para el
vídeo; las respuestas van al 2002 del teléfono), responde al saludo con una clave pública RSA-1024 generada en el momento (la clave privada
se queda en la memoria y nunca se graba), recibe la clave de sesión AES-256, envía el latido del tablero, responde a cada cuadro de vídeo con
el aviso del tablero, vuelve a montar el vídeo RTP en un archivo `.h264` y pulsa el joystick por la entrada estándar (`press left`; `help`
lista los comandos). Lo que llega, lo que se responde y todo error de protocolo van al terminal y a un archivo de registro, los dos en
`painel_falso_saida/` (`--out` cambia la carpeta; `.log` y `.h264` nunca se versionan). El título de la ruta, la canción y el nombre de quien
llama solo aparecen por su tamaño, salvo con `--show-text`: no compartas un registro hecho con él.

El teléfono encuentra el ordenador cuando el ordenador es lo que la app busca: un punto de acceso Wi-Fi con WPA2, un nombre que empiece por
`RE_` (por ejemplo `RE_BENCH_0001`) y la contraseña que la app tiene para el tablero (la de fábrica, `12345678`, si no la cambiaste), con el
ordenador en `192.168.1.1` (máscara `255.255.255.0`) y un servidor DHCP que entregue al teléfono una dirección `192.168.1.x`. La app envía
los paquetes de control a `192.168.1.255` y el vídeo a `192.168.1.1`. En Linux con NetworkManager, `scripts/painel_falso/ponto_de_acesso.py`
crea ese punto de acceso sin sacar el ordenador del Wi-Fi en el que está:

1. `python3 scripts/painel_falso/ponto_de_acesso.py check` solo lee, y dice si el punto de acceso puede levantarse ahí y por qué. La
   tarjeta Wi-Fi tiene que aceptar una estación y un punto de acceso a la vez (`iw list` muestra `managed` y `AP` en una misma
   combinación). La mayoría de las tarjetas que lo aceptan solo permiten un canal para los dos, así que el punto de acceso se levanta en el
   canal en el que está la red del ordenador, también en 5 GHz; ese canal no puede ser uno en el que nada puede empezar a transmitir
   (`no IR`, detección de radar), y un ordenador cuya red ya es `192.168.1.x` se rechaza. Donde no pueda levantarse, pon el ordenador en
   internet por cable o usa un adaptador Wi-Fi USB, y haz a mano el punto de acceso descrito arriba.
2. `sudo python3 scripts/painel_falso/ponto_de_acesso.py up` lo crea (`--ssid` y `--password` cambian el nombre y la contraseña;
   `--dry-run` escribe los comandos y no ejecuta ninguno). Por debajo, añade en la misma tarjeta una interfaz virtual de tipo punto de
   acceso (`reauap0`, con una dirección aleatoria administrada localmente) y, en ella, una conexión de NetworkManager en modo punto de
   acceso con el método IPv4 "shared", que da la dirección y el servidor DHCP. Nunca llama a `sudo` por sí mismo. `down` en lugar de `up`
   lo deshace todo, también después de un `up` que se quedó a medias.
3. Haz lo que `up` dice al final: con una VPN activa, activa su "Local network sharing", o lo que envía el teléfono nunca llega al tablero
   falso; con un cortafuegos activo, permite la entrada de UDP en los puertos 2000 y 5000 (para `ufw` escribe el comando).
4. Arranca el tablero falso: `python3 scripts/painel_falso/painel_falso.py --ssid RE_BENCH_0001`. Con `--ssid`, una clave de sesión enviada
   con otro nombre se rechaza, como el tablero comprueba su propio nombre; sin él, se acepta cualquier nombre. `--ver` también muestra el
   vídeo en directo, en una ventana de `ffplay` para cada sesión; sin `ffplay`, o una vez cerrada la ventana, el vídeo se sigue grabando
   en el archivo.
5. En el teléfono, con la moto apagada o fuera de alcance, olvida el tablero en REau (para que vuelva a buscar uno por el nombre), elige el
   punto de acceso y conecta como lo harías con la moto.

`--selftest` prueba el tablero falso contra un lado del teléfono mínimo en el loopback del propio ordenador, sin red, y las pruebas están en la
misma carpeta. El tablero falso se escribió a partir del código de la propia app y solo responde lo que ese código muestra que el tablero
responde; lo demás que envía el teléfono solo se registra. Prueba el lado del teléfono, no que un tablero lo acepte: para eso, mira la sección
siguiente. `test_sincronia.py` lee los puertos, los códigos de los botones, los paquetes y la secuencia de salida del código Kotlin, así que
un cambio en la app falla ahí hasta que el tablero falso lo siga.

## Qué no cambiar sin una prueba en la moto

El protocolo de Android Auto y el protocolo del tablero (`aa/` y `dash/protocol/`), el handshake con el tablero y la ruta del video (el
codificador, el compositor, el ritmo de envío) solo se comprueban en un tablero real. Las pruebas unitarias no lo reemplazan: no
los cambies a menos que puedas decir qué probaste en el tablero, con su firmware. Se probó en el firmware 13.10;
en otro firmware no hay ninguna garantía.

## Datos personales

Los registros, las grabaciones y las fotos pueden contener lugares a los que fuiste, nombres de Wi-Fi, números de teléfono o nombres de contactos. Léelos
y recorta todo lo privado antes de adjuntarlos en cualquier parte, y nunca pongas datos personales en una prueba, en una muestra de registro ni en una
captura de pantalla que subas al repositorio. Si tienes dudas, descríbelo con palabras.

Un problema de seguridad no se informa en un issue público: sigue [SECURITY.md](SECURITY.md).

## Informar un error

Usa la plantilla de informe de errores: pide la versión de REau, la versión de Android Auto, el firmware del tablero y
el teléfono, e indica dónde la app comparte sus registros.
