# Lista de revisión de la app SATCP con el ESP32 real

Protocolo 2 · firmware 2.0.0 · app del 7 de noviembre de 2026.

Marca cada punto con `[x]` cuando lo compruebes. Si algo falla, anota la hora y copia el log del
monitor serie y el de la app (ver "Cómo sacar los logs" al final).

## 0. Antes de empezar

- [ ] El ESP32 tiene el firmware **2.0.0** (el monitor serie lo dice al arrancar). La app ya no se conecta a 1.2.0.
- [ ] Tienes a mano la **clave de fábrica** de la etiqueta.
- [ ] Instalaste esta versión de la app (Android Studio o `adb install`).
- [ ] La **llave de paso del gas está cerrada** para las pruebas de válvula y rearme.

## A. Seguridad y conexión

- [ ] **Vectores en el teléfono:** con el teléfono por USB, corre `SeguridadDispositivoTest`. Debe pasar.
- [ ] **Red `SATCP`:** al conectar, Android pide unirse con la clave del equipo. El panel pasa a "Conectado" y muestra fase, temperatura, presencia y válvula.
- [ ] **Red de la casa:** se conecta y en Ajustes aparece la IP del equipo (`26.x.x.x`).
- [ ] **Bluetooth:** se conecta y el panel se llena igual.
- [ ] **Clave incorrecta:** escribe una equivocada. Debe decir que el equipo no la aceptó y volver a pedirla, sin reintentar sin fin.

## B. Clave del equipo

- [ ] **Se guarda:** cierra la app por completo y ábrela. No debe pedir la clave de nuevo.
- [ ] **Cambio de clave:** en Ajustes, cámbiala escribiendo la nueva dos veces. A los pocos segundos debe reconectar sola. En `SATCP`, Android puede pedirte confirmar la red.
- [ ] **Clave nueva en otro teléfono o en el cliente de pruebas:** la vieja ya no funciona.
- [ ] **Volver a la de fábrica:** BOOT 15 s y luego "Escribir otra clave del equipo" en la app.

## C. Comandos

- [ ] **Cerrar suministro:** pide confirmación, la fase pasa a `CIERRE_AUTOMATICO` y luego a `BLOQUEADO`.
- [ ] **Cerrar ya bloqueado:** avisa que ya estaba cerrado.
- [ ] **Rearmar:** pide "¿Revisaste la cocina, apagaste las perillas y abriste la válvula a mano?". Tras aceptar, la válvula pasa a "Abierta".
- [ ] **Rearmar con la válvula cerrada:** solo si tienes sensor de posición. Debe pedirte abrirla primero.
- [ ] **Horarios:** crea uno para dentro de 2 minutos. A esa hora debe cortar el suministro con origen "horario".
- [ ] **Hora no válida:** conectado solo por `SATCP` tras un reinicio, la pantalla de horarios avisa que no se ejecutarán.
- [ ] **Ocho horarios:** al intentar el noveno, avisa que no hay espacio.

## D. Evento de estado

- [ ] **Latido:** la conexión se mantiene "Conectado" con el equipo quieto al menos 5 minutos.
- [ ] **Sensor de temperatura caído:** desconecta el MLX90614 con el equipo apagado y enciéndelo. Aparece el aviso permanente y las temperaturas se muestran como "—". Al reconectarlo, el aviso desaparece solo.
- [ ] **Válvula:** el panel indica si el estado es medido o supuesto.
- [ ] **Cierre manual y por horario:** la alerta no muestra la parte de visión.

## E. Foto

- [ ] **Por Wi-Fi:** "Tomar foto" muestra la imagen, la clase y las barras.
- [ ] **Por Bluetooth:** igual, tarda unos segundos más.
- [ ] **Error con causa:** si la descarga falla, debajo del aviso aparece la causa (403, tiempo agotado, sin ruta…).

## F. Vista en vivo

