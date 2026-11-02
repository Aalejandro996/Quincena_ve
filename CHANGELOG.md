# Historial de versiones — Quincena

Cada cambio se registra aquí. La versión de Android sube en `android/app/build.gradle.kts`
(o sola, cuando la compila GitHub Actions: `versionCode` = 100 + número de ejecución).
La web no tiene número propio: cada entrada indica qué cambió en la web y en la app.

Formato: **Agregado** (nuevo), **Cambiado** (mejoras), **Corregido** (errores), **Quitado**.

---

## Cargas visibles y pagos mixtos (web)
- Pantalla de carga con Quinci y barra animada al abrir la app (ya viene en el HTML, se ve aunque el servidor esté despertando), con mensajes de lo que está pasando.
- Barra fina arriba mientras hay peticiones al servidor; los botones de los formularios muestran un indicador mientras guardan.
- Portada y panel de administración: barra de carga al abrir la app o cambiar de pestaña.
- Pagos mixtos: un gasto se puede dividir en varios métodos (pago móvil, efectivo, transferencia, tarjeta, punto, Zelle, Binance), cada uno en su moneda y cuenta; en Movimientos se ve como un solo gasto con sus métodos.

## Android: todas las mejoras nuevas
- Quinci en Inicio y sus reacciones; tutorial de bienvenida (también en Más › Ver el tutorial).
- Ver montos en $ BCV, USDT, € o Bs.; gastos con fecha; pagos fijos manuales o automáticos y compromisos sin duplicar; plan de pagos con orden, monto por quincena, comparación y programar el mes.
- Mi Negocio para profesionales: tipo de negocio y profesión, cobros con concepto, por cobrar, clientes y recibo de honorarios.
- Enviar sugerencia desde Más.

## Tutorial de bienvenida y Mi Negocio para profesionales (web)
- Tutorial con Quinci la primera vez: ingreso, pagos fijos, deudas, cómo ver los montos y un recorrido por la app (con el paso del negocio si lo tiene). Se puede saltar y volver a ver desde Más.
- Mi Negocio por tipo: comercio, comida, belleza, ventas por redes, servicios profesionales (abogado, contador, administrador, consultor, arquitecto o ingeniero), salud, oficios, clases y creativos.
- Para servicios: cobros, por cobrar, servicios, concepto del cobro con sugerencias según la profesión, recibo de honorarios (no fiscal) y nueva pestaña Clientes con lo cobrado y lo que debe cada uno.
- Portada: los profesionales en la sección del negocio y en Para quién.

## Mejoras pedidas por los clientes (web)
- Enviar sugerencia desde Más; el administrador las lee y marca en /admin › Sugerencias.
- Gastos con fecha: se puede anotar un gasto de otro día (nunca futuro); en Bs. usa la tasa BCV de ese día si está guardada.
- Ver los montos en $ (BCV), USDT, € (BCV) o Bs.
- Pagos fijos y compromisos alineados: un pago fijo puede anotarse solo o marcarse al pagarlo; Pagos › Compromisos muestra los pagos de cada mes y los pagos únicos; avisa si algo ya existe para no contarlo dos veces; los pagos fijos que faltan se descuentan de lo que te queda.
- Plan de pagos: cuánto pagar al mes y por quincena, orden de pago con lo que toca a cada deuda este mes, cuándo terminas cada una, comparación de estrategias y botón para programar los pagos del mes.
- Quinci, la mascota: aparece en Inicio con un mensaje según tu situación (deudas, pagos por vencer, presupuesto, racha) y reacciona al anotar gastos, abonar o crear el plan.

## Android 1.23: confirmar el correo desde el enlace
- Al abrir el enlace de confirmación, la app cancelaba la consulta antes de enviarla y mostraba «The coroutine scope left the composition»; el correo no quedaba confirmado. Ahora la confirmación corre aparte y termina.
- Las etiquetas de la barra inferior (p. ej. «Movimientos») ya no se cortan.

## Android: diseño Liquid Glass
- Tarjetas, campos, selectores y botones de vidrio translúcido con reflejo en el borde, sobre un fondo con luces verde y menta (mismos colores de la marca).
- La barra inferior es una cápsula de vidrio flotante con la pestaña activa resaltada; el botón + flota encima y el contenido pasa por debajo.

