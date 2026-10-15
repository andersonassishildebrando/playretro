# Play Retro — notas de desenvolvimento

Frontend Android touch-first construído sobre um frontend de código aberto sob licença MIT
(avisos de copyright em `LICENSE`, créditos em `CREDITS.md`). Application ID:
`org.play_retro.frontend`.

## Origem do código

O código base público não inclui a camada Android. Tudo abaixo foi escrito do zero para o
Play Retro, a partir do contrato visível no C++. Código descompilado de APKs de terceiros
**não** deve ser copiado para este repositório.

Peças que não existiam no código base (todas implementadas):

| Peça | Referenciado em |
|---|---|
| `es-core/src/utils/PlatformUtilAndroid.{h,cpp}` (ponte JNI C++ → Kotlin) | `es-core/CMakeLists.txt:179-181` |
| `es-core/src/InputOverlay.{h,cpp}` (será substituído pela camada de gestos) | `InputManager`, `Window`, `main.cpp` |
| `external/SDL_Android` | `CMakeLists.txt:493` |
| `android/libs/<abi>/*` (FFmpeg, curl, OpenSSL, FreeImage, FreeType, HarfBuzz, libgit2, ICU, intl, jpeg, poppler, pugixml, SDL2) | `CMakeLists.txt:582-606` |
| `android_<abi>/liblunasvg.a`, `librlottie.a` | `CMakeLists.txt:656-658` |
| Projeto Gradle/Kotlin (Activity, onboarding, SAF, FileProvider, importador) | — |

## Contrato `Utils::Platform::Android` (derivado das chamadas)

| Função | Chamada em | Uso |
|---|---|---|
| `checkConfigurationNeeded()` / `startConfigurator()` | `main.cpp:657-664` | onboarding; C++ espera em `AndroidVariables::sHold` |
| `setDataDirectories()` / `setROMDirectory()` | `main.cpp:667-668` | diretórios escolhidos no onboarding |
| `checkNeedResourceCopy(buildId)` / `setupFontFiles()` / `setupLocalizationFiles()` / `setupResources(buildId)` | `main.cpp:1040-1100` | copiar assets para armazenamento interno |
| `getCreateSystemDirectories()` | `main.cpp:1106` | criar pastas de sistemas |
| `printDeviceInfo()` / `onResume()` | `main.cpp:1081`, `:537` | log / ciclo de vida |
| `getWindowSize()` → `pair<int,int>` | `Renderer.cpp:113`, `ViewController.cpp:1600` | |
| `getBluetoothStatus()`, `getWifiStatus()`, `getCellularStatus()`, `getBatteryStatus()` → `pair<int,int>` | `SystemStatus.cpp` | barra de status |
| `getInternalDirectory()` / `getExternalDirectory()` | `FileData.cpp:1974-1976` | `%INTERNALDATA%`/`%EXTERNALDATA%` |
| `checkEmulatorInstalled(pkg, activity)` | `FileData.cpp:1318`, `:2612` | find rules `androidpackage` |
| `checkRACoreInstalled(pkg, core)` → int | `FileData.cpp:2244` | cores RetroArch |
| `launchGame(pkg, activity, action, category, mime, data, startPath, rom, extrasStr, extrasStrArray, extrasInt, extrasBool, flags, otherScreen)` | `FileData.cpp:2298` | Intents de emulador (ver `GameLauncher.kt`) |
| `getInstalledApps(list, gamesOnly, media)` | `GuiGameImporter.cpp:321` | importador de apps |

## Build Android

1. **Dependências nativas** (Linux ou WSL2, NDK r28c em `/opt/android/android-ndk-r28c`):
   ```
   bash android/deps/build-deps.sh            # todos os passos, retomável
   FORCE=1 bash android/deps/build-deps.sh ffmpeg install   # refazer passos específicos
   ```
   Gera `android/prefix/<abi>` (headers), `android/libs/<abi>` (.so sem versão, alinhados a 16 KB,
   incluindo `libc++_shared.so`), `android/sdl2-java` (glue Java do SDL) e
   `resources/locale/*/LC_MESSAGES/*.mo`.
