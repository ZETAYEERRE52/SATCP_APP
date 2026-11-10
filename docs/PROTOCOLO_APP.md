# Protocolo de comunicación SATCP ↔ app Android

Versión del protocolo: **2** · Firmware: **2.0.0**

**Cambios respecto al protocolo 1:**
- **Todos los mensajes van cifrados** con la clave del equipo, salvo el saludo `hola` (§1b).
- **La red `SATCP` usa la clave del equipo como contraseña,** en lugar de `satcp1234`.
- **Comandos nuevos:** `vivo` y `cambiar_clave`.
- **La foto por HTTP y el video piden un token.**
- **El evento `estado` informa el estado de la válvula,** y hay un origen nuevo, `reinicio`.

El ESP32 expone el **mismo protocolo JSON** por dos transportes. El usuario elige cuál usar desde la app:

| Transporte | Comandos y eventos | Foto |
|---|---|---|
| **Wi-Fi** | WebSocket `ws://<ip>/ws` | HTTP `GET /foto/ultima.jpg?t=<token>` |
| **Bluetooth LE** | Servicio UART de Nordic (NUS) | Característica de imagen (notificaciones, cifrada) |

Los mensajes son idénticos en ambos transportes. Solo cambia cómo viajan y cómo se entrega la foto.

---

## 1. Conexión por Wi-Fi

### Redes disponibles

| Red | Cómo conectar | Dirección del ESP32 |
|---|---|---|
| **Red propia** (siempre activa) | SSID `SATCP`; la contraseña es la **clave del equipo** (§1b) | `192.168.4.1` |
| **Red de la casa** (opcional) | Se configura con el comando `wifi` | La asigna el router. Se descubre por mDNS |

### Descubrimiento en la red de la casa (mDNS)

- Nombre: `satcp.local`
- Servicio: `_satcp._tcp`, puerto `80`, registros TXT `ws=/ws`, `protocolo=2` y `vivo=81`
- En Android: `NsdManager.discoverServices("_satcp._tcp", NsdManager.PROTOCOL_DNS_SD, ...)`

### Endpoints

| Método | Ruta | Uso |
|---|---|---|
| WebSocket | `/ws` | Comandos y eventos cifrados. **Un mensaje JSON por trama de texto** |
| GET | `/foto/ultima.jpg?t=<token>` | Última foto tomada (`image/jpeg`). La URL completa con el token llega en la respuesta de `foto`. `403` sin token válido; `404` si aún no hay foto |
| GET | `/info` | Solo `dispositivo`, `firmware` y `protocolo`, en claro. Sirve para comprobar que la IP es un SATCP |
| GET | `:81/vivo?t=<token>` | Video en vivo de la cámara (MJPEG), **en el puerto 81**. La URL llega con el comando `vivo`. Ver §6b |

Límites:
- **Máximo 4 conexiones** simultáneas. Si se supera, se cierra la más antigua.
- **Mensajes de hasta 767 bytes** (el sobre cifrado completo). Si un mensaje es más largo, se cierra la conexión.

**Ejemplo con OkHttp:**
```kotlin
val solicitud = Request.Builder().url("ws://192.168.4.1/ws").build()
val ws = cliente.newWebSocket(solicitud, object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, texto: String) { procesarMensaje(texto) }
})
ws.send("""{"cmd":"hola","id":1}""")    // Lo único en claro; después, solo sobres cifrados (§1b)
```

---

## 1b. Seguridad: clave del equipo y mensajes cifrados

### La clave del equipo

- **Qué es:** un texto de 8 a 63 caracteres ASCII imprimibles. Es **la contraseña de la red `SATCP`** y **la base del cifrado** de todos los mensajes, por Wi-Fi (las dos redes) y por Bluetooth.
- **Clave de fábrica:**
  - Se genera **al azar la primera vez que arranca** cada equipo: 12 caracteres de `23456789abcdefghjkmnpqrstuvwxyz`, sin `0/o/1/l/i`, para evitar confusiones al copiarla.
  - Sale en el monitor serie en cada arranque (`SEGURIDAD: Clave de fábrica: ...`). Se escribe en una **etiqueta pegada al equipo**.
- **Cambio:** el usuario la cambia desde la app con `cambiar_clave`.
- **Si la olvida:** mantener presionado el botón BOOT **15 s** (con el sistema fuera de alerta y de bloqueo) devuelve la clave de fábrica, la de la etiqueta.
- **En la app:** se pide la clave una vez (al emparejar el equipo) y se guarda cifrada con `EncryptedSharedPreferences` o el Android Keystore. Sirve tanto para unirse a la red `SATCP` con `WifiNetworkSpecifier` como para el cifrado.

### Derivación de claves

