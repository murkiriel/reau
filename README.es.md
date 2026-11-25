# REau

English: [README.md](README.md) · Português (pt-BR): [README.pt-br.md](README.pt-br.md)

Aplicación Android que lleva el **Android Auto del propio teléfono** al tablero TFT de la moto, por el Wi-Fi del tablero, con la
pantalla del teléfono apagada. REau no dibuja ningún mapa: repite en el tablero lo que la app de navegación (Waze, Google Maps)
dibuja en Android Auto.

> **Compatibilidad:** funciona con el **Tripper Dash**; se verificó solo con el firmware 13.10 del tablero, y con
> otro firmware no se garantiza nada. REau es un proyecto independiente, sin afiliación ni respaldo de Royal Enfield,
> Google, Waze o Meta. Royal Enfield, Tripper, Android Auto y Waze pertenecen a sus dueños.

## Cómo funciona

```
App de navegación ──▶ el Android Auto del teléfono ──(video H.264 por 127.0.0.1)──▶ REau
                                                                                    │ decodifica, recorta, reduce
                                                                                    ▼
                                                                 codificador 528×304 ──RTP──▶ tablero de la moto
```

REau se presenta a Android Auto como la unidad principal de un coche, usando el servidor de unidad principal que el propio
Android Auto ofrece en el modo de desarrollador. Recibe el video, ajusta la imagen a la pantalla del tablero y la envía por el
Wi-Fi del tablero.

## Qué hace

- Muestra la pantalla de Android Auto en el tablero, con un ajuste regulable (uno para las apps de navegación y otro para las demás pantallas,
  el menú de apps incluido).
- Envía al tablero la maniobra, la distancia, la hora de llegada y el tiempo que falta de la ruta.
- Envía también la música que suena (cuando la página del joystick tiene un control de música), las llamadas y el clima, para las tarjetas del tablero.
- Usa el joystick del manillar para controlar Android Auto (mapa, menú, música, llamadas y Gemini, que viene activado y funciona cuando concedes el micrófono): mientras suena una llamada, la izquierda contesta y la derecha o el
  centro rechazan; durante una llamada, el centro cuelga, la derecha sube el volumen y la izquierda lo baja, y arriba y abajo no hacen nada (con el aviso de llamada activado, el predeterminado). Los toques también pueden ser "Subir volumen" y "Bajar volumen", "Alternar mapa y música", "Abrir mapa" y "Abrir música" (ninguno viene configurado), y el título del tablero muestra el nombre de cada acción durante 1,5 s ("Nombre de la acción en el tablero", activado por defecto); aún no probado en la moto.
