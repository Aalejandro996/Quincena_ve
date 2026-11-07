# Historial de versiones — Quincena

Cada cambio se registra aquí. La versión de Android sube en `android/app/build.gradle.kts`
(o sola, cuando la compila GitHub Actions: `versionCode` = 100 + número de ejecución).
La web no tiene número propio: cada entrada indica qué cambió en la web y en la app.

Formato: **Agregado** (nuevo), **Cambiado** (mejoras), **Corregido** (errores), **Quitado**.

---

## [Sin publicar]

### Corregido
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


## Suscripción (Negocio Plus) con Ávila Pay

La suscripción de Quincena VE la administra la Consola Maestra de Gigadevs (Contax → Quincena VE): allí se crean los planes y
sus precios. En la app, **Más → Suscripción · Negocio Plus** muestra esos planes; la persona elige plan y forma de pago (bolívares a
tasa BCV, o Zelle/tarjeta si están habilitados) y paga en la página segura de Ávila Pay. Al volver, o con el aviso de Ávila Pay, la
consola confirma el pago, crea o renueva la suscripción, emite la factura y la envía por correo; la app recibe la suscripción
firmada y activa Negocio Plus hasta su vencimiento. El administrador puede seguir activando Plus a mano.

Variables: `GIGADEVS_SYNC_SECRET` (igual a `SYNC_SECRET_CONTROL_FINANCIERO` del servidor de licencias) y `GIGADEVS_SYNC_URL`
(por defecto `https://erp-licencias.onrender.com`). Sin el secreto, la tarjeta ofrece pedir el Plus por WhatsApp como antes.