```
clave_maestra = PBKDF2-HMAC-SHA256(contraseña = clave del equipo (UTF-8),
                                   sal = "SATCP-clave-v1", iteraciones = 10000, 32 bytes)
clave_sesion  = HMAC-SHA256(clave_maestra, "SATCP-sesion" || reto)        // 32 bytes → AES-256
```

- **Clave maestra:** se calcula una vez y se guarda en memoria mientras la app esté conectada. En Android: `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.
- **Reto:** son los 16 bytes que manda el ESP32 en `hola`, en hexadecimal. Se convierten a bytes antes de concatenarlos. `||` significa concatenar.
- **En Android,** HMAC con `Mac.getInstance("HmacSHA256")`.

### 1. Abrir la sesión: `hola` (en claro)

Lo primero tras conectar, en **cada conexión** (al abrir el WebSocket o tras activar las notificaciones TX):

```json
→ {"cmd":"hola","id":1}
← {"tipo":"hola","id":1,"ok":true,"protocolo":2,"reto":"00112233445566778899aabbccddeeff",
   "sal":"SATCP-clave-v1","iteraciones":10000}
```

- **Sesión nueva cada vez:** cada `hola` crea un reto aleatorio y deja sin valor la sesión anterior de esa conexión.
- **Sin espacio:** si hay demasiadas conexiones abiertas, llega `"ok":false,"error":"sin_espacio"`.
- **El primer evento `estado`** cifrado llega justo después del `hola`.

### 2. Sobre cifrado (todo lo demás, en ambos sentidos)

```json
{"c":"<base64( nonce de 12 bytes || texto cifrado || etiqueta GCM de 16 bytes )>"}
```

- **Cifrado:** AES-256-GCM con `clave_sesion`. En Android: `Cipher.getInstance("AES/GCM/NoPadding")` con `GCMParameterSpec(128, nonce)`. El resultado de `doFinal` ya trae la etiqueta al final.
- **Nonce:** 12 bytes aleatorios **nuevos en cada mensaje** (`SecureRandom`).
- **Datos asociados (AAD):** el texto ASCII `app` en los mensajes app → ESP32, y `esp` en los ESP32 → app (`cipher.updateAAD`). Así un mensaje no se puede reenviar en el sentido contrario.
- **Base64:** estándar, con relleno `=` y sin saltos de línea (`Base64.NO_WRAP` en Android).
- **Contenido:** el JSON de siempre (comando, respuesta o evento) con un campo extra **`n`**:
  - **App → ESP32:** `n` es un contador que empieza en 1 en cada sesión y **sube en cada mensaje**. El ESP32 rechaza un `n` igual o menor al último aceptado (error `repetido`). Así un mensaje capturado no se puede repetir.
  - **ESP32 → app:** el ESP32 pone su propio contador `n`, que empieza en 1 en cada sesión. La app debe descartar los mensajes cuyo `n` no sea mayor que el último recibido.

Ejemplo del contenido en claro de un comando:
```json
{"n":1,"cmd":"estado","id":2}
```

### 3. Errores de la capa de seguridad (en claro)

Si el ESP32 no puede leer el sobre, contesta **en claro**, porque no puede cifrar para quien no tiene la clave correcta:

```json
{"tipo":"respuesta","ok":false,"error":"no_autorizado","mensaje":"Clave incorrecta"}
```

| `error` | Causa | Qué hacer |
|---|---|---|
| `no_autorizado` | El sobre no descifra (clave equivocada o mensaje alterado), o se envió un comando en claro | Pedir de nuevo la clave al usuario |
| `sin_sesion` | Se envió un sobre sin haber hecho `hola` (o la sesión se cerró) | Enviar `hola` |
| `repetido` | El contador `n` no subió | Revisar el contador de la app |

Tras **3 sobres que no descifran**, el ESP32 cierra la sesión y hay que enviar `hola` de nuevo.

### Vectores de prueba (para las pruebas unitarias de la app)

| Dato | Valor |
|---|---|
| Clave del equipo | `k7m3pq9xw2ha` |
| `clave_maestra` (hex) | `df80c3463906c28f62504e9a8f4387ec5db9bfd080ffd0b8a0dcc0fc2e4d8666` |
| Reto (hex) | `00112233445566778899aabbccddeeff` |
| `clave_sesion` (hex) | `fc7a9ef318e8000f1636280f04002a294c8a2a39a127f6596919c6672c425402` |
| Nonce (hex) | `000102030405060708090a0b` |
| Texto en claro, AAD `app` | `{"n":1,"cmd":"estado","id":1}` |
| Sobre resultante | `{"c":"AAECAwQFBgcICQoLrG0G4TJP4eVdrRGusTJ3EilOrwdGXi6jklhe2L8lbcyNocwuf+j87jHfxtYL"}` |

### Lo que no va cifrado

- **`GET /info`:** solo nombre, firmware y versión del protocolo.
- **La foto por HTTP y el video en vivo:** las imágenes van sin cifrar, pero solo se abren con un **token de un solo propósito** que se obtiene por el canal cifrado:
  - **Foto:** dura 2 minutos.
  - **Video:** dura 30 segundos y sirve **para abrirlo una sola vez**.

  En la red `SATCP` el aire ya va cifrado por WPA2. En la red de la casa, otro equipo de la misma red que capture el tráfico podría ver esas imágenes, pero no enviar comandos.

---

## 2. Conexión por Bluetooth LE

- Nombre anunciado: **`SATCP`**
- El anuncio incluye el UUID del servicio UART, así que se puede filtrar el escaneo por ese UUID.
- Sin emparejamiento de Android: la protección es el cifrado de los mensajes con la clave del equipo (§1b).

### Servicios y características

| Servicio | Característica | UUID | Propiedades | Uso |
|---|---|---|---|---|
| UART (Nordic) | — | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | | |
| | **RX** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write, Write Without Response | La app **envía** comandos |
| | **TX** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify | La app **recibe** respuestas y eventos |
| Imagen (SATCP) | — | `8D6F0001-3C4E-4B1A-9F5E-5A7C0A7E5A01` | | |
| | **Datos** | `8D6F0002-3C4E-4B1A-9F5E-5A7C0A7E5A01` | Notify | Bytes del JPEG |

### Pasos al conectar

1. `connectGatt(...)` y, ya conectado, **`requestMtu(517)`**. Sin esto, cada notificación lleva 20 bytes y las fotos tardan muchísimo.
2. `discoverServices()`.
3. Activar notificaciones (escribir `0x0100` en el descriptor CCCD `0x2902`):
   - **TX**: imprescindible.
   - **Datos de imagen**: solo si se van a pedir fotos por Bluetooth.
4. Enviar `{"cmd":"hola","id":1}` por RX para abrir la sesión cifrada (§1b). El primer evento `estado` llega después.

### Formato de los mensajes por Bluetooth

- **App → ESP32 (RX):**
  - **Fin del mensaje:** cada mensaje JSON termina en `\n`. El ESP32 también acepta mensajes sin `\n` si el objeto JSON está completo.
  - **Mensajes largos:** si el mensaje supera el MTU, se puede enviar en varias escrituras seguidas; el ESP32 las une.
  - **Límite:** 767 bytes por mensaje (el sobre cifrado completo).
- **ESP32 → app (TX):** cada mensaje JSON termina en `\n` y puede llegar partido en varias notificaciones, según el MTU. **La app debe acumular hasta el `\n`** y recién entonces interpretar el JSON.

---

## 3. Formato común de los mensajes

Todo lo que describe esta sección y las siguientes viaja **dentro del sobre cifrado** de §1b, con el contador `n` agregado. Los ejemplos muestran el contenido ya descifrado y sin `n`, para que se lean mejor.

### Comando (app → ESP32)

```json
{"cmd": "foto", "id": 7, "imagen": true}
```

| Campo | Tipo | Descripción |
|---|---|---|
| `cmd` | string | Nombre del comando (obligatorio) |
| `id` | número o string | Opcional. Se devuelve igual en la respuesta para emparejarla. **Para pedir la foto por Bluetooth debe ser un número** |
| otros | | Parámetros propios de cada comando |

### Respuesta (ESP32 → app)

Llega **una respuesta por cada comando**, por el mismo transporte por donde llegó.

```json
{"tipo": "respuesta", "cmd": "foto", "id": 7, "ok": true, "datos": { ... }}
```

Si hay error:
```json
{"tipo": "respuesta", "cmd": "foto", "id": 7, "ok": false, "error": "camara_ocupada", "mensaje": "La cámara está ocupada, reintenta en unos segundos"}
```

### Evento (ESP32 → app, sin que la app lo pida)

```json
{"tipo": "evento", "evento": "estado", "datos": { ... }}
```

**Recomendación para la app:** distinguir siempre por `tipo` (`respuesta` o `evento`) y después por `cmd` o `evento`.

---

## 4. Comandos

### `info`
Datos del dispositivo. Conviene pedirlo al conectar para comprobar `protocolo`.

```json
→ {"cmd":"info","id":1}
← {"tipo":"respuesta","cmd":"info","id":1,"ok":true,"datos":{
     "dispositivo":"SATCP","firmware":"2.0.0","protocolo":2,"mac":"AA:BB:CC:DD:EE:FF",
     "wifi":{"ap_ssid":"SATCP","ap_ip":"192.168.4.1","mdns":"satcp.local","puerto_vivo":81,"casa_ip":"192.168.1.40"},
     "ble_nombre":"SATCP","clases":["fuego","humo","normal"]}}