- Un anillo de información (viene encendido, con un interruptor): lluvia, viento, tormentas, UV, calidad del aire, el sol, las temperaturas del día y la batería, la señal y el GPS del teléfono, una cosa a la vez (de fábrica con todas las tarjetas; apagas las que no quieras). De fábrica se escribe en el título de la franja dorada del tablero ("Información en el título (en lugar del anillo)"); con eso apagado, se dibuja sobre la imagen, a la derecha del tablero. Necesita la ubicación e internet para el clima; la pantalla del clima también muestra el pronóstico, con la lluvia en milímetros (aún no probado en la moto).
- Música con Spotify: con Spotify al frente y sonando, izquierda, derecha y centro del joystick son canción anterior, siguiente y reproducir/pausa, y la franja dorada del tablero muestra la canción, el artista y los tres botones (aún no probado en la moto).
- Un asistente de ajuste (pantalla de Android Auto > "Asistente de ajuste"; se abre solo la primera vez): ayuda a un teléfono nuevo a comprobar que la flecha de la maniobra y la velocidad se vean enteras en el tablero. Aún no se probó en otros teléfonos.
- Avisos para el piloto en el teléfono y en el tablero (Wi-Fi apagado, teléfono caliente, Wi-Fi del tablero colgado, tablero sin respuesta, otra app usando la conexión del tablero, esperando a Android Auto) y una página de Consejos; los avisos de tablero que deja de responder y de otra app en la conexión del tablero aún no se probaron en la moto. Los avisos hablados en el auricular son opcionales.
- Un aviso de volumen arriba del video del tablero (viene activado, con un interruptor): una píldora pequeña con el volumen de la música o de la llamada cada vez que cambia, desde el teléfono, un intercomunicador Bluetooth o el joystick, en lugar del volumen en el título; después de un toque de "Subir volumen" o "Bajar volumen" en el joystick, durante 5 s solo acepta esas dos acciones.
- Un aviso de llamada arriba del video del tablero (viene activado, con un interruptor): una píldora con quién llama, las pistas del joystick y el tiempo de la llamada, en lugar de la pantalla de llamada del tablero, que tapa casi todo el video; con él activado, durante una llamada la derecha y la izquierda son el volumen de la llamada y arriba y abajo no hacen nada.
- Habla portugués, inglés y español: sigue el idioma del teléfono, y los ajustes tienen un selector.
- Para el tráfico por la izquierda, "Lado de circulación" (Automático, Derecha o Izquierda) le dice al tablero hacia dónde girar donde Android Auto no lo dice (aún no probado en la moto); y "Anunciar como moto" (viene activado) le dice a Android Auto que REau es la unidad principal de una moto, así que la primera vez puede pedir de nuevo los permisos en el teléfono.
- Ajusta la calidad del video según la distancia a la próxima maniobra y la velocidad, al ritmo que elijas: Original (el de fábrica, 4 y 2 fps, el predeterminado) o REau (6 y 3 fps, más fluido). El ritmo se elige en Ajustes > Perfiles de video > "Ritmo del video". La imagen es fija, de 528×304, con 400 kbps como máximo y un fotograma clave por segundo, y REau nunca pasa de 6 fps: en pruebas en la moto, por encima de eso el tablero empezó a fallar.
- Se enciende con una pulsación larga en el ícono de REau ("Encender") o desde un mosaico de los Ajustes rápidos, para encenderlo en casa, antes del casco (solo con el teléfono desbloqueado).
- Opcional: muestra Android Auto en un navegador de la red local, protegido por un PIN.
- Guarda un registro de diagnóstico en el almacenamiento interno de la app (activado por defecto, con un interruptor en los
  ajustes), para analizarlo en el computador (`scripts/registro/`); nada sale del teléfono si no lo compartes. Grabar el video
  y las sesiones es opcional (Ajustes > Depuración): viene desactivado en la app que instalas para rodar y activado en la compilación de
  depuración; las grabaciones se quedan en el teléfono, salen por los mismos días y el mismo tope de tamaño del registro, y guardan el mapa,
  la ruta y los mensajes de Android Auto.

## Requisitos

- Un teléfono con Android 10 o más nuevo y **Android Auto** instalado.
- El modo de desarrollador de Android Auto activado, con el "servidor de unidad principal". REau abre esa pantalla (una vez cada vez que enciendes, con REau en pantalla) y tú enciendes el servidor a mano: con Android Auto 17.4 o más nuevo se apaga al terminar la sesión, así que lo enciendes de nuevo cada vez; con 17.3 o anterior, REau le pide a Android Auto que se conecte y no hay nada que encender.
- Un tablero compatible, con su Wi-Fi al alcance.

## Instalar y actualizar

Instala el APK publicado, `REau-0.3.0.apk` (applicationId `com.reau.app`, nombre "REau"). Es una sola app: REau sabe qué pantalla de Android Auto se muestra (mapa, menú, apps) por la propia imagen, con una red neuronal pequeña que corre en el teléfono.

¿Vienes de la antigua REau Lite (paquete `com.reau.app.lite`)? REau 0.3.0 es una app nueva para ti: rehaz los ajustes y el emparejamiento con el tablero y luego desinstala la Lite antigua (la app nueva muestra una línea discreta y, si Android Auto no responde, un aviso, mientras la Lite antigua siga instalada). ¿Vienes del REau anterior (paquete `com.reau.app`)? Solo actualiza encima: los ajustes y el emparejamiento se quedan. Dos instalaciones juntas compiten por el único servidor de Android Auto, que acepta una sesión a la vez.

La primera vez, "Encender" pide los permisos que REau aún necesita, y la proyección empieza sea cual sea la respuesta; la pantalla **Permisos** los lista todos, con un botón para pedir los que faltan y otro que abre los permisos de REau en Android. Una vez al día, con internet, REau le pregunta a GitHub cuál es el número de la última versión y muestra una notificación discreta por cada versión nueva (Ajustes > Actualizaciones > "Avisar cuando haya una actualización", activado por defecto; no se descarga ni instala nada; aún no probado en la moto). Para comprobar el APK que descargaste, compara la huella SHA-256 del certificado con que está firmado con la del proyecto: el comando y la huella están en la página de instalación del sitio.

