# Gitly — notas de desarrollo

> Notas técnicas internas (en español). La presentación del proyecto está en el [README](../README.md).

Red social sobre GitHub para descubrir repos: feed con algoritmo de preferencias, actividad de la
gente que sigues, compartir repos e instalar APKs directamente desde las releases.

GitHub es el backend: los "me gusta" son estrellas reales, lo social sale de quién sigues en
GitHub y el algoritmo corre en el propio móvil. No hay servidor propio (todavía).

## Estructura

| Módulo | Qué hay |
|---|---|
| `core/` (Kotlin JVM puro) | `GitHub.kt` cliente REST · `Recommender.kt` rasgos, perfil y ranking · `FeedEngine.kt` generación de candidatos y actividad |
| `app/` (Android, Compose) | `AppViewModel` estado + pila de navegación · `Store` (`filesDir/state.json`: ajustes + cuentas, cada una con su historial) · `ApkInstaller` · `GitlyApp` (Coil con GIF/SVG) · `ui/` pantallas · textos en `res/values` (es) y `values-en` |

Pestañas (Material You, color dinámico):
- **Para ti**: posts estilo Instagram. Carrusel con las imágenes del README (`ReadmeParser` filtra
  insignias, QR, banners de donación…; si no hay, la tarjeta social del repo), doble toque = estrella,
  acciones estrella / enviar / repostear y el texto del README debajo,
  plegado a 3 líneas con "Mostrar más". Menú ⋮: no me interesa, detalles, abrir en GitHub.
- **Explorar**: buscador, **Pequeñas joyas** (`GemScorer`: 5–600 estrellas, afines a tu perfil,
  activas, bien documentadas y que ganan estrellas rápido para su edad; máx. 2 por dueño) y
  "Descubrir" con chips (tendencias, apps Android, tus topics) en rejilla de 2 columnas.
- **Amigos**: chats con amigos mutuos y actividad de a quién sigues.
- **Perfil**: estilo Instagram (avatar, repos/seguidores/siguiendo, bio, empresa, ubicación, web),
  pestañas Repos / Estrellas / Gustos. Sirve también para perfiles ajenos (botón Seguir).
  Seguidores/Siguiendo abren su lista. Engranaje → **Ajustes**: cuentas (varias, cambiar/añadir),
  tema (sistema/claro/oscuro), color dinámico, idioma (sistema/es/en), privacidad (quién me escribe,
  quién ve mis reposts, confirmaciones de lectura, compartir actividad — guardados para cuando haya
  DMs/reposts), algoritmo (olvidar descartes, reiniciar historial) y cerrar sesión.
Detalle de repo: README renderizado, estrella, compartir y tarjeta "Instalar app" si la última
release trae `.apk` (ordenados por ABI del dispositivo). Los enlaces `https://github.com/owner/repo`
pueden abrirse con Gitly.

## Algoritmo

1. **Rasgos de un repo** (`Features.of`): topics (`t:`), lenguaje (`l:`), palabras de descripción,
   nombre y topics (`w:`), normalizado L2.
2. **Perfil** (`Profile.build`): suma de rasgos de tus señales con decaimiento (vida media 240 días).
   Señales: estrella +1, abrir detalle +0,3, "no me interesa" −1,5 (vector negativo aparte que resta).
3. **Candidatos** (`FeedEngine`, ~40 llamadas por refresco, en paralelo):
   - SOCIAL: `received_events` → lo que marcan/crean/forkean/publican quienes sigues.
   - TOPIC: búsqueda por tus 4 topics más fuertes (página aleatoria para variar).
   - TRENDING: creados en los últimos 30 días con estrellas, general + tus 2 lenguajes.
   - COSTAR: colaborativo ligero; stargazers recientes de 3 repos que marcaste → qué más marcan.
4. **Ranking** (`Ranker.rank`): `0,5·similitud + 0,2·social + 0,12·co-estrellas + 0,1·popularidad
   + 0,08·frescura (+ tendencia)` − penalización por impresiones repetidas, archivado o fork;
   después MMR para diversidad (penaliza parecido con lo ya elegido y dueño repetido).
   Cada tarjeta explica su porqué ("Porque te interesa wayland, qml", "Actividad de @x"…).

Arranque en frío (sin estrellas): el feed se basa en tendencias y popularidad.

## Compilar y probar

```sh
./gradlew :app:assembleRelease                     # app/build/outputs/apk/release/app-release.apk (R8, la que hay que usar)
./gradlew :app:assembleDebug                       # debug: mucho más lenta (Compose sin optimizar)
./gradlew :core:test                               # tests del algoritmo
GH_TOKEN=$(gh auth token) ./gradlew :core:test --rerun-tasks   # + prueba en vivo contra la API
```

### Inicio de sesión

