# SATCP — App Android

App de control remoto y canal de alertas del **SATCP**, un monitor de estufa basado en ESP32-S3. El equipo vigila la cocina con un sensor de temperatura infrarrojo, un radar de presencia y una cámara con clasificación de fuego y humo. Si detecta un riesgo y nadie responde, **corta el suministro de gas**. La app permite ver lo que pasa, responder a las alertas y configurar el equipo desde el teléfono.

> La app nunca abre el gas a distancia: solo puede cerrarlo o rearmar el sistema después de que alguien revisó la cocina.

- **Protocolo:** 2
- **Firmware compatible:** 2.1.0
- **Android:** 8.0 (API 26) o superior
- **Tecnología:** Kotlin, Jetpack Compose, MVVM, corrutinas, OkHttp y kotlinx.serialization
- **Especificación del protocolo:** [`docs/PROTOCOLO_APP.md`](docs/PROTOCOLO_APP.md)

---

## Cómo se conecta

La app se conecta al equipo de tres formas, **una sola a la vez**. Al elegir una, cierra la anterior.

| Forma | Cuándo usarla |
|---|---|
| **Red `SATCP`** | Wi-Fi propio del equipo (`192.168.4.1`). Funciona sin router; la app se une sola a esa red. |
| **Red de la casa** | El equipo y el teléfono están en el mismo Wi-Fi. La app lo encuentra por mDNS (`satcp.local`) o por la última IP que el equipo informó. |
| **Bluetooth LE** | Cuando no hay Wi-Fi. Funciona todo salvo el video en vivo. |

Si la conexión se pierde, la app reintenta sola y avisa con una notificación que, mientras tanto, no llegarán alertas.

## Seguridad

- **Clave del equipo:** cada equipo tiene una clave (también es la contraseña de la red `SATCP`). La app la pide la primera vez y la guarda cifrada en el teléfono.
- **Mensajes cifrados:** todos los mensajes van cifrados y autenticados con **AES-256-GCM**, con una clave de sesión nueva en cada conexión y un contador que rechaza mensajes repetidos. Sin la clave, nadie puede leer ni enviar comandos, aunque esté en la misma red.
- **Video y fotos:** solo se abren con un permiso de un solo uso que el equipo entrega por el canal cifrado.

---

## Pantallas

### Cocina (panel principal)
Muestra el estado del equipo en tiempo real:
- **Fase del sistema:** vigilando, validando presencia, revisando con la cámara, alerta, cierre, bloqueado, calibrando…
- **Válvula de gas:** abierta o cerrada, y si el dato está medido o lo supone el equipo.
- **Temperatura:**
  - La medida y la estimada del área.
  - La **temperatura ambiente de referencia** y a partir de cuánto se inicia una revisión.
  - El **umbral de estufa encendida** (calibrado o de fábrica).
- **Presencia:** si el radar detecta a alguien y a qué distancia.
- **Aviso permanente** si el sensor de temperatura deja de responder: la vigilancia depende entonces solo de la cámara.
- **Cerrar suministro:** corta el gas al momento, con confirmación. Queda bloqueado hasta rearmar.

### Alertas
Es la función más importante. Un servicio en primer plano mantiene la conexión abierta aunque la app esté cerrada o la pantalla apagada.
- **Alerta nueva:** el teléfono suena, vibra y abre una **pantalla completa** con el motivo (fuego, humo o falla de visión) y la **cuenta regresiva** del cierre automático.
- **"Estoy en la cocina":** desde la pantalla o desde la notificación, cancela el cierre.
- **Corte de suministro:** si nadie responde, el equipo corta el gas y la app avisa. Tras revisar la cocina, **"Ya revisé: rearmar"** vuelve a habilitar el sistema.

### Cámara
- **Foto al momento:** toma una foto con el clasificador del equipo y muestra la clase (fuego, humo, sin riesgo) con sus probabilidades.
- **Transporte:** por Wi-Fi la imagen se descarga por HTTP; por Bluetooth llega cifrada en fragmentos.
- **Si la descarga falla,** la app explica la causa real: tiempo agotado, sin ruta, equipo ocupado…

### En vivo
- **Video de la cámara (solo Wi-Fi),** con una cuadrícula de tercios para encuadrar las hornillas.
- **Por Bluetooth** ofrece una vista previa con fotos.
- **Al salir:** el video se cierra al dejar la pantalla o pasar la app a segundo plano.

### Horarios
- **Hasta 8 horarios** de cierre automático, con hora y días de la semana.
- **Hora del equipo:** se usa la hora de Ciudad de México, que el equipo obtiene por internet. La app avisa si todavía no la tiene.

### Ajustes
- **Wi-Fi de la casa:** envía al equipo el nombre y la contraseña de la red y confirma que obtuvo una IP.
- **Redes guardadas** en el teléfono para reconectar rápido.
- **Calibrar con la estufa:**
  - **Inicio:** con la estufa apagada, se toca *Comenzar*. Cuando la app lo indica, se enciende una parrilla al mínimo.
  - **Medición:** la app muestra en vivo cuánto sube la temperatura.
  - **Resultado:** el 80 % de la subida pasa a ser el umbral que el equipo considera "estufa encendida".
- **Cambiar la clave del equipo:** la app reconecta sola con la clave nueva.
- **Optimización de batería:** pide excluir la app para que Android no corte la vigilancia.
- **Información del equipo:** firmware, protocolo, MAC, clases del clasificador y redes.

---

## Permisos que usa

| Permiso | Para qué |
|---|---|
| Wi-Fi y red | Unirse a la red `SATCP` y hablar con el equipo |
| Bluetooth | Buscar y conectar el equipo por BLE |
| Notificaciones | Avisar alertas y pérdida de conexión |
| Servicio en primer plano | Mantener la vigilancia con la app cerrada |
| Pantalla completa | Mostrar la alerta aunque el teléfono esté bloqueado |
| Ignorar optimización de batería | Que el sistema no detenga la conexión |

## Compilar

```bash
./gradlew assembleDebug        # APK de depuración
./gradlew installDebug         # instalar en el teléfono conectado
./gradlew testDebugUnitTest    # pruebas unitarias
```

La lista de pruebas con el equipo real está en [`docs/LISTA_DE_REVISION.md`](docs/LISTA_DE_REVISION.md).
