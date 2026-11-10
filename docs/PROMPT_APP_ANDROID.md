Vamos a construir la app Android de SATCP, un sistema que vigila la estufa de una cocina y evita incendios por descuido. El hardware es un ESP32-S3 con un sensor de temperatura infrarrojo, un radar de presencia y una cámara que clasifica fuego, humo o normal. El firmware ya está terminado; la app es el control remoto y el canal de alertas del usuario.

## Fuente de verdad

El protocolo completo está en `docs/PROTOCOLO_APP.md` de este proyecto. Léelo entero antes de escribir código. Ahí están los transportes, UUIDs, endpoints, todos los comandos con ejemplos JSON de ida y vuelta, el evento de estado, la transferencia de la foto por Bluetooth y los códigos de error. Si algo de este prompt contradice ese documento, manda el documento. Si algo no está claro en él, pregúntame antes de inventarlo: no puedo cambiar el firmware para que se adapte a la app.

## Qué hace la app

El usuario elige cómo conectarse: por Wi-Fi o por Bluetooth LE. Los dos transportes llevan exactamente los mismos mensajes JSON, así que la app debe tener una sola capa de protocolo y dos implementaciones de transporte intercambiables.

1. **Conexión**
   - Bluetooth: escanear filtrando por el UUID del servicio UART de Nordic (`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`), conectar al dispositivo `SATCP`, pedir MTU 517, descubrir servicios y activar notificaciones en TX y en la característica de imagen.
   - Wi-Fi: dos modos.
     - Red propia: conectarse a la red `SATCP` (clave `satcp1234`) desde la app con `WifiNetworkSpecifier`, y el ESP32 queda en `192.168.4.1`.
     - Red de la casa: descubrirlo con `NsdManager` (servicio `_satcp._tcp`), o escribir la IP a mano.
     - En ambos casos, abrir el WebSocket `ws://<ip>/ws`.
   - Recordar el último transporte y dispositivo usados, y reconectar solo con espera creciente si se pierde la conexión.
2. **Panel de estado**
   - Qué muestra: la fase del sistema, la temperatura medida y la estimada, la presencia según el radar y el estado de la conexión.
   - Cómo se actualiza: con cada evento `estado` (llega al conectar, al cambiar la fase y cada 5 s).
   - Si pasan 15 s sin eventos, mostrar la conexión como perdida.
3. **Alertas** (lo más importante)
   - Cuando el evento `estado` trae `alerta` con `admite_respuesta: true`, mostrar una notificación de máxima prioridad con sonido, vibración y *full-screen intent*, aunque la app esté en segundo plano.
   - Mostrar también una pantalla de alerta con la cuenta regresiva `cierre_en_s` y un botón grande "Estoy en la cocina" que envía `responder`.
   - La notificación también lleva esa acción.
   - Con `requiere_rearme: true`, mostrar que se cortó el suministro y un botón "Ya revisé: rearmar" que envía `rearmar`, pidiendo confirmación antes.
4. **Cámara**
   - Botón "Tomar foto" que envía `foto`.
   - Muestra la clase y las barras de probabilidad de fuego, humo y normal, el tiempo de inferencia, si se guardó en la SD y la imagen.
   - Por Wi-Fi, la imagen se descarga por HTTP de `datos.imagen.url`. Por Bluetooth, se pide con `"imagen": true` y se recibe por la característica de imagen entre `imagen_inicio` e `imagen_fin`, verificando el CRC-32 con `java.util.zip.CRC32`.
   - Si el CRC no coincide, descartar y avisar.
   - Ante `camara_ocupada`, reintentar sola después de 3 s, una vez.
5. **Vista en vivo** (para ajustar la posición del equipo)
   - Pantalla que muestra el video de la cámara desde `http://<ip>:81/vivo` (MJPEG, ver §6b del protocolo).
   - Sobre el video, una cuadrícula de tercios para ayudar a encuadrar las hornillas.
   - Implementación: un `WebView` con un `<img>` que apunte a esa URL, o un lector propio con OkHttp que separe los JPEG. Elige y justifica en el plan.
   - Al salir de la pantalla o al pasar la app a segundo plano, cerrar la conexión del video.
   - Si responde `503`, avisar que otra persona está mirando. Si la transmisión se corta a los 10 minutos, ofrecer un botón para reanudar.
   - Solo existe por Wi-Fi. Si el usuario está conectado por Bluetooth, mostrar una vista previa con `foto` e `"imagen": true` cada vez que toque "Actualizar", y explicar que el video en vivo requiere Wi-Fi.
6. **Configuración**
   - Formulario para configurar el Wi-Fi de la casa con el comando `wifi`.
   - Información del dispositivo obtenida con `info`: firmware, versión de protocolo, MAC y clases del modelo.
   - Si `protocolo` no es 1, avisar que la app y el firmware son incompatibles.