2. **APK** (Windows, macOS ou Linux, com Android SDK e NDK 28.2.13676358):
   ```
   cd android && ./gradlew assembleDebug
   ```
   O Gradle chama o `CMakeLists.txt` da raiz e empacota `resources/` + o tema `linear` como
   assets.

## Camada Android (`android/app`)

| Arquivo | Papel |
|---|---|
| `MainActivity.kt` | `SDLActivity`; funções `@JvmStatic` chamadas pelo C++ via JNI |
| `MainActivityHomeApp.kt` | mesma activity registrada como HOME (launcher do aparelho) |
| `NativeBridge.kt` | callbacks para o C++ (`nativeSetHold`, `nativeSetHomeApp`, `nativeLog`) |
| `OnboardingActivity.kt` | primeira execução em Compose: permissão, pastas, diretórios de sistemas |
| `GameLauncher.kt` | monta o Intent do emulador (`%ROM%`, `%ROMSAF%`, `%ROMPROVIDER%`, extras, flags, 2ª tela) |
| `AppImporter.kt` | lista apps instalados e exporta ícones/banners para o importador |
| `AssetInstaller.kt` | copia recursos, fontes, traduções e tema do APK para o armazenamento interno |
| `DeviceStatus.kt` | Bluetooth, Wi-Fi, rede celular, bateria |
| `StoragePaths.kt` | conversão caminho ⇄ URI do SAF |

## Entrada touch

`es-core/src/InputOverlay.*` deixou de desenhar botões virtuais: agora reconhece gestos e os traduz
para as entradas de navegação existentes — toque = A, arrastar/deslizar = passos do D-pad com
inércia, toque longo = Back (menu de opções), dois dedos = B (voltar), três dedos = Start (menu
principal), deslizar com dois dedos = L/R (horizontal) ou gatilhos (vertical). O gesto/botão
"voltar" do Android navega para trás (`BackEventAppExit` agora é `false` por padrão).

## Identidade e serviços externos

Nome, diretório de dados e user-agent ficam em `es-core/src/Branding.h`. O código base embutia
credenciais de desenvolvedor de terceiros (ScreenScraper e TheGamesDB) e apontava o atualizador para
outro servidor; isso foi removido. O Play Retro usa contas próprias, passadas no build:

```
cmake -DSCREENSCRAPER_DEV_ID=... -DSCREENSCRAPER_DEV_PASSWORD=... -DTHEGAMESDB_API_KEY=... \
      -DAPPLICATION_UPDATER_URL=https://.../latest_release.json ...
```

Sem elas, o scraper correspondente não autentica e o atualizador fica desligado. Nunca commitar
valores reais. A lista de temas vem de `github.com/andersonassishildebrando/playretro-themes-list`
(`THEMES_LIST_REPOSITORY_URL` em `Branding.h`).

## Fases

0. **Ambiente** — JDK 21, SDK 36, NDK 28.2, CMake, Ninja, Git. ✅
1. **Rebranding** — ID, nome "Play Retro", diretório `PlayRetro/`, user-agent, strings de UI e
   catálogos de tradução, `android_strings.xml`, credenciais/atualizador ✅. Ícones, splash e
   assets de desktop com a identidade própria (`tools/branding/generate_branding.py`) ✅.
2. **Dependências nativas** — cross-compile arm64-v8a (depois x86_64 p/ emulador).
3. **Ponte** — `PlatformUtilAndroid.cpp` + módulo Kotlin com os métodos acima.
4. **App Android** — Gradle, SDLActivity, onboarding em Compose, SAF, FileProvider, permissões.
   Primeiro APK executável.
5. **Touch-first** — gestos nativos (swipe entre sistemas, tap para abrir, long-press para menu,
   pull para busca), layouts e menus para telas pequenas/retrato, teclado nativo, sem overlay de
   botões virtuais como entrada principal; controles físicos continuam funcionando.
6. **Release** — assinatura, testes em dispositivos, revisão de permissões para a Play Store.