```

### `estado`
Estado completo del sistema. Los `datos` tienen el mismo formato que el evento `estado` (§5).

```json
→ {"cmd":"estado","id":2}
← {"tipo":"respuesta","cmd":"estado","id":2,"ok":true,"datos":{ ...ver §5... }}
```

### `foto`
Toma una foto, la clasifica con el modelo y la guarda en la SD (`/sdcard/telefono/sNNNN/<clase>_<probabilidad>_NNNNNN.jpg`). Tarda alrededor de 1 s.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `imagen` | bool | Solo para **Bluetooth**: `true` para recibir también el JPEG (§6). Por Wi-Fi siempre se puede descargar |

```json
→ {"cmd":"foto","id":3}
← {"tipo":"respuesta","cmd":"foto","id":3,"ok":true,"datos":{
     "clasificada":true,
     "clase":"fuego",
     "probabilidades":{"fuego":0.921,"humo":0.031,"normal":0.048},
     "tiempo_ms":310,
     "guardada":true,
     "ruta_sd":"/sdcard/telefono/s0004/fuego_92_000012.jpg",
     "bytes":41234,
     "imagen":{"entrega":"http","url":"/foto/ultima.jpg?t=3f9a0c...e1"}}}
```

`imagen.entrega` puede valer:

| Valor | Significado |
|---|---|
| `http` | Wi-Fi: descargar con `GET http://<ip><url>`. La `url` ya trae el token, válido 2 minutos |
| `ble` | Bluetooth: el JPEG llega a continuación por la característica de imagen (§6) |
| `no_solicitada` | Bluetooth sin `"imagen":true` |
| `no_disponible` | Sin memoria para la copia; la clasificación sí es válida |