1. **Iniciar sesión con GitHub** (principal): abre la página de login de GitHub dentro de la app
   (Custom Tab: usuario, contraseña, 2FA) y vuelve por `gitly://oauth/callback`. Flujo web de OAuth
   con PKCE + `state` (`core/DeviceAuth.kt` → `WebAuth`). GitHub no permite que una app de terceros
   reciba la contraseña, así que no hay campos propios de usuario/contraseña.
2. **Entrar con un código**: device flow (código copiado, se pega en github.com/login/device).
3. **Token personal** (classic, `public_repo,user,gist`).

Permisos pedidos: `public_repo` (estrellas), `user` (perfil, seguir), `gist` (clave de mensajería).

Registro de la OAuth App (una vez): https://github.com/settings/applications/new
- Authorization callback URL: `gitly://oauth/callback`
- Marcar **Enable Device Flow** (para la opción del código).
- En `local.properties`: `gitly.githubClientId=…` y `gitly.githubClientSecret=…`.
  El secreto viaja dentro del APK (habitual en apps móviles con OAuth Apps de GitHub; PKCE evita
  que un código interceptado sirva). Sin client_id, la pantalla muestra directamente el token.

### Rendimiento

- El feed calculado y las vistas previas de README se guardan en `cacheDir/feed/`: la app abre en
  ~0,6 s con la cinta pintada. Si la caché tiene más de 15 min, se recalcula en segundo plano y
  aparece el botón "Nuevas recomendaciones" en vez de mover la cinta.
- Las posiciones de scroll (Para ti, Explorar, perfil propio) viven en el ViewModel.
- Se precargan las vistas previas de los 6 posts siguientes al visible.

## Mensajes directos (pestaña Amigos)

- **Transporte**: Nostr con NIP-17 (rumor kind 14 sellado y envuelto NIP-59, cifrado NIP-44) por
  relays públicos (`chat/Messenger.kt`, lista `RELAYS`). Ni GitHub ni los relays ven el contenido.
- **Identidad**: cada cuenta genera una clave Nostr (guardada cifrada con el Android Keystore,
  `chat/SecretBox.kt`) y publica su parte pública en un gist suyo `gitly.json`
  (`{"app":"gitly","v":1,"login":…,"pubkey":…}`). Al recibir, el mensaje trae la etiqueta
  `["gitly", login]` y se comprueba que la clave que lo firma es la del gist de ese login.
- **Regla**: solo entre amigos mutuos (los dos os seguís en GitHub). Se comprueba al enviar y al
  recibir; lo de no mutuos se descarta. Privacidad → "Nadie" desactiva la recepción.
- **Tipos**: texto y tarjeta de repo (`["gitly-repo", full_name, descripción, estrellas, lenguaje]`).
  El botón ✈ de cada post abre "Enviar a…" con tus amigos mutuos.
- **Sincronización**: sondeo cada 15 s con la pestaña Amigos abierta y cada 8 s dentro de un chat.
  Notificaciones: ver abajo.
- **Otro móvil**: Ajustes → Mensajes → exportar/importar la clave (nsec). El historial no viaja.
- **Permisos**: el gist necesita el scope `gist` (incluido en "Continuar con GitHub" y en el enlace
  del token). Sin él, "Activar mensajes" avisa de que hay que volver a entrar.