- [ ] **Abre:** el video aparece en pocos segundos.
- [ ] **Lag:** anota los números de "Cuadros por segundo: X recibidos, Y mostrados".
  - Cerca de 10 y 10, sin retraso visible: bien.
  - Recibidos mucho más altos que mostrados: el problema está en el teléfono.
  - Ambos bajos: el ESP32 o la red entregan pocos cuadros.
  - Cerca de 10 pero con más de 1 s de retraso: el firmware manda cuadros viejos.
- [ ] **Parar e iniciar:** "Parar transmisión" congela y oscurece la imagen, e "Iniciar transmisión" la recupera.
- [ ] **Salir y volver a la pestaña:** se corta al salir y arranca con un token nuevo al volver.
- [ ] **Otro espectador:** con el video abierto en la app, ábrelo con `herramientas/cliente_satcp.py ... vivo`. La segunda conexión recibe 503.
- [ ] **10 minutos:** deja el video abierto 10 minutos. Debe ofrecer "Reanudar".
- [ ] **Por Bluetooth:** explica que el video necesita Wi-Fi y ofrece "Actualizar" con fotos.

## G. Red y conexión estable

- [ ] **Latido del WebSocket (nuevo, lo más importante de esta versión):** conectado por Wi-Fi, deja la app 5 minutos sin tocarla. **No debe reconectar cada 15 a 30 s.** Si lo hace, el firmware no está contestando los ping del WebSocket: avísame para quitarlo o pedir el cambio en el firmware.
- [ ] **IP nueva del equipo (nuevo):** conéctate una vez por la red de la casa. Luego, en el router, dale otra IP al ESP32 (o reinícialo si cambia sola). La app debe encontrarla sola.
- [ ] **Pantalla apagada:** conectado por la red de la casa, apaga la pantalla 10 minutos. Al encenderla debe seguir "Conectado". Mira en el log del ESP32 si hubo desconexiones.
- [ ] **Equipo apagado:** apágalo. Debe mostrar "Reconectando" con esperas crecientes y, pasado 1 minuto, el aviso de que no recibirás alertas. Al encenderlo, reconecta solo y el aviso se quita.
- [ ] **Optimización de batería:** en Ajustes, "Excluir la app". La tarjeta cambia a "La app está excluida".
- [ ] **Red que no se encuentra:** envía una red de 5 GHz o con el nombre mal escrito. A los 30 s, la tarjeta de Wi-Fi avisa que el equipo no logró conectarse.
- [ ] **Red correcta:** envía la red de 2.4 GHz. La IP aparece en la tarjeta de Wi-Fi y no sale el aviso.
- [ ] **Redes guardadas:** por Bluetooth, guarda tu red. Cierra la app, ábrela y usa "Conectar" desde la lista.

## H. Mensajes de error

- [ ] **Video con el equipo apagado:** debe decir la causa (sin ruta o tiempo agotado), no solo "No se pudo abrir".
- [ ] **Logcat:** las URL aparecen como `...?t=…`, nunca con el token completo.

## Alertas (lo más importante para la seguridad)

- [ ] **Alerta completa:** provoca una anomalía (por ejemplo, una fuente de calor frente al sensor) sin nadie frente al radar. Debe sonar, vibrar y abrir la pantalla completa, aun con la app en segundo plano.
- [ ] **"Estoy en la cocina":** desde la pantalla y desde la notificación. Cancela el cierre.
- [ ] **Sin respuesta:** deja correr la cuenta regresiva. Debe cerrar y pedir rearme.
- [ ] **Con la pantalla apagada:** repite la alerta con la pantalla apagada y la app cerrada.
- [ ] **Segunda anomalía:** tras una revisión sin riesgo, anota cuánto tarda en revisar otra vez. Hasta unos 60 s es lo esperado con el firmware actual.

## Cómo sacar los logs

Con el teléfono por USB:

```
adb logcat -s ClienteSatcp TransporteWifi VivoViewModel TransporteBle ServicioVigilancia
```

Del ESP32, el monitor serie completo desde el arranque. Ojo: muestra la clave de fábrica, así que no lo compartas fuera de estas conversaciones.