## Panel de administración (solo web)
- Nueva página /admin, solo para administradores y solo en la web: resumen de cuentas y cobros, base de datos (tablas, filas y tamaño), usuarios (plan, confirmar correo, contraseña, activar o desactivar).
- Cobros de Negocio Plus: al confirmar un pago se activa o alarga el Plus, se emite la factura con número correlativo de 8 dígitos y se envía en PDF al correo del cliente. Se puede ver, reenviar o anular.
- Factura según la Prov. SNAT/2011/00071, con el mismo cálculo del ERP: precio en USD llevado a Bs. con la tasa BCV, IVA 16% sobre la base en Bs. e IGTF 3% aparte cuando el pago es en divisas. Logos de Quincena VE y Gigadevs; emisor Gigadevs, RIF 27344270-3.
- Búsqueda de pagos recibidos en Binance (USDT, solo lectura, igual que en el ERP); cada pago se usa en una sola factura.

## Correos: enlace que siempre abre
- Brevo cambia el botón de los correos por un enlace suyo de rastreo que algunos teléfonos o redes bloquean. Ahora el correo muestra también la dirección directa, grande y tocable, con la indicación de usarla si el botón da error.

## Presentación renovada
- Página principal con estilo y animaciones tipo Apple, con los mismos colores: menú translúcido, títulos grandes, secciones que aparecen al bajar, teléfono que se endereza y montos que cuentan.
- Sección completa del negocio: qué incluye, cómo empezar en tres pasos, comparación Gratis vs Plus, preguntas nuevas y botón de cuenta de negocio al final.

## [Sin publicar · SEO, seguridad y páginas de error] — 31 oct 2026

### Corregido
- **Web:** la portada se enviaba con la cabecera `X-Robots-Tag: noindex`, que le pedía a Google NO indexarla.
  Ahora la portada y las páginas legales son indexables; la app, la API y el panel siguen ocultos.

### Agregado
- **Web:** páginas de error propias de Quincena VE (400, 401, 403, 404, 405, 413, 429, 500 y 503). La API sigue
  respondiendo JSON. La 500 muestra un código de referencia y nunca detalles internos.
- **Web:** `/.well-known/security.txt` y `lastmod` en el sitemap; `/admin` fuera de los buscadores.
- **Servidor:** modo mantenimiento con `MAINTENANCE_MODE=1` (503 con Retry-After; `/healthz` sigue respondiendo).
- **Servidor:** las rutas típicas de escáneres (`.env`, `.git`, `wp-admin`, `*.php`…) se cortan con un 404 inmediato.

## [Sin publicar · tasa BCV del sitio oficial, euro y tasa del negocio] — 28 oct 2026

