# DisableFlagSecure (LSPosed / Vector)

> **Módulo minimalista para LSPosed que desactiva `FLAG_SECURE` a nivel
> del sistema, permitiendo que capturas de pantalla, grabación de pantalla
> y MediaProjection (AnyDesk, scrcpy, TeamViewer, etc.) vean ventanas que
> de otro modo se renderizarían en negro.**

Probado en **Android 16 (SDK 36) / LineageOS 23** con
[Vector LSPosed](https://github.com/LSPosed/LSPosed) y Magisk 30600, pero
los hooks apuntan a APIs estables de AOSP y deberían funcionar en
Android 11+.

---

## ¿Por qué otro módulo DisableFlagSecure?

Muchos módulos existentes están atados a una versión concreta de Android,
dependen de repositorios Maven que ya no existen
(`api.xposed.info` devuelve 404) o requieren bajar APIs de terceros.

Este módulo es deliberadamente:

- **Autocontenido.** Incluye sus propios *stubs* locales de la API de
  Xposed bajo `xposed-stubs/` (solo compile-only; LSPosed inyecta las
  clases reales en tiempo de ejecución). No hace falta ningún repositorio
  externo.
- **Pequeño.** ~150 líneas de Java en total. El APK resultante pesa ~10 KB.
- **Redundante a propósito.** Instala **tres hooks independientes** en
  distintos puntos del pipeline de enforcement de `FLAG_SECURE`, para
  que si un OEM o una versión de Android mueve una comprobación, alguno
  de los otros hooks la siga interceptando.
- **De alcance muy acotado.** Solo se carga en `system_server` (scope
  `android`), que es donde el Window Manager impone la política global
  de FLAG_SECURE. No necesita mantenimiento de scope por app.

---

## ⚠️ Aviso legal

`FLAG_SECURE` existe por buenas razones: apps bancarias, gestores de
contraseñas, el propio keyguard, contenido DRM y muchos otros lo usan para
evitar que credenciales o contenido bajo licencia se escapen por
capturas o grabación remota.

Desactivarlo tiene sentido en **tu propio dispositivo** cuando necesitas
controlarlo remotamente (por ejemplo, un teléfono con la pantalla táctil
rota que manejas por AnyDesk). Desactivarlo en dispositivos que no son
tuyos, para capturar contenido que no estás autorizado a capturar, es
casi seguro ilegal en tu jurisdicción.

**Tú eres responsable del uso que le des al módulo.** Se ofrece sin
garantía de ningún tipo. No lo actives en un teléfono que contenga datos
que no quieras que terminen en un screenshot o una grabación.

---

## Cómo funciona

Cuando una ventana tiene `FLAG_SECURE`, `SurfaceFlinger` marca el
`SurfaceControl` subyacente como "secure", y el compositor sustituye esa
capa por negro sólido cuando la pantalla se está capturando (grabadores,
MediaProjection, `screencap`, etc.). La decisión final se reduce a un
`boolean` en un `SurfaceControl` en composición.

El módulo corre dentro de `system_server` e instala tres hooks que
convergen en forzar ese boolean a `false`:

1. **`SurfaceControl$Transaction.setSecure(SurfaceControl, boolean)`**
   — la ruta moderna. Cualquier transacción que intente marcar una capa
   como segura ve su argumento `boolean` reescrito a `false` antes de la
   llamada real.

2. **`WindowState.isSecureLocked()`**
   — helper interno del Window Manager que muchas rutas relacionadas con
   captura consultan. Se fuerza a devolver `false` para que la lógica
   posterior (filtrado de captura de display, etc.) se comporte como si
   la ventana no fuera segura.

3. **`SurfaceControl$Builder.setSecure(boolean)`**
   — la ruta de creación, usada en versiones de Android más antiguas
   donde el flag se decide al construir la surface en lugar de vía una
   transacción posterior. También se reescribe a `false`.

Los hooks 1 y 3 son tolerantes: si el método no existe en la plataforma
en ejecución, ese hook concreto se omite y el log registra cuántos hooks
se instalaron realmente.

### ¿Por qué solo `system_server`?

`FLAG_SECURE` lo impone globalmente el Window Manager y SurfaceFlinger,
no el proceso de cada app. Enganchar `system_server` basta para cubrir
todas las ventanas del dispositivo en un único sitio, y evita tener que
añadir cada app que te interese al scope de LSPosed.

---

## Compilación

### Requisitos

- JDK 17
- Android SDK con la **Platform 34** instalada
  (`sdkmanager "platforms;android-34"`)
- Gradle viene empaquetado vía wrapper (`gradlew` / `gradlew.bat`)

### Configurar la ruta del SDK

Crea `local.properties` en la raíz del repo (este fichero está en
`.gitignore`):

```properties
sdk.dir=C:/Users/<tu-usuario>/AppData/Local/Android/Sdk
# o en Linux / macOS:
# sdk.dir=/home/<tu-usuario>/Android/Sdk
```

Alternativamente, exporta la variable de entorno `ANDROID_HOME` o
`ANDROID_SDK_ROOT`.

### Compilar

```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

El APK queda en
`app/build/outputs/apk/debug/app-debug.apk` (unos **10 KB**).

---

## Instalación y activación

1. **Instala el APK** en el dispositivo destino:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Abre LSPosed Manager (o Vector Manager)** → *Módulos* →
   *DisableFlagSecure* → activa el toggle **Habilitado**.

3. **Configura el scope.** Añade la entrada **System Framework** /
   `android` al scope del módulo (en algunos managers aparece como
   "System Framework"). No hace falta nada más.

4. **Reinicia.** `system_server` tiene que reiniciarse para cargar los
   hooks.

### Verificar que los hooks se cargaron

Revisa los logs de LSPosed tras arrancar:

```bash
adb shell "su -c 'cat /data/adb/lspd/log/modules_*.log | grep DisableFlagSecure'"
```

Deberías ver líneas parecidas a:

```
I/LSPosed-Bridge: DisableFlagSecure: loaded in android
I/LSPosed-Bridge: DisableFlagSecure: hooked ...Transaction.setSecure(SurfaceControl,boolean)
I/LSPosed-Bridge: DisableFlagSecure: hooked ...WindowState.isSecureLocked()
I/LSPosed-Bridge: DisableFlagSecure: hooked ...Builder.setSecure(boolean)
I/LSPosed-Bridge: DisableFlagSecure: total hooks installed = 3
```

### Prueba funcional rápida

El PIN pad del Keyguard siempre aplica `FLAG_SECURE`. Con el módulo
desactivado, `adb shell screencap -p` tomada mientras se ve el PIN pad
produce una imagen completamente negra. Con el módulo cargado produce un
screenshot normal mostrando los dígitos del PIN — eso confirma que el
bypass funciona.

---

## Estructura del proyecto

```
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml           # meta-data Xposed + declaración de scope
│       ├── assets/
│       │   └── xposed_init               # nombre de la clase entry-point
│       ├── java/com/diffusion/disableflagsecure/
│       │   └── Hook.java                 # los 3 hooks
│       └── res/values/
│           ├── arrays.xml                # xposed_scope = [android]
│           └── strings.xml
├── xposed-stubs/                          # stubs compileOnly de la API
│   └── src/main/java/de/robv/android/xposed/…
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

### `xposed_init`

LSPosed lee el nombre de la clase de entrada desde `assets/xposed_init`.
Para este módulo es simplemente:

```
com.diffusion.disableflagsecure.Hook
```

### `xposedminversion`

El manifest declara `xposedminversion=93`, compatible con LSPosed moderno
y Vector LSPosed. Frameworks Xposed más antiguos (como EdXposed original)
podrían requerir un valor menor.

---

## Troubleshooting

- **Los hooks no se cargan tras reiniciar.**
  Comprueba que el módulo esté *Habilitado* **y** tenga `system` en el
  scope. Algunos managers llaman a esa entrada "System Framework" o
  `android`; internamente la BD de LSPosed la almacena como la cadena
  literal `system` (en `scope.app_pkg_name`).

- **Los logs dicen "0 hooks installed".**
  Tu versión de Android puede haber renombrado o reestructurado los
  internals de `SurfaceControl` / `WindowState`. Adjunta un log completo
  de LSPosed y abre un issue; añadiremos otro fallback.

- **La app sigue viéndose en negro por AnyDesk.**
  Confirma primero que `adb shell screencap -p /sdcard/x.png` de esa
  misma pantalla **no** sale en negro. Si `screencap` ve bien la pantalla
  pero AnyDesk sigue en negro, el problema está en el diálogo de permiso
  de MediaProjection o la configuración de AnyDesk, no en FLAG_SECURE.

- **Una app se rompe al activar el módulo.**
  El módulo solo engancha `system_server`. Por construcción no puede
  hacer crashear procesos de apps individuales. Si es `system_server` el
  que entra en crash-loop, saca logs vía ADB en recovery y adjúntalos en
  un issue.

---

## Licencia

MIT — ver [LICENSE](LICENSE).