- **Prueba**: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` — ida y vuelta
  real por los relays con identidades de prueba (GitHub simulado) y descarte de no mutuos.

## Reposts

- Botón ⟲ de cada post. A quién llega lo decide Ajustes → Privacidad → "Quién ve mis reposts":
  **Todo el mundo** = evento Nostr público firmado (kind 31616, reemplazable, `d` = owner/repo);
  **Amigos mutuos** = rumor kind 31616 en sobre cifrado a cada amigo mutuo con Gitly;
  **Solo yo** = solo local. Deshacer publica la misma clave con la etiqueta `deleted`.
- Al recibir: públicos de amigos mutuos (verificando que el login del evento es el de su gist) y
  privados por la misma vía que los mensajes. Se guardan en `ChatData.reposts`.
- En "Para ti" (`AppViewModel.displayFeed`) los reposts de amigos de las últimas 2 semanas se
  intercalan (posiciones 1, 4, 7…) con la marca "⟲ @x lo ha reposteado". Perfil propio → pestaña Reposts.
- **Perfiles**: la pestaña Reposts aparece en cualquier perfil. Muestra los públicos de esa persona
  (si eligió "Todo el mundo", cualquiera los ve: `Messenger.fetchUserReposts`) más los que te envió
  en privado por ser amigos mutuos. Si no usa Gitly o no comparte nada contigo, lo dice.
- Necesitan los mensajes activados (la clave Nostr). Probado en `MessengerTest` (privado, público,
  deshacer y "solo yo").

## Notificaciones

- **Sondeo de respaldo**: `push/SyncWorker` (WorkManager, cada 15 min) sincroniza todas las cuentas con
  mensajes activados y avisa de los mensajes nuevos si la app no está en primer plano.
- **Timbre al instante (UnifiedPush)**: si hay un distribuidor instalado (ntfy u otro), Gitly obtiene una
  URL y se la cuenta a cada amigo mutuo en un rumor cifrado (kind 20716, contenido = URL, "" = retirada;
  `Messenger.announcePush`). Al enviar un mensaje se hace un POST vacío a la URL del otro (`wake`, aparte
  y con 4 s de tope: si falla, el envío sigue siendo correcto). El mensaje nunca pasa por ntfy: solo el aviso,
  y la URL solo la conocen los amigos mutuos.
- `push/Notifications`: una notificación por conversación (MessagingStyle con el avatar de GitHub), con
  **Responder** (RemoteInput → `ReplyReceiver`, envía sin abrir la app) y **Marcar como leído**. Tocarla abre
  el chat (cambiando de cuenta si hace falta). Leer el chat en la app la retira.
- Un solo `Messenger` por cuenta en todo el proceso (`GitlyApp.messenger`): UI, worker y respuesta rápida
  comparten almacén; `ChatStore.changes` refresca la UI cuando escribe otro.
- Ajustes → Notificaciones: activar/desactivar, estado del permiso y del distribuidor.
- R8: `-keep class * extends androidx.room.RoomDatabase { <init>(); }` es obligatorio (WorkManager peta al
  arrancar sin él).
- Pruebas: `pushUrlTravelsEncryptedAndDoorbellRings` (anuncio cifrado + POST real a ntfy.sh) y
  `NotificationsTest` (`-e hold 15000` deja la notificación a la vista para capturarla).

## Bloquear, silenciar y «Visto»

- Menú ⋮ en el chat y en perfiles ajenos (`ui/PeerActions.kt`): silenciar (1 h / 8 h / 1 semana / siempre),
  ocultar sus reposts, bloquear (opcionalmente también en GitHub con `PUT /user/blocks/{u}`), denunciar
  (abre el formulario de GitHub). Lista de bloqueados en Ajustes → Privacidad.
- Bloquear es local y silencioso: se descartan sus mensajes, reposts, timbre y confirmaciones; no se le puede
  escribir y se le retira el timbre. Lo recibido durante el bloqueo no reaparece al desbloquear.
- Silenciar: los mensajes llegan, pero sin notificación ni contador.
- Confirmaciones de lectura: rumor cifrado kind 20717 con `["read", ts]` al leer (una vez por mensaje nuevo,
  sin timbre). Recíprocas como en WhatsApp: si las desactivas no envías ni ves «Visto».

## Imágenes

- `ReadmeParser.direct`: github.com/o/r/raw|blob/… → raw.githubusercontent.com (sin redirección por imagen).
- Coil: hasta 16 peticiones por host (OkHttp trae 5), disco de 512 MB, ignora `max-age=300` de GitHub.
- Precarga de la portada de los 6 posts siguientes al tamaño exacto de pantalla; shimmer mientras carga.
- Redes con pérdidas: imágenes por HTTP/1.1 (con HTTP/2 una conexión muda atasca todas), conexión 5 s /
  lectura 10 s y `RetryInterceptor` (3 intentos en GET, también 429/5xx); la API usa el mismo reintento.
  Un fallo de red ya no quita la imagen del carrusel (solo 4xx o imagen inválida).
- Avatares (`ui/avatarUrl`): directos de avatars.githubusercontent.com al tamaño pintado (`?s=64/128/256`),
  ~3 KB en vez de ~30 KB y sin la redirección de github.com/x.png.

## Estética

Material You (color dinámico), icono adaptativo propio con capa monocroma (iconos temáticos),
pantalla de arranque (`core-splashscreen`), transiciones deslizantes entre pantallas y fundido entre
pestañas, acceso a chats con contador en "Para ti", rebote + vibración al dar estrella, destellos de
carga (`Modifier.shimmer()`), botón "Mensaje" en perfiles.

## Pendiente / ideas

- Comentarios en un post o "responder a un repost" (abrir chat con el repo citado).
- Copia de seguridad cifrada del historial de chats.
- Detectar si un APK ya está instalado y ofrecer "Actualizar"; verificación de firma.
- Señales extra para el algoritmo: tiempo en el detalle, instalaciones, compartidos.
- Token en `EncryptedFile`/Keystore en vez de JSON en claro (privado de la app).
- La tarjeta social (opengraph.githubassets.com) da 429 en ráfagas: `OgImage` reintenta con otro id numérico.