**Otros casos:**
- **Sin modelo:** si el modelo no está cargado, llega `"clasificada":false`, `"clase":null` y no vienen `probabilidades`.
- **Sin SD:** llega `"guardada":false` y `"ruta_sd":null`.

### `responder`
El usuario atiende la alerta y **cancela el cierre automático**. Solo es válido si el evento de estado trae `alerta.admite_respuesta = true`.

```json
→ {"cmd":"responder","id":4}
← {"tipo":"respuesta","cmd":"responder","id":4,"ok":true}
```
Error `sin_alerta` si no hay una alerta esperando respuesta.

### `rearmar`
Después de un cierre, el sistema queda bloqueado hasta este comando. Solo es válido con `alerta.requiere_rearme = true`.

**El sistema nunca abre el gas.** El botón de la válvula alterna entre abrir y cerrar, y el ESP32 solo lo pulsa para cerrar. Para volver a usar la estufa:
1. El usuario revisa la cocina y verifica que todas las perillas estén apagadas.
2. Abre la válvula **a mano**, con su botón.
3. Envía `rearmar`. Con eso el ESP32 registra que la válvula volvió a estar abierta.

```json
→ {"cmd":"rearmar","id":5}
← {"tipo":"respuesta","cmd":"rearmar","id":5,"ok":true}
```
- **Errores:**
  - `no_bloqueado`: el sistema no está bloqueado.
  - `valvula_cerrada`: el equipo tiene sensor de posición y la válvula sigue cerrada. Hay que abrirla primero.
- **Antes de enviar,** la app debe pedir una confirmación explícita, por ejemplo "¿Ya revisaste la cocina y abriste la válvula?".
- **Sin sensor de posición,** si el usuario envía `rearmar` sin haber abierto la válvula, el ESP32 la creerá abierta. En el siguiente cierre pulsaría el botón y la **abriría**. Por eso la confirmación es importante.

### `cerrar`
El usuario corta el suministro desde la app. Vale en **cualquier fase excepto `BLOQUEADO`**, incluso durante una alerta: el sistema pasa a `CIERRE_AUTOMATICO` con el mismo cierre y reporte que un cierre automático, y queda `BLOQUEADO` hasta `rearmar`.

```json
→ {"cmd":"cerrar","id":7}
← {"tipo":"respuesta","cmd":"cerrar","id":7,"ok":true}
```
- **Confirmación:** `ok` significa que el cierre fue aceptado. El cambio llega en los eventos `estado`: `fase` pasa a `CIERRE_AUTOMATICO` y luego a `BLOQUEADO`, con `alerta.origen = "manual"` y `requiere_rearme: true`.
- **Demora:** si el sistema está revisando el radar o la cámara, el cierre ocurre al terminar esa revisión (unos segundos como máximo).
- Error `ya_bloqueado` si el suministro ya está cerrado.

### `horarios`
Lista de cierres programados. Hasta **8 horarios**, guardados en el ESP32 (se conservan al reiniciar).