Una compilación de prueba (debug) y la oficial no se reemplazan entre sí: para pasar de una a la otra, desinstala la primera (después de compartir sus registros en Ajustes > Depuración, porque desinstalar borra los datos de la app). El paso a paso, con los permisos que hay que conceder, y las preguntas frecuentes están en el sitio: <https://murkiriel.github.io/reau/es/>.

Consejo: Obtainium (una app libre de terceros) instala y actualiza REau directamente desde las Releases del repositorio: en "Añadir app", pega `https://github.com/Murkiriel/reau`; solo valen las versiones publicadas, sin las builds de prueba, y REau no cambia nada por ello.

## Compilación

Con JDK 21 y el Android SDK instalados:

```
./gradlew assembleRelease               # el APK: app/build/outputs/apk/release/
./gradlew assembleDebug                 # el APK de debug: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # pruebas unitarias
```

Las pruebas instrumentadas (`app/src/androidTest`) corren en un emulador, contra un Android Auto falso y un tablero falso.

Hay un APK publicado en la página de Releases del proyecto; para compilar el tuyo, Gradle tiene que encontrar el Android SDK (`ANDROID_HOME`, o `sdk.dir` en `local.properties`), y, como el repositorio no trae la clave de firma del proyecto, `assembleRelease` solo firma con la clave de debug de Android si lo pides con `-PreauDebugSigned=true`. Las Releases oficiales se firman con la clave del proyecto, guardada fuera del repositorio; sin las variables de entorno `REAU_KEYSTORE` (la ruta absoluta del archivo de la clave), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` y `REAU_KEY_PASSWORD`, el empaquetado del release se detiene con un error.

## Privacidad

REau no tiene cuenta, ni anuncios, ni recoge datos. La pantalla **"REau y tus datos"**, dentro de la app, dice qué se queda en el
teléfono, qué sale y por qué. Lo que sale del teléfono va al tablero y, solo con el servidor de red opcional encendido, a otros
dispositivos de tu red local; los únicos viajes a internet son el del clima (Open-Meteo, para el tablero y para el anillo de información, que vienen activados: no se pide nada hasta que concedas la ubicación, y cada uno tiene un interruptor) y la verificación de actualizaciones de los Ajustes (el botón “Buscar ahora” y, una vez al día, por sí sola, el interruptor “Avisar cuando haya una actualización”, activado por defecto), que solo consulta a GitHub cuál es la última versión. El permiso de ubicación también deja que Android entregue a REau la lista de redes Wi-Fi, para ver la del tablero, con la app detrás de otra (Android Auto, Waze) o con la pantalla apagada; REau no lee la posición para eso, y Android puede mostrar el ícono de ubicación durante la proyección.

## Seguridad

No manipules el teléfono mientras conduces. Usa el joystick del manillar, y solo cuando la moto esté en condiciones seguras.

## Historial de cambios

Todos los cambios importantes están en [CHANGELOG.es.md](CHANGELOG.es.md) (en inglés: [CHANGELOG.md](CHANGELOG.md); en portugués: [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md)).

## Términos de uso

La app te pide aceptar los [términos de uso](TERMS_OF_USE.es.md) la primera vez que se abre (en inglés: [TERMS_OF_USE.md](TERMS_OF_USE.md); en portugués: [TERMS_OF_USE.pt-br.md](TERMS_OF_USE.pt-br.md)); están al final de la pantalla **"REau y tus datos"**, junto a los créditos.

## Contribuir y seguridad

REau es un proyecto de tiempo libre, con un único mantenedor. Cómo informar un error, ejecutar las pruebas y enviar un cambio está en [CONTRIBUTING.es.md](CONTRIBUTING.es.md) (en inglés: [CONTRIBUTING.md](CONTRIBUTING.md); en portugués: [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md)). Un problema de seguridad no se informa en un issue público: mira [SECURITY.es.md](SECURITY.es.md).

## Licencia y créditos

REau es software libre bajo la **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Incorpora código
de otros proyectos abiertos; el archivo [NOTICE](NOTICE) enumera cada uno, con su licencia y lo que se usó, y la app muestra
los mismos créditos en la pantalla **Créditos**, al final de **"REau y tus datos"**, donde también se da la dirección del
código fuente propio de REau (la AGPL pide que los usuarios de la app puedan obtenerlo).