## Servicio en segundo plano

La app solo recibe alertas mientras mantiene la conexión abierta: el ESP32 no tiene servidor en la nube. Por eso:
- **Servicio en primer plano:** la conexión (BLE o WebSocket) y el procesamiento de mensajes viven en un *foreground service* de tipo `connectedDevice`, con su notificación permanente "SATCP vigilando la cocina".
- **Alertas sin la pantalla abierta:** el servicio es quien lanza la notificación de alerta, aunque la interfaz esté cerrada.
- **Arranque y parada:** el usuario lo inicia al conectarse y puede detenerlo desde la app.

## Requisitos técnicos

- Kotlin, Jetpack Compose con Material 3, arquitectura MVVM, corrutinas y `Flow`/`StateFlow`. Un solo módulo `app` es suficiente.
- `minSdk` 26 y `targetSdk` la última versión estable.
- JSON con `kotlinx.serialization`, WebSocket y HTTP con OkHttp. No agregues más librerías sin preguntarme.
- **Capas sugeridas:**
  - `protocolo/`: modelos de mensajes y el codificador/decodificador.
  - `transporte/`: interfaz común más `TransporteBle` y `TransporteWifi`.
  - `servicio/`: el *foreground service*.
  - `ui/`: pantallas y ViewModels.
- **Bluetooth:**
  - Android permite una sola operación GATT a la vez: implementa una cola que serialice escrituras, descriptores y la petición de MTU.
  - Cada mensaje enviado termina en `\n`.
  - Al recibir por TX, acumula los fragmentos hasta el `\n` antes de interpretar el JSON.
- **Wi-Fi en la red propia:** la red `SATCP` no tiene internet, y Android puede mandar el tráfico por datos móviles. Usa el `Network` que devuelve `WifiNetworkSpecifier` y haz que OkHttp salga por él (`network.socketFactory` o `bindProcessToNetwork`).
- **HTTP sin cifrar:** el ESP32 solo habla HTTP. Permite tráfico sin cifrar solo para `192.168.4.1` y la red local, mediante `network_security_config`; no lo permitas en toda la app.
- **Permisos:**
  - Bluetooth: `BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT` desde Android 12, o ubicación en versiones anteriores.
  - Notificaciones: `POST_NOTIFICATIONS` desde Android 13.
  - Servicio: `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
  - Pantalla completa: `USE_FULL_SCREEN_INTENT`.
  - Red: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` y `CHANGE_WIFI_STATE`.
  - Pide cada permiso en el momento en que se necesita y explica por qué.
- **Emparejar respuestas:** cada comando lleva un `id` numérico creciente. Empareja cada respuesta por su `id`, con un tiempo de espera de 10 s (30 s para `foto` por Bluetooth).

## Idioma del código

- **Todo en español:** nombres de clases, funciones, variables, parámetros, constantes, paquetes propios y comentarios. Por ejemplo, `conectarDispositivo()`, `enviarComando()`, `procesarEventoEstado()`, `class TransporteBle`, `val temperaturaEstimada`.
- **Lo que queda en inglés:** las palabras reservadas de Kotlin, las APIs de Android y de las librerías, y los métodos que sobrescribes de ellas (`onCreate`, `onCharacteristicChanged`, `onMessage`...).
- **Nombres del protocolo:** los campos JSON se llaman como en el protocolo (ya están en español). Usa `@SerialName` solo donde haga falta.
- **Textos de la interfaz:** en español, en `strings.xml`.
- **Sin tildes ni ñ en los identificadores:** escribe `configuracion`, no `configuración`, y `anio`, no `año`.

## Pruebas

Pruebas unitarias de lo que no depende del hardware:
- **Protocolo:** decodificación de cada mensaje de ejemplo de `docs/PROTOCOLO_APP.md`.
- **Bluetooth:** reensamblado de mensajes partidos en varias notificaciones.
- **Imagen:** armado de la foto por fragmentos y verificación del CRC-32.
- **Comandos:** emparejamiento de respuestas por `id` y tiempo de espera.

## Cómo trabajar

1. Antes de programar, dame un plan corto: estructura de paquetes, clases principales y orden de implementación. Espera mi visto bueno.
2. Implementa por etapas y deja la app compilando al final de cada una:
   1. Protocolo y pruebas.
   2. Transporte Wi-Fi y panel de estado.
   3. Transporte Bluetooth.
   4. Servicio en primer plano y alertas.
   5. Cámara.
   6. Vista en vivo.
   7. Configuración.
3. Al terminar cada etapa, dime qué probar con el ESP32 real y qué debería verse.
4. No inventes campos, comandos ni comportamientos del firmware que no estén en el protocolo.