```json
→ {"cmd":"horarios","id":8}
← {"tipo":"respuesta","cmd":"horarios","id":8,"ok":true,"datos":{
     "hora_valida":true,"maximo":8,
     "horarios":[{"indice":0,"hora":22,"minuto":30,"dias":127,"activo":true},
                 {"indice":2,"hora":15,"minuto":0,"dias":31,"activo":false}]}}
```

| Campo | Descripción |
|---|---|
| `hora_valida` | `true` si el ESP32 ya obtuvo la hora por internet. Con `false` **los horarios no se ejecutan** |
| `horarios` | Solo los lugares ocupados, ordenados por `indice` (0 a 7) |
| `hora`, `minuto` | Hora local de Ciudad de México (UTC−6, sin horario de verano), formato 24 h |
| `dias` | Máscara de días: bit 0 = lunes, bit 1 = martes … bit 6 = domingo. `127` = todos los días, `31` = lunes a viernes, `96` = sábado y domingo |
| `activo` | `false` = guardado pero sin ejecutarse |

**Sobre la hora:**
- **Origen:** el ESP32 no tiene reloj con batería. Obtiene la hora por SNTP (`pool.ntp.org`) **solo cuando está conectado a la red de la casa**, que es la que tiene internet; la red propia `SATCP` no la da.
- **Tras un reinicio,** `hora_valida` vuelve a `false` hasta la siguiente sincronización, que tarda unos segundos después de conectarse.
- **Aviso en la app:** si `hora_valida` es `false`, conviene indicar que los horarios no se ejecutarán hasta configurar la red de la casa con `wifi`.

### `horario_guardar`
Crea o reemplaza un horario.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `indice` | entero 0-7, opcional | Lugar a reemplazar. Si se omite (o es `null`), se usa el primer lugar libre |
| `hora` | entero 0-23 | Hora del cierre |
| `minuto` | entero 0-59 | Minuto del cierre |
| `dias` | entero 1-127 | Máscara de días (ver `horarios`). Debe incluir al menos un día |
| `activo` | booleano, opcional | `true` por defecto |

```json
→ {"cmd":"horario_guardar","id":9,"hora":22,"minuto":30,"dias":127,"activo":true}
← {"tipo":"respuesta","cmd":"horario_guardar","id":9,"ok":true,"datos":{"indice":0,"hora_valida":true}}
```
- **Respuesta:** `datos.indice` dice en qué lugar quedó.
- **Errores:**
  - `parametros_invalidos`: un valor falta, no es entero o está fuera de rango.
  - `sin_espacio`: los 8 lugares están ocupados y no se indicó `indice`.

**Cuándo se ejecuta:**
- **Condiciones:** el sistema lo revisa una vez por minuto. Cierra solo si la fase es `MONITOREO`, la hora es válida y coinciden la hora, el minuto y el día.
- **Mismo flujo que un cierre automático:** pasa a `CIERRE_AUTOMATICO` y queda `BLOQUEADO` hasta `rearmar`, con `alerta.origen = "horario"`.
- **Sin repeticiones:** un mismo horario no se ejecuta dos veces en el mismo minuto.
- **Si la fase no es `MONITOREO` en ese minuto** (por ejemplo, durante una alerta), ese horario no se ejecuta ese día.

### `horario_borrar`
Libera un lugar. Borrar un lugar que ya estaba vacío también responde `ok`.

```json
→ {"cmd":"horario_borrar","id":10,"indice":2}
← {"tipo":"respuesta","cmd":"horario_borrar","id":10,"ok":true}
```
Error `parametros_invalidos` si falta `indice` o no está entre 0 y 7.

### `wifi`
Guarda la red de la casa (queda en memoria tras reiniciar) y se conecta a ella. La red propia `SATCP` sigue activa.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `ssid` | string | Nombre de la red (máx. 31 caracteres) |
| `clave` | string | Contraseña (máx. 63). Omitir si la red es abierta |

```json
→ {"cmd":"wifi","id":6,"ssid":"MiCasa","clave":"secreta123"}
← {"tipo":"respuesta","cmd":"wifi","id":6,"ok":true,"mensaje":"Conectando; la IP llegará en el evento de estado"}
```
La IP obtenida aparece después en `conexion.wifi_casa_ip` del evento `estado`.

### `vivo`
Pide permiso para abrir el video en vivo (§6b). **Solo por Wi-Fi.**

```json
→ {"cmd":"vivo","id":11}
← {"tipo":"respuesta","cmd":"vivo","id":11,"ok":true,"datos":{"puerto":81,"url":"/vivo?t=8c1e...04","vigencia_s":30}}
```
- **URL:** la app abre `http://<ip>:<puerto><url>`.
- **Token:** vale 30 s y **una sola apertura**. Para reabrir el video (al volver a la pantalla, o tras el corte de 10 minutos), hay que pedir `vivo` otra vez.
- **Por Bluetooth** responde el error `solo_wifi`.