### Corregido
- **La tasa se quedaba en la del día anterior:** dolarapi tarda en recoger la tasa que el BCV publica en la tarde y
  la consulta directa a bcv.org.ve fallaba siempre ("unable to get local issuer certificate": el sitio no envía su
  certificado intermedio). Ahora el servidor baja ese intermedio desde la dirección que trae el propio certificado
  del BCV (viene como paquete PKCS#7, .p7c) y verifica la cadena completa. La web vuelve a pedir la tasa al volver a la pestaña tras 30 minutos.

### Agregado
- **Botón "Actualizar"** discreto junto a la tasa en Inicio: la vuelve a pedir al momento (`/api/rate?force=1`, como
  mucho una consulta a las fuentes por minuto). Inicio muestra también el **euro BCV**.
- **Tasa del negocio:** en Configurar el negocio se elige BCV dólar, BCV euro o USDT. Ventas, precios, totales,
  fiado, Excel y vendedores usan esa tasa; con el euro los montos del negocio se ven en €, y "Me pago a mí" pasa a
  tu quincena su equivalente en dólares.
- **Página de presentación:** botón "Para tu negocio" en el menú y acceso "¿Tienes un negocio? Crea su cuenta" en
  la portada.

## [Sin publicar · Quincena VE para tu negocio] — 27 oct 2026

### Agregado
- **Fase 1 · Negocio (gratis, web y app):** se activa en Más. Pestaña Negocio con ventas en $ y Bs. a la tasa del
  día, por medio de pago (pago móvil, efectivo, Zelle, Binance, punto…); fiado con abonos y recordatorio por WhatsApp;
  gastos del negocio (mercancía, alquiler, servicios, sueldos…) aparte de los personales; cierre de caja con el
  efectivo esperado y contado; "Me pago a mí" (sale del negocio y entra como ingreso personal); ganancia del mes.
  En Android, compartir el SMS de un pago móvil **recibido** abre la venta ya escrita.
- **Fase 2 · Negocio Plus:** catálogo de productos con precio en $ (se convierte a Bs. solo) e inventario que baja
  con cada venta; recibo para WhatsApp que dice "No es factura fiscal"; Excel del mes para el contador (CSV).
  El plan lo activa un administrador desde Más › Administrar Negocio Plus (o `PATCH /api/users/<id>` con
  `{"plan": "plus", "days": 30}`; 0 = sin vencimiento).
- **Fase 3 · Equipo y crecimiento:** hasta 5 vendedores por negocio (Plus) con su propio usuario: solo registran
  ventas y la dueña las ve con su nombre. Tarjeta para contratar a Gigadevs. La compilación deja además el AAB para
  Google Play (`android/GOOGLE_PLAY.md`).
- **Crear la cuenta de un negocio:** pantalla "Crea la cuenta de tu negocio" (web, `/app#negocio`, y app) con el
  nombre del negocio; la cuenta nace con el modo Negocio activo y abre en la pestaña Negocio. La página de
  presentación tiene la sección "Quincena VE para tu negocio" y una pregunta frecuente nueva.
- Servidor: colecciones `business`, `sales`, `bizPayments`, `bizExpenses`, `products`, `cashClosings`; columnas
  `user.plan`, `user.plan_until`, `user.owner_id`; rutas `/api/biz/sellers` y `/api/biz/seller`. Pruebas:
  `tests/negocio_test.py` (38) y `tests/negocio_ui_test.py` (30, en navegador).

### Corregido
- **Web:** un cambio hecho mientras se guardaba otro (por ejemplo, la tasa que llega justo después de crear la
  cuenta) se perdía al recibir la respuesta del servidor. Ahora se conserva y se mezcla en el siguiente guardado.

## [Sin publicar · tasa del dólar más confiable] — 27 oct 2026

### Corregido
- **La tasa BCV podía quedarse vieja sin aviso.** El servidor solo la tomaba de dolarapi y, si fallaba o venía
  atrasada, lo callaba. Ahora consulta también la página del BCV (bcv.org.ve) y usa la de **fecha más reciente** (a
  igual fecha, la del BCV). El paralelo sigue saliendo de dolarapi.
- Cada consulta queda en el registro de Render: qué dio cada fuente, cuál se usó y, si falló, por qué.
- `/api/rate` dice de qué fuente salió la tasa (`source`). Pruebas: `tests/rate_test.py`.
- **Errores de base de datos al despertar el servidor** ("SSL error: decryption failed or bad record mac" y
  "unexpected eof"): PostgreSQL de Render corta las conexiones inactivas y el servidor reusaba una muerta, con lo que
  fallaban peticiones y `/healthz`. Ahora cada conexión se prueba antes de usarse (`pool_pre_ping`) y se renueva cada
  5 minutos.

## [Sin publicar · Más, más simple] — 26 oct 2026

### Cambiado
- **Android — Más pasa de 13 tarjetas a 6:** País y moneda; Avisos (avisos de pago con sus interruptores y el
  recordatorio diario en una sola tarjeta); Registrar desde el banco; Seguridad (huella o rostro, cambiar contraseña,
  cerrar sesión en otros teléfonos); Mi cuenta (usuario y versión, descargar datos, cerrar sesión, eliminar cuenta); Legal.
- **Web — Más pasa de 10 tarjetas a 5:** "Descargar la app" solo aparece en teléfonos Android.

### Quitado
- De Más (app y web): **Mis cuentas**, **Mi presupuesto** y **Mis bolívares guardados**, que ya están en
  Movimientos → Cuentas y en Inicio.
- De la web: **activar entrada con huella o rostro** y **activar avisos de pago** (la sesión web se cierra a los 30
  minutos y la app da mejores avisos). Quien ya los activó los sigue recibiendo.

### Corregido
- `tests/country_ui_test.py` abre `/app` (en `/` está la presentación) y busca el enlace actual "Crea una gratis".

## [Sin publicar · enlaces de los correos abren la app] — 26 oct 2026

### Agregado
- **Android App Links:** los enlaces de los correos (`/app#verify=…` y `/app#reset=…`) abren la app si está instalada
  y la web si no. El servidor publica `/.well-known/assetlinks.json` con la huella de la llave de firma (cambiable con
  `ANDROID_CERT_SHA256`). Pruebas: `tests/app_links_test.py`.
- **Android:** confirmar el correo desde el enlace (aviso "Tu correo quedó confirmado") y pantalla "Elige tu contraseña
  nueva". Antes de cambiarla se suben los cambios pendientes; si la sesión del teléfono era de esa cuenta, se cierra.

## [Sin publicar · diagnóstico de correos] — 26 oct 2026

### Agregado
- **Servidor — `POST /api/mail-test`** (solo administrador): envía un correo de prueba a `{"to": "correo"}` (o al
  correo del administrador) y devuelve la configuración detectada (`BREVO_API_KEY` sí o no, `MAIL_FROM`,
  `APP_PUBLIC_ORIGIN`, qué falta) y la respuesta exacta de Brevo. Pruebas: `tests/mail_test.py`.

### Cambiado
- **Correos con la marca de Quincena VE:** una sola plantilla (`email_layout`) para la confirmación de correo, la
  recuperación de contraseña, los avisos de seguridad y el correo de prueba: franja con la bandera, logo, nombre en
  Bricolage Grotesque, botón verde #0B6B4D y pie con la dirección del sitio.

### Corregido
- **Correos que no salían sin dejar rastro:** ahora el registro de Render dice por qué no se envió un correo de
  verificación o de recuperación (falta `BREVO_API_KEY`, `MAIL_FROM` o `APP_PUBLIC_ORIGIN`) y, si Brevo lo rechaza,
  su código y mensaje (p. ej. «unauthorized: Key not found» o un remitente no verificado). La clave nunca se registra.

## [Sin publicar · actualización obligatoria] — 25 oct 2026

### Agregado
- **Servidor — Versión mínima de la app:** si una petición de la app Android llega con una versión menor que la mínima,
  responde 426 con el aviso "Hay una versión nueva… Actualízala para seguir usándola" y no entrega datos. La app manda
  su versión en `X-App-Version`; las versiones 1.14 y anteriores no la mandan y cuentan como viejas. Siguen abiertas
  `/api/android-app`, `/api/status`, `/api/countries`, `/api/rate` y la descarga del APK. La web no se ve afectada.
- **Variable `ANDROID_MIN_VERSION` en Render:** `latest` (por defecto) exige siempre la última versión publicada; un
  número fija esa versión mínima; `0` u `off` lo apaga. Pruebas: `tests/app_update_test.py`.
- **Android:** pantalla "Actualiza para continuar" que no se puede saltar, con el botón Actualizar. Aparece si
  `/api/android-app` dice que la versión instalada es menor que la mínima (se revisa al abrir, antes de entrar) o si
  cualquier petición recibe 426.

## [Sin publicar · Quincena VE 🇻🇪] — 25 oct 2026

### Cambiado
- **Nombre: Quincena VE.** En el ícono del teléfono, el menú Compartir, la pestaña del navegador, el manifest, los correos
  y las notificaciones va "Quincena VE"; en las pantallas de entrada y en la presentación, "Quincena VE 🇻🇪". Google
  Play no admite emojis en el título de la ficha, por eso la bandera no va en el nombre corto.
- **Ícono nuevo:** la Q con los colores de la bandera de Venezuela en tercios iguales, las 8 estrellas en arco y la cola
  en el verde de la marca, sobre fondo blanco. Lo genera `tools/icons.py` para la web y para Android (incluidos los
  vectores del ícono adaptativo y la capa monocromática).
- Los identificadores internos no cambian (`app.quincena`, `QuincenaTheme`, rutas y nombres de archivo), así que la
  app se actualiza encima sin desinstalar.

## [Sin publicar · página de presentación y Gigadevs] — 25 oct 2026

### Agregado
- **Web — Página de presentación en `/`** (`static/landing.html`): qué es Quincena, cómo funciona, funciones, para quién es,
  países, privacidad, descarga y preguntas frecuentes. HTML estático para que Google la lea completa, con datos
  estructurados (SoftwareApplication, Organization de Gigadevs y FAQPage), Open Graph y enlaces a Gigadevs.
- **Servidor:** `/` muestra la presentación a quien no tiene sesión y la app a quien sí (las PWA instaladas siguen igual);
  la app también vive en `/app`. Nuevas rutas `/robots.txt` y `/sitemap.xml`. Pruebas: `tests/landing_test.py`.
- **Web:** `/app#registro` abre directamente el formulario de crear cuenta.

### Cambiado
- La PWA abre `/app` (`start_url` y acceso directo), el service worker guarda `/app` para usar sin conexión y las
  notificaciones abren `/app`. Los correos de verificación y recuperación enlazan a `/app#…`; los enlaces viejos
  (`/#reset=…`, `/#verify=…`, `/?nuevo=gasto`) se reenvían solos a la app.
- La app (`index.html`) lleva `noindex`: en Google aparece la presentación, no la pantalla de acceso.

## [Sin publicar · ícono nuevo y acceso renovado] — 25 oct 2026

### Agregado
- **Ícono nuevo (web y Android):** la "Q" de Quincena en blanco con la cola menta, sobre el degradado verde de la marca.
  Sale de un solo diseño: `python3 tools/icons.py` genera `favicon.ico` (16/32/48), `icon.svg`, los PNG de la PWA,
  un ícono *maskable* propio, el de iPhone (`apple-touch-icon.png`) y los PNG de respaldo de Android.
- **Android:** ícono adaptativo vectorial (`drawable/ic_launcher_foreground.xml` y `ic_launcher_background.xml`) con capa
  monocromática para los íconos temáticos de Android 13+. Se quitaron los PNG de primer plano viejos.
- **Servidor:** ruta `/favicon.ico` (los navegadores la piden siempre en la raíz).

### Cambiado
- **Web — Pantallas de acceso:** en computadora, panel de marca a la izquierda y formulario a la derecha; en el teléfono,
  formulario centrado con el ícono. Botón para mostrar u ocultar la contraseña, aviso de Bloq Mayús, "¿Olvidaste tu
  contraseña?" junto al campo, separador antes de "Entrar con huella o rostro" y menos botones apilados.
- El aviso de descargar la app usa el ícono nuevo (antes iba incrustado en base64).

### Corregido
- **Web:** al equivocarse de contraseña salía "Tu sesión terminó. Entra de nuevo."; ahora se muestra el mensaje real
  ("Usuario o contraseña incorrectos.").
- **Web:** en modo oscuro, el botón principal de las pantallas de acceso tenía texto blanco sobre verde claro (poco
  contraste); ahora el texto es oscuro.

## [Sin publicar · origen de los registros] — 25 oct 2026

### Agregado
- **Servidor y web — Origen de cada cuenta nueva:** si la persona llega por un enlace con `utm_source` / `utm_campaign`
  (por ejemplo `/?utm_source=facebook&utm_campaign=post1`), la web lo guarda en el navegador, limpia la URL y lo envía al
  registrarse. Se guarda en `user.signup_source` (columna nueva, se crea sola al arrancar). Con solo el `fbclid` de Meta
  queda `meta`; desde la app Android sin campaña, `android`; sin nada, directo.
- **Servidor — `/api/signups`** (solo administrador): cuentas nuevas por origen, en total y en los últimos 7 días.
  `/api/users` también muestra el origen de cada cuenta. Pruebas: `tests/signup_source_test.py`.

### Cambiado
- `android/NOVEDADES.txt` vacío: el próximo APK ya no repite el aviso de firma nueva (sin texto se muestra
  "Mejoras de seguridad y correcciones.").

## [Sin publicar · vista previa en redes] — 25 oct 2026

### Agregado
- **Web — Vista previa al compartir:** etiquetas Open Graph (título, descripción e imagen) para que el enlace se vea
  con tarjeta en Facebook, Instagram y WhatsApp. La imagen es `static/og-image.png` (1200×630) y se regenera desde
  `tools/og-image.html`. Las etiquetas usan la dirección de Render: si cambia el dominio, hay que actualizarlas.

## [Sin publicar · países y cobro semanal] — 25 oct 2026

### Agregado
- **Web y Android — País y moneda:** al crear la cuenta se elige el país (Venezuela, Colombia, Chile, Perú, México o
  Argentina; se sugiere uno según la zona horaria). La app usa la moneda local de ese país (Bs., pesos, soles) y su tipo
  de cambio oficial, que se actualiza solo. Se puede cambiar en Más › País y moneda.
- **Web y Android — Ingreso semanal:** nueva frecuencia "Cada semana" con el día en que cobras. El ingreso de cada
  quincena cuenta los cobros que realmente caen en esas fechas y el calendario marca cada uno.
- **Servidor:** `/api/countries` y `/api/rate?country=XX` (Venezuela y Argentina por dolarapi, el resto por
  open.er-api.com). Pruebas nuevas: `tests/country_test.py` y `tests/country_ui_test.py`.

### Corregido
- **Web:** el botón de moneda del encabezado se partía en dos líneas con "COP $"; ahora muestra el código corto (COP, CLP, S/, MXN, ARS). Los números largos en los mosaicos de Inicio ya no se cortan con "…" y las filas de listas se acomodan mejor en pantallas angostas.

### Cambiado
- Los textos de Venezuela (Bs., bolívares, BCV) se adaptan al país elegido; en pesos colombianos y chilenos los montos
  salen sin decimales. Las cuentas existentes siguen en Venezuela y no cambia nada para ellas.
- Las funciones de tasa paralela (USDT) y la brecha solo aparecen en Venezuela.

### Pendiente
- Los términos y la privacidad siguen citando las leyes de Venezuela: conviene una revisión legal antes de abrir otros países.

## [Sin publicar · seguridad del servidor] — 25 oct 2026

Solo servidor y web. **No hay cambios en la app Android** (no hace falta compilar otro APK).

### Agregado
- **Recuperación de clave por correo:** enlace de un solo uso que vence, el mismo mensaje exista o no el correo
  (no se puede averiguar quién tiene cuenta) y máximo 3 correos por hora por dirección.
- **Avisos de seguridad por correo** (solo a correos verificados): cambio o restablecimiento de clave, cierre de
  sesiones en otros dispositivos y inicio de sesión desde un teléfono nuevo.
- **Envío por Brevo (HTTPS):** el plan gratis de Render bloquea SMTP, así que los correos salen por la API de Brevo.
  Se configura con `BREVO_API_KEY` y `MAIL_FROM`.
- **Límite general de uso** de la API y límite de intentos de acceso **por usuario**, además del límite por IP.
- Pruebas automáticas: `tests/security_test.py` (inyección SQL, sesiones, IDOR, CSRF, límites, correos) y
  `tests/xss_test.py` (navegador real).

### Cambiado
- Los enlaces de verificación y de recuperación viajan en el fragmento `#` de la URL, que no llega a los registros
  del servidor ni a otros sitios por la cabecera Referer.
- Al cambiar o restablecer la clave se cierran las demás sesiones web (una vez tras este despliegue, todos volverán a
  iniciar sesión) y, al restablecerla un administrador, también las de la app.
- Comparación de claves con tiempo parecido exista o no el usuario; claves de más de 1000 caracteres se rechazan.
- Los errores 413, 429, 404, 405 y 500 devuelven mensajes genéricos en JSON; nunca detalles internos.
- La dirección IP del registro de auditoría sale de la conexión real validada.
- `.gitignore` ahora excluye `.env`, bases de datos, `instance/`, llaves y `android/app/release/`.

## [1.6.0] — 25 oct 2026 · app 1.9 (versionCode 105, compilada por GitHub)

### Agregado
- **Web y Android — Mis cuentas:** banco, efectivo, digital (Zelle…) y cripto (USDT). Cada gasto, ingreso o préstamo
  puede salir o entrar de una cuenta; el saldo se calcula solo. Se ve en Movimientos › Cuentas, en Inicio ("Tu dinero")
  y en Más.
- **Web y Android — Mover o cambiar dinero** entre cuentas. Si cambias $ por Bs., queda guardada **"Tu cambio"**
  (tasa de los últimos 45 días) y aparece como opción de tasa al registrar un gasto en bolívares.
- **Web y Android:** los **bolívares guardados** se calculan solos con tus cuentas en Bs. (si no tienes, sigue el monto manual).
- **Web y Android — Ingresos recibidos:** botón **"Me pagaron"** en cada ingreso fijo e **"Ingreso extra"**.
  Los extras suman al "Te quedan" del periodo.
- **Web y Android — Movimientos:** gastos **agrupados por día** (Hoy, Ayer…) con total diario, filtros por categoría
  y total del filtro.
- **Web y Android — Presupuesto por quincena:** en Inicio el presupuesto se muestra por quincena (la mitad del mensual)
  y el título dice "Tu quincena". Botón **"Usar los sugeridos"** con el promedio de los últimos 3 meses.
- **Web y Android — Calendario de pagos** en Pagos (pagos, compromisos, gastos fijos y cobros por día).
- **Web y Android — Pagos repetidos:** al programar un pago se puede repetir cada mes o cada quincena (hasta 36 veces).
- **Web y Android — Me deben:** préstamos a familiares o amigos, con "Me pagó" (abonos parciales) y fecha límite.
  Tarjeta "Te deben" en Inicio.
- **Web y Android — Fecha estimada para salir de cada deuda** ("terminas aprox. en dic 2027").
- **Seguridad:** cambiar contraseña desde la app y la web; "Cerrar sesión en mis otros teléfonos";
  el servidor cierra la sesión web tras 30 minutos sin uso; las contraseñas comunes o con tu usuario se rechazan.
- **Legal:** páginas `/terminos`, `/privacidad` y `/cookies` (con derechos según los arts. 28 y 60 de la Constitución),
  aceptación de los términos al entrar (se registra en el historial de seguridad), aviso de **cookies** en la web
  y **Copyright** en Más, entrada y menú lateral.

### Cambiado
- **Servidor:** la cookie de sesión ahora se llama `__Host-quincena` (SameSite=Strict). **Todos tendrán que
  entrar de nuevo una vez.**
- **Servidor:** encabezado `Cross-Origin-Resource-Policy`; límite de 120 guardados por minuto; los datos nuevos
  (cuentas, movimientos entre cuentas, ingresos, préstamos) se validan y se mezclan sin perder cambios.
- **Web:** caché del service worker v14.
- **Pruebas:** la prueba de humo usa contraseñas que cumplen la nueva regla.

### Pendiente por tu parte
- Definir la variable `CONTACT_EMAIL` en Render (correo que aparece en la política de privacidad).
- Revisar los textos legales con un abogado antes de publicar en tiendas.

---

## [Sin publicar]

### Agregado
- **Android — Avisos ampliados** (sin Firebase ni claves: los calcula el teléfono): además de los pagos y compromisos,
  ahora avisa de **cobros de "Me deben"** (el día del cobro, el día anterior y a los 3 días de atraso),
  **presupuesto al 80 % y al 100 %** de cada categoría en la quincena y **nueva versión de la app**.
  En Más › Seguridad y avisos se prende o apaga cada tipo por separado.
- **Android:** la revisión pasa de una vez al día a **cada 6 horas**, y nunca avisa entre las 10 pm y las 7 am
  (lo pendiente sale en la primera revisión de la mañana). Quien ya tenía los avisos activos pasa al ritmo nuevo al abrir la app.
- **GitHub Actions:** paso opcional que **redespliega Render** al publicar el APK (secreto `RENDER_DEPLOY_HOOK`).
  Sin ese secreto no hace nada y avisa cómo lanzarlo a mano.

### Cambiado
- **Aviso de actualización:** el texto sale de `android/NOVEDADES.txt` (lenguaje sencillo).
  Si está vacío o es un mensaje técnico de Git (Merge, Revert…), dice "Mejoras de seguridad y correcciones.".
  El servidor también filtra esos mensajes, así que corrige el aviso de la 1.8 sin recompilar.

### Corregido
- **Servidor y web:** el APK se descarga con la versión en el nombre (`Quincena-1.7.apk`), para no abrir
  por error una descarga vieja (`Quincena.apk`) y quedar con la versión anterior.

---

## [1.5.0] — 23 oct 2026 · versionCode 7 (o el automático de GitHub)

### Agregado
- **Web y Android:** **gastos rápidos**: en Inicio aparecen los gastos que repetiste 2 o más veces
  en los últimos 60 días (máx. 6). Un toque los registra de nuevo, con "Deshacer".
- **Web y Android:** el formulario de gasto nuevo **recuerda** la última moneda, categoría y tasa.
- **Android:** **atajo en el ícono**: mantener presionado Quincena › "Registrar gasto".
- **Web (instalada como app):** el mismo atajo, desde el manifest (`/?nuevo=gasto`).
- **Android:** **recordatorio diario** a la hora que elijas (Más): solo avisa si ese día no registraste nada.
  Los domingos llega además el **resumen de la semana**. Tocar el aviso abre "Nuevo gasto".
- **Web y Android:** tarjeta **"Tu semana"** en Inicio los domingos y lunes.
- **Web:** pantalla de carga con aviso "Despertando el servidor…" si tarda más de 4 segundos.
- **Android:** en la pantalla de entrada, aviso de que la primera conexión puede tardar hasta un minuto.


### Corregido (flujo de GitHub, publicado junto con 1.5.0)
- Compilación automática (GitHub Actions): se quitó el paso `setup-android`, que fallaba al
  instalar el paquete `tools` (ya no existe). Ahora usa el SDK que trae el servidor de GitHub.
- El flujo revisa al inicio que estén la carpeta `android` y los 4 secretos, y avisa en español qué falta.
- Se borra `android/local.properties` antes de compilar en GitHub (apunta a la Mac).

---

## [1.4.0] — 23 oct 2026 · versionCode 6

### Quitado
- **Android:** lector de notificaciones del banco (permiso `BIND_NOTIFICATION_LISTENER_SERVICE`).
  Era la causa probable de que Android y Google Play Protect bloquearan la instalación.

### Agregado
- **Android:** registrar un gasto desde un mensaje del banco con **Compartir › Quincena**
  o con **Más › Pegar mensaje copiado**. Sin permisos especiales.
- **Android:** aviso **"Nueva versión disponible"** en Inicio, con botón Actualizar.
  La app consulta `/api/android-app` cada vez que se abre.
- **Android:** la versión instalada se ve en Más (bajo el nombre de usuario).
- **Servidor:** `downloads/version.json` define la versión publicada; `/api/android-app`
  devuelve `versionCode` y notas.
- **GitHub Actions:** `.github/workflows/android-apk.yml` compila y firma el APK en cada
  cambio dentro de `android/` y lo publica en `downloads/`. Render despliega solo.
- **Android:** firma y número de versión por variables de entorno (para GitHub Actions).
- **Web:** diseño propio para computadora (más de 900 px): barra lateral con
  "+ Registrar gasto", Inicio en columnas, Más en dos columnas, ventanas centradas.
  En el teléfono la web se ve igual que antes.

---

## [1.3.0] — 23 oct 2026 · versionCode 5

### Cambiado
- **Web y Android:** Inicio rediseñado para no tener que bajar tanto (de ~4.000 a ~1.000 px):
  - Tarjeta principal compacta con el selector de periodo (‹ › y 1.ª / 2.ª / Mes) adentro.
  - Seis mosaicos: Próximo pago, Sueldo comprometido, Deudas, Tus tasas, Tus bolívares y Calculadora.
    Cada uno abre su detalle.
  - "Tu mes": en qué se va el dinero + las 3 categorías de presupuesto más usadas, con "Ver todo".

---

## [1.2.0] — 23 oct 2026 · versionCode 4

### Agregado
- **Web y Android:** **Tu sueldo comprometido**: qué parte de las próximas 4 quincenas ya está
  comprometida (cuotas, pagos programados, compromisos y gastos fijos).
- **Web y Android:** **Compra en cuotas** (Cashea y otras): crea la deuda, las cuotas con fecha
  (cada 14 días, quincena o mes) y la inicial como gasto.
- **Web y Android:** **Calculadora de caja**: compara el costo real de pagar en $ o en Bs.
- **Web y Android:** **Poder de compra**: cuánto valor pierden tus bolívares guardados.
- **Servidor:** campos `bsSavings` y `rateHistory` (historial diario de tasas, máx. 120 días).
- **Android:** registro automático leyendo notificaciones del banco *(quitado en 1.4.0)*.

---

## [1.1.0] — 23 oct 2026 · versionCode 3

### Agregado
- **Web y Android:** **Las tres tasas**: al registrar un gasto en bolívares se marca a qué tasa
  se consiguieron (BCV, USDT u otra) y se calcula su costo real en dólares.
- **Web y Android:** tarjeta "Tus tasas este mes" con la brecha BCV/USDT y cuánto ahorraste o perdiste.
- **Servidor:** dólar USDT (paralelo) desde dolarapi; campos `rateType`, `rateUsed`,
  `bcvRate`, `marketRate` en los gastos.

---

## [1.0.0] — 22–23 oct 2026 · versionCode 2

### Agregado
- **Android:** migración completa de la web a app nativa (Kotlin + Compose): Inicio, Movimientos,
  Deudas, Pagos y Más; los formularios de la web; simulador de deudas; gastos fijos;
  compromisos; sincronización sin conexión; entrada con huella o rostro; avisos de pago locales.
- **Android:** diseño de la web (colores, letras Bricolage Grotesque y Figtree, íconos).
- **Android:** ícono adaptativo en todos los tamaños.
- **Web:** modal "Quincena para Android" para descargar el APK: se abre al iniciar sesión,
  aviso en Inicio y tarjeta en Más. Ruta `/descargar/android`.
- **Web:** logo incluido en la página (no depende de `static/icons`).

### Corregido
- **Android:** error de compilación "Conflicting overloads: parseMoney" (función duplicada en
  `MainActivity.kt`).
- **Android:** el modo de pruebas vuelve a usar el servidor local de la Mac (`10.0.2.2:5001`).
- **Android:** los errores de inicio de sesión dicen la causa exacta.