### `cambiar_clave`
Cambia la clave del equipo, que también es la contraseña de la red `SATCP`.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `clave_nueva` | string | De 8 a 63 caracteres ASCII imprimibles |

```json
→ {"cmd":"cambiar_clave","id":12,"clave_nueva":"MiClaveSegura2026"}
← {"tipo":"respuesta","cmd":"cambiar_clave","id":12,"ok":true,"mensaje":"Clave cambiada: reconecta con la clave nueva (tambien es la de la red SATCP)"}
```
- **La respuesta todavía va cifrada con la sesión vieja.** Un segundo después, el ESP32 cierra **todas** las sesiones (de todos los teléfonos) y cambia la contraseña de la red `SATCP`. Los teléfonos conectados a esa red se desconectan.
- **La app debe:**
  1. Guardar la clave nueva.
  2. Recalcular la `clave_maestra`.
  3. Si usaba la red `SATCP`, volver a unirse con la contraseña nueva.
  4. Enviar `hola` otra vez.
- **En el formulario,** pedir la clave nueva dos veces para evitar errores de escritura.
- Error `parametros_invalidos` si la clave es muy corta, muy larga o tiene caracteres no admitidos.

---

## 5. Evento `estado`

Se envía a **todas las apps con sesión abierta** (por ambos transportes), cifrado para cada una, en estos casos:
- **Al abrir la sesión** con `hola`.
- **Al cambiar la fase** del sistema.
- **Cada 5 s** como latido. Si dejan de llegar, la conexión se perdió.

```json
{"tipo":"evento","evento":"estado","datos":{
  "fase":"ALERTA_USUARIO",
  "temperatura":{"sensor_ok":true,"medida":31.42,"estimada":31.4,"sensor":24.8,"linea_base":null},
  "presencia":{"estado":"nadie","distancia_movimiento_cm":0,"distancia_quieta_cm":0},
  "alerta":{"vision":"FUEGO","temperatura":31.4,"origen":"temperatura","cierre_en_s":95,
            "admite_respuesta":true,"requiere_rearme":false},
  "valvula":{"estado":"abierta","sensor":false},
  "conexion":{"wifi_casa_ip":"192.168.1.40","ble_conectado":true}}}
```

| Campo | Valores |
|---|---|
| `fase` | `MONITOREO`, `VALIDAR_PRESENCIA`, `DETECCION_VISUAL`, `ALERTA_USUARIO`, `CIERRE_AUTOMATICO`, `BLOQUEADO`, `AUTOCALIBRACION` |
| `temperatura.sensor_ok` | `false` si el sensor de temperatura no responde (desde el arranque o porque falló después). Ver "Falla del sensor" abajo |
| `temperatura.medida` | Lectura directa del sensor infrarrojo (°C) o `null` si no hay lectura |
| `temperatura.estimada` | Temperatura estimada del recipiente (°C), corregida por la calibración |
| `temperatura.sensor` | Temperatura interna del sensor (°C) |
| `temperatura.linea_base` | Temperatura estable ya verificada por la autocalibración, o `null` |
| `presencia.estado` | `nadie`, `en_movimiento`, `quieta`, `en_movimiento_y_quieta`, `sin_datos` |
| `alerta` | `null` si no hay alerta. Si existe, el objeto descrito abajo |
| `alerta.vision` | `FUEGO`, `HUMO`, `error de visión` (la cámara falló y se alerta por seguridad), `sin riesgo`. En los cierres `manual` y `horario` siempre es `sin riesgo`: no se consulta la cámara |
| `alerta.temperatura` | Temperatura (°C) al generarse la alerta. En `manual` y `horario` es la del momento del cierre, o `null` si el sensor no tenía lectura |
| `alerta.origen` | `temperatura` (anomalía), `autocalibracion` (fuego/humo hallado al verificar una temperatura estable), `manual` (comando `cerrar`), `horario` (horario programado) o `reinicio` (el equipo arrancó con la válvula cerrada de antes y sigue `BLOQUEADO`). `manual` y `horario` pasan directo a `CIERRE_AUTOMATICO`, sin `ALERTA_USUARIO` |
| `alerta.cierre_en_s` | Segundos para el cierre automático (0 fuera de `ALERTA_USUARIO`) |
| `alerta.admite_respuesta` | `true` → mostrar el botón que envía `responder` |
| `alerta.requiere_rearme` | `true` → el suministro se cerró; mostrar el botón que envía `rearmar` |
| `valvula.estado` | `abierta` o `cerrada` |
| `valvula.sensor` | `true` si el equipo tiene sensor de posición y el estado está medido. Con `false`, el estado es el que **supone** el ESP32: cambia a `cerrada` cuando él cierra, y a `abierta` con `rearmar` |

**Falla del sensor de temperatura** (`sensor_ok: false`):
- **El resto del sistema sigue funcionando:** comandos, foto, vista en vivo, `cerrar`, horarios y la cámara.
- **Valores:** `medida`, `estimada` y `sensor` llegan en `null`; nunca se envía una lectura vieja.
- **Vigilancia por cámara:** el sistema trata la falla como una anomalía, por seguridad. Revisa presencia y, si no hay nadie, la cámara, al detectar la falla y luego cada 30 s mientras dure. Si la cámara ve fuego o humo, hay alerta normal, con `alerta.temperatura` en `null`.
- **Recuperación sola:** el ESP32 reintenta cada 100 ms. Si el sensor vuelve a responder, `sensor_ok` pasa a `true` sin reiniciar.
- **En la app:** mostrar un aviso visible y permanente, por ejemplo "Sensor de temperatura sin respuesta: la detección depende solo de la cámara".

**Comportamiento sugerido en la app:**
- **Alerta nueva:** cuando `alerta` pasa de `null` a un objeto con `admite_respuesta: true`, mostrar una **notificación de alta prioridad** con sonido y la acción "Estoy en la cocina", que envía `responder`.
- **Cuenta regresiva:** mientras dure la alerta, mostrar `cierre_en_s`. Se actualiza con cada evento.
- **Para notificar con la app en segundo plano,** hace falta un *Foreground Service* que mantenga abierta la conexión BLE o el WebSocket.

---

## 6. Recepción de la foto por Bluetooth

Al enviar `{"cmd":"foto","id":N,"imagen":true}`, los mensajes llegan en este orden:

1. **Por TX:** la respuesta de `foto` con `"imagen":{"entrega":"ble"}`.
2. **Por TX:** el evento de inicio:
   ```json
   {"tipo":"evento","evento":"imagen_inicio","id":N,"bytes":41262,"crc32":2864434397,"fragmento":514}
   ```
3. **Por la característica de imagen:** notificaciones con los bytes **cifrados** de la foto, **en orden** y sin encabezado. Hay que concatenarlas hasta juntar `bytes`.
4. **Por TX:** el evento de cierre:
   ```json
   {"tipo":"evento","evento":"imagen_fin","id":N}
   ```
   Si se interrumpió la transferencia, llega en cambio `{"tipo":"evento","evento":"imagen_error","id":N}`.

**Verificación:** calcular el CRC-32 de los bytes recibidos (cifrados) y compararlo con `crc32`. Es el CRC-32 estándar, el mismo que `java.util.zip.CRC32`; conviene confirmarlo en la primera prueba. Si no coincide, descartar la imagen y volver a pedirla.

**Descifrado:** los bytes juntos tienen el formato `nonce (12) || JPEG cifrado || etiqueta (16)`, cifrados con AES-256-GCM, la `clave_sesion` y AAD = `img`. Por eso `bytes` es 28 más que el JPEG. Si `doFinal` falla, la imagen está alterada: descartarla.

**Si las notificaciones de imagen no están activadas,** llega `{"tipo":"evento","evento":"imagen_error","id":N,"error":"sin_suscripcion"}`.

**Velocidad:** con MTU 517, una foto de ~40 KB tarda unos pocos segundos. Mientras se envía, el ESP32 no atiende otros comandos de la app.

---

## 6b. Vista en vivo de la cámara (solo Wi-Fi)

Sirve para **ajustar la posición del equipo**: el usuario ve en el teléfono lo mismo que ve la cámara y la mueve hasta que las hornillas queden bien encuadradas.

- **URL:** se pide con el comando `vivo`, que devuelve el puerto y la ruta con el token. Se abre `http://<ip>:81/vivo?t=<token>`; en la red propia, `<ip>` es `192.168.4.1`. Sin token válido, responde `403`.
- **Formato:** MJPEG, es decir, una respuesta HTTP que no termina (`Content-Type: multipart/x-mixed-replace;boundary=satcpcuadro`). Cada parte es un JPEG de 320×240 (la cámara baja la resolución mientras dura el video y la restaura al terminar):
  ```
  --satcpcuadro
  Content-Type: image/jpeg
  Content-Length: 41234

  <bytes del JPEG>
  ```
- **Velocidad:** hasta unos 10 cuadros por segundo, según la señal Wi-Fi.
- **Un espectador a la vez.** Si ya hay alguien mirando, responde `503 Service Unavailable`.
- **Duración máxima: 10 minutos.** Pasado ese tiempo, el ESP32 cierra la transmisión. Para seguir, la app envía `vivo` otra vez y abre la URL nueva.
- **Para terminar,** la app simplemente cierra la conexión HTTP. Debe hacerlo al salir de la pantalla de vista en vivo.

**Cómo mostrarla en Android:**
- **La forma más simple:** un `WebView` que cargue un HTML mínimo con `<img src="http://192.168.4.1:81/vivo?t=...">`, porque los navegadores muestran MJPEG de forma nativa.
- **La alternativa:** leer el flujo con OkHttp y separar los JPEG por el `boundary` y el `Content-Length`, decodificando cada uno con `BitmapFactory`.

**Efecto en el sistema:**
- **La vigilancia tiene prioridad.** La transmisión suelta la cámara después de cada cuadro, así que la fase visual de una alerta puede tomar sus fotos sin esperar. Mientras ocurre una alerta, el video puede verse entrecortado.
- **Comandos y alertas siguen funcionando.** Van por el servidor del puerto 80 y el WebSocket, que no se bloquean por el video.
- **Por Bluetooth no hay video:** es demasiado lento. Por ese transporte, la app puede usar `{"cmd":"foto","imagen":true}` repetidamente como vista previa (unos segundos por foto).

---

## 7. Códigos de error

| `error` | Causa | Qué hacer |
|---|---|---|
| `json_invalido` | El mensaje no es un objeto JSON | Revisar el formato |
| `parametros_invalidos` | Falta `cmd` o un parámetro obligatorio | Revisar el comando |
| `comando_desconocido` | `cmd` no existe en esta versión | Comprobar `protocolo` con `info` |
| `camara_ocupada` | El sistema está usando la cámara (fase visual o captura periódica) | Reintentar en 2-3 s |
| `camara_no_disponible` | La cámara no se inicializó | Revisar el hardware |
| `sin_alerta` | `responder` sin alerta pendiente | Ignorar (la alerta ya terminó) |
| `no_bloqueado` | `rearmar` sin bloqueo | Ignorar |
| `valvula_cerrada` | `rearmar` con el sensor de posición indicando válvula cerrada | Pedir al usuario que la abra a mano |
| `solo_wifi` | `vivo` por Bluetooth | Ofrecer la vista previa con `foto` |
| `no_autorizado`, `sin_sesion`, `repetido` | Errores de la capa de seguridad, en claro (§1b) | Ver §1b |
| `ya_bloqueado` | `cerrar` con el suministro ya cerrado | Ignorar; para volver a usarlo, `rearmar` |
| `sin_espacio` | `horario_guardar` sin `indice` y con los 8 lugares ocupados | Borrar un horario o indicar `indice` para reemplazarlo |
| `wifi_error` | No se pudo guardar la red | Nombre o clave demasiado largos |

---

## 8. Limitaciones actuales y pendientes

- **Seguridad:**
  - **Imágenes sin cifrar por HTTP:** en la red de la casa, la foto y el video viajan sin cifrar (protegidos solo por el token). Los comandos y el estado sí van cifrados.
  - **La clave de fábrica está en la etiqueta** y sale por el monitor serie: quien tenga acceso físico al equipo puede leerla.
- **Válvula sin sensor de posición:** el ESP32 supone su estado. Si alguien la acciona a mano sin usar `rearmar`, la suposición queda mal. Con un final de carrera en `PIN_VALVULA_POSICION` el estado se mide (§4, `rearmar`).
- **Horarios sin red de la casa:** sin internet no hay hora, y los horarios no se ejecutan (`hora_valida: false`).
- **Notificaciones con la app cerrada:** no hay servidor en la nube. La app solo recibe alertas mientras mantiene una conexión abierta.
- **Clientes:** se admite una conexión Bluetooth y hasta 4 WebSocket al mismo tiempo, más un espectador de la vista en vivo.
- **Cambios de versión:** al modificar el formato de los mensajes se incrementa `protocolo` (constante `SATCP_VERSION_PROTOCOLO` en `components/comunicaciones/protocolo.h`).

---

## 9. Pruebas sin la app

Con el cifrado ya no sirven nRF Connect ni un cliente WebSocket genérico, salvo para el `hola`. Para probar el firmware sin la app está el cliente de pruebas `herramientas/cliente_satcp.py`, que habla el protocolo completo por Wi-Fi:

```
python herramientas/cliente_satcp.py --ip 192.168.4.1 --clave <clave del equipo> estado
python herramientas/cliente_satcp.py --ip 192.168.4.1 --clave <clave> foto      # guarda foto.jpg
python herramientas/cliente_satcp.py --ip 192.168.4.1 --clave <clave> vivo      # imprime la URL del video
python herramientas/cliente_satcp.py --ip 192.168.4.1 --clave <clave> cerrar
```

Necesita el paquete `cryptography`, que ya viene en el Python de ESP-IDF.
