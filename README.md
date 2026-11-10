# kmp-resources

Gradle-плагин: вшивает файлы в нативный бинарник Kotlin Multiplatform и даёт
к ним доступ из общего кода — **без `objcopy` и без binutils**.

## Зачем

В Kotlin/Native нет встроенного механизма ресурсов. `getResourceAsStream`
работает только на JVM: там jar — это zip, ресурсы лежат рядом с классами.
У нативного бинарника такого контейнера нет.

`objcopy -I binary` решает задачу на ELF, но:

| Проблема | Следствие |
|---|---|
| его нет в поставке Kotlin/Native | `~/.konan/dependencies/llvm-*/bin/` содержит clang, lld, llvm-ar — но не objcopy |
| требует системный binutils | внешняя зависимость в публичном плагине |
| не понимает Mach-O | macOS и iOS отваливаются |
| имя символа собирается из пути | `res/resources.tar` → `_binary_res_resources_tar_start` |

Этот плагин пишет ELF-объектник **сам, байтами**. Ни binutils, ни системный
компилятор не нужны — только clang, который уже приезжает с Kotlin/Native.

## Как работает

```
┌─────────────────────────────────────────────────────────────┐
│ 1. ресурсы из каталогов from(...)                           │
│         ↓                                                    │
│ 2. контейнер KRES (своё оглавление + md5 на файл)           │
│         ↓                                                    │
│ 3. ELF / Mach-O / COFF объектник (пишется вручную)          │
│         ↓                                                    │
│ 4. llvm-ar → статический архив на каждый таргет             │
│         ↓                                                    │
│ 5. -include-binary → архив внутри klib                      │
│         ↓                                                    │
│ 6. линкер Kotlin/Native → бинарник с ресурсами внутри       │
└─────────────────────────────────────────────────────────────┘
```

Шаг 5 — то, что делает плагин пригодным для библиотек: архив едет внутри
klib, поэтому ресурсы попадают в приложение-потребитель **без единой настройки
с его стороны**. `linkerOpts` так не умеют — они локальны и в опубликованный
klib не попадают.

На JVM схема другая: контейнер там не нужен, каждый ресурс едет в jar
отдельной записью `kmpres/<пакет>/<имя>` (см. «Почему на JVM нет контейнера»).

## Установка

Плагин публикуется в **Maven Central** (`pw.binom:kmp-resources-plugin`) и в
**Gradle Plugin Portal** под id `pw.binom.resources`.

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("pw.binom.resources") version "1.4.1"
}

resources {
    packageName = "pw.binom.myapp.resources"
    // from(...) не обязателен: ресурсы берутся из source sets модуля
}
```

`packageName` — пакет сгенерированного кода. Из него же по умолчанию считается
**namespace модуля** — то, что попадает в имена C-символов (`_binary_kmpres_<ns>_…`)
и в cinterop-пакет (`<packageName>.kmpres`). Namespace должен быть уникален в
пределах приложения: если не задать — подставляется
`pw.binom.resources.generated.<имя проекта>`, чтобы два модуля без настройки не
столкнулись. Задать руками можно через `namespace = "…"` (только латиница,
цифры и `_`).

## Использование

```kotlin
import pw.binom.myapp.resources.Resources

val html = Resources.readText("web/index.html")
val bytes = Resources.read("web/logo.png")
val есть = Resources.exists("web/style.css")

Resources.list().forEach { println("${it.name} — ${it.size} байт, md5=${it.md5}") }
```

Пути — относительно корня каталога ресурсов. Вложенность сохраняется:
`src/commonMain/resources/web/index.html` → `"web/index.html"`.

## Наборы ресурсов по таргетам

Каталоги ресурсов плагин забирает **сам** — из всех source sets, которые видит
таргет. `from(...)` нужен только для каталогов вне `src/*/resources`.

Набор у каждого таргета свой, и это не теория:

```
src/commonMain/resources/web/index.html     <- все платформы
src/linuxMain/resources/linux-main.txt      <- только linuxX64 и linuxArm64
src/linuxX64Main/resources/linux-x64.txt    <- только linuxX64
src/mingwX64Main/resources/win-only.txt     <- только mingwX64
src/jvmMain/resources/jvm-only.txt          <- только JVM
src/androidMain/resources/android-only.txt  <- только Android
```

Результат `Resources.list()`:

```
linuxX64:  [linux-main.txt, linux-x64.txt, web/index.html, web/logo.png]
mingwX64:  [win-only.txt, web/index.html, web/logo.png]
jvm:       [jvm-only.txt, web/index.html, web/logo.png]
android:   [android-only.txt, web/index.html]
```

Работают и произвольные группы иерархии — плагин идёт от компиляции, а не от
списка имён, поэтому любой `dependsOn`-граф учитывается:

```kotlin
kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("unixAll") {
                withLinuxX64(); withLinuxArm64(); withMingwX64()
            }
        }
    }
}
// src/unixAllMain/resources/unix-all.txt -> linuxX64, linuxArm64, mingwX64 (не jvm)
```

Важно: `dependsOn` у source set'ов можно менять только на этапе конфигурации —
KGP запрещает это позже (`Illegal 'dependsOn' call in stage 'ReadyForExecution'`).
И если вручную подключить набор до применения шаблона иерархии, KGP перестаёт
создавать промежуточные наборы вроде `linuxMain`, и их ресурсы пропадают.

Технически: на каждый нативный таргет собирается **свой** контейнер и свой
архив, поэтому в бинарник попадает ровно то, что относится к его платформе.
Оглавление на нативе читается из контейнера в рантайме (`list()`/`exists()`),
поэтому один и тот же сгенерированный Kotlin-код обслуживает таргеты с разными
наборами. На JVM/Android список известен на этапе компиляции (jar-записи) и
лежит в `ResourceIndex`.

## Библиотеки

Плагин можно применять и к библиотеке — ресурсы доедут до приложения вместе с
klib, без настройки у потребителя:

```kotlin
// библиотека
resources {
    packageName = "org.demo.lib.res"
    from("src/commonMain/resources")
}
```

```kotlin
// приложение
dependencies {
    implementation("org.demo:demo-lib:1.0.0")
}
```

```kotlin
// код приложения: у каждого модуля свой объект Resources
val own = myapp.resources.Resources.readText("web/index.html")
val fromLib = org.demo.lib.res.Resources.readText("web/lib.html")
```

Как это устроено:

- на каждый нативный таргет собирается свой архив `kmpres-<ns>.a` (ELF или
  Mach-O, смотря какой таргет) и вкладывается в klib через `-include-binary` —
  линкер приложения подтянет его сам (в том числе собственные бинарники и тесты
  библиотеки);
- символов у модуля ровно три, с namespace: `_binary_kmpres_<ns>_start`,
  `_end`, `_size`, плюс обёртки `kmpres_<ns>_data`/`_size`. Namespace по
  умолчанию считается из `packageName`, переопределяется `namespace = "…"`.
  Два модуля с плагином в одном бинарнике не конфликтуют. В Mach-O у этих имён
  в таблице символов ещё один `_` — так устроен формат;
- на JVM префикс jar-записей — `kmpres/<пакет>/...`, поэтому одинаковые имена
  в разных библиотеках не перекрываются.

**Важно:** ресурсы **не** собираются со всех библиотек в один список. Каждый
модуль с плагином собирает только свои ресурсы и получает свой `Resources`;
библиотека везёт свой архив в klib, приложение линкует его рядом со своими.
`list()` показывает только ресурсы своего модуля — глобального реестра нет
(на нативе его не из чего собрать в рантайме).

## Платформы

| Платформа | Механизм | Статус |
|---|---|---|
| linuxX64 | ELF `.data` → `.a` → `-include-binary` | ✅ запуск (приложение + библиотека) |
| linuxArm64 | то же, `EM_AARCH64`, кросс-сборка с x86-хоста | ✅ линкуется |
| macosArm64 | Mach-O `__DATA,__data` → `.a` → `-include-binary` | ✅ запуск (macOS 14, Apple Silicon) |
| macosX64 | то же, `CPU_TYPE_X86_64` | ✅ линкуется |
| iosArm64 / iosSimulatorArm64 | то же | ✅ klib собирается, симуляторный бинарник линкуется |
| watchosX64 / watchosSimulatorArm64 | то же | ✅ линкуется |
| watchosArm64 | то же, `CPU_TYPE_ARM64_32` | ✅ линкуется (arm64_32) |
| mingwX64 | COFF `.data` → `.a` → `-include-binary` | ✅ запуск под wine (приложение + библиотека) |
| androidNativeArm64 / X64 | ELF | ✅ запуск на устройстве (arm64, API 36) |
| jvm | обычные записи `kmpres/<пакет>/...` в jar | ✅ Linux и macOS |
| androidTarget | java-ресурсы в APK/AAR, classloader | ✅ запуск на устройстве |
| tvos* | Mach-O | ⚠️ написано, не проверялось |
| watchosArm32, androidNativeArm32/X86 | 32-битные форматы | ❌ не реализовано |
| js / wasmJs | физически некуда вшить | ❌ не поддерживается |

Формат объектника выбирается по имени таргета (`macos*`/`ios*`/`tvos*`/`watchos*`
→ Mach-O, `linux*`/`androidNative*` → ELF, `mingw*` → COFF), поэтому один и тот
же проект собирает ресурсы под все платформы.

## Почему на JVM нет контейнера

Jar — это уже zip с произвольным доступом и ленивой подгрузкой, а оглавление
(имена, размеры, md5) известно из сгенерированного `ResourceIndex`. Второй
контейнер внутри jar ничего к этому не добавлял и мешал:

| Проблема | Следствие |
|---|---|
| читался целиком в память | весь набор ресурсов в heap при первом обращении |
| дублировал оглавление | те же имена/размеры/md5 лежали и в коде, и в контейнере |
| не давал стримить | поток нельзя отдать из середины сжатой записи container'а |
| скрывал ресурсы от инструментов | не работают `getResource`, ресурс-фильтры, просмотр jar |

Поэтому на JVM каждый файл кладётся отдельной записью под префиксом `kmpres/<пакет>/`:

```
jar:
  kmpres/org/demo/app/web/index.html
  kmpres/org/demo/app/web/logo.png
```

Два уровня нужны с двух сторон: `kmpres/` — чтобы имена не попали в корень jar
(где столкнулись бы с ресурсами других библиотек), `<пакет>` — namespace
модуля, чтобы одинаковые имена в двух библиотеках не перекрывались.

## Android

Android-таргет (`androidTarget`) идёт тем же путём, что JVM: файлы кладутся
java-ресурсами, AGP упаковывает их в APK, читаются через classloader.

```
APK:
  kmpres/org/demo/app/web/index.html      <- своё приложение
  kmpres/org/demo/lib/web/lib.html        <- зависимость
```

Две особенности, найденные на практике:

- KGP не доносит `resources` из `androidMain` до AGP у **application**-модуля
  (у library — доносит). Плагин регистрирует каталог в
  `android.sourceSets.main.resources` сам, через рефлексию — чтобы не тянуть
  AGP в зависимости плагина.
- `androidMain` создаёт AGP в своём `afterEvaluate`, поэтому подключение идёт
  через `sourceSets.matching { }.configureEach { }`, а не `findByName`.

Плагин применяется только к KMP-модулю (`kotlin("multiplatform")`): в обычном
Android-модуле без KMP он ничего не сгенерирует.

Для кода `Resources` генерируется два одинаковых файла — `kotlin/jvm` и
`kotlin/android`: один каталог исходников нельзя положить в два source set.
Ресурсы (`jvm-resources`) общие — дерево файлов конфликта не даёт.


## Сколько занимает оперативку

Ресурсы не загружаются при старте: секция с данными отображена в память, страницы
подгружаются по требованию. Замеры на бинарнике с ресурсом 64 МБ (Linux, RSS):

| Что делали | RSS |
|---|---|
| процесс стартовал, к ресурсам не обращались | 3 МБ |
| `Resources.exists` / `size` / `list` | **5 МБ** |
| `read("big.bin")` — весь файл целиком | ~70 МБ |
| потоковое чтение блоками по 1 МБ вручную | ~70 МБ (страницы файла) |
| `Resources.forEachChunk("big.bin")` | **3–5 МБ** |

Почему так:

- контейнер **не копируется** в кучу — `ContainerReader` разбирает оглавление прямо
  из указателя, поэтому `exists`/`list` читают только блок имён (килобайты);
- `read(name)` копирует только сам файл;
- поднятые страницы — file-backed и **вытесняемые**: под давлением ОС их отдаёт.
  Они видны в RSS, но это page cache, а не занятая память;
- `madvise(MADV_DONTNEED)` на уже прочитанный диапазон возвращает RSS к базовому.

Выгрузить большой ресурс на диск, не поднимая его в память целиком:

```kotlin
Resources.forEachChunk("big.dll") { chunk, size ->
    file.write(chunk, 0, size)
}
```

`forEachChunk` на нативе после каждого блока возвращает прочитанные страницы ядру
(`madvise(MADV_DONTNEED)`), поэтому RSS остаётся на уровне буфера. На Apple этот
совет — no-op (страницы вытесняет система), на Windows его нет вовсе; в обоих
случаях страницы file-backed и вытесняемые, а куча не растёт.

Сборка тоже не держит ресурсы в памяти: контейнер пишется потоком (файлы
копируются кусками по 64 КБ, MD5 считается на лету), объектник льёт блоб в
себя из файла. Замер на наборе 64 МБ: пик RSS сборочной JVM — **121 МБ**
(включая сам Gradle), раньше было бы ~3× размера набора.

Если нужен свой цикл — есть низкоуровневый вариант:

```kotlin
val buffer = ByteArray(1024 * 1024)
var offset = 0L
while (true) {
    val n = Resources.read("big.dll", buffer, offset = offset)   // length = -1 → сколько влезет
    if (n <= 0) break
    file.write(buffer, 0, n)
    offset += n
}
```

На JVM то же самое: `read(name, dst, offset)` читает поток из jar, куча не растёт.
`readOnly = true` дополнительно гарантирует, что маппинг не станет writable —
иначе случайная запись создала бы приватные грязные страницы, которые нельзя
вытеснить.

## Формат контейнера

Контейнер используется только нативными таргетами.

```
Заголовок (16 байт):
  magic    4   "KRES"
  version  4   int LE
  count    4   int LE
  reserved 4

Оглавление (count × 40 байт):
  nameOffset 4   смещение имени в блоке имён
  nameLength 4
  dataOffset 8   от начала контейнера
  dataLength 8
  hash       16  MD5 содержимого

Блок имён: UTF-8 подряд, без нулей.
Блок данных: подряд, выровнен на 8.
```

## Формат объектников

Оба писателя — вручную, без binutils:

| | ELF (linux, androidNative) | Mach-O (apple) | COFF (mingw) |
|---|---|---|---|
| файл | `ET_REL` | `MH_OBJECT` | `IMAGE_FILE_HEADER` |
| секция | `.data` | `__DATA,__data` | `.data` |
| символы | `_binary_kmpres_<ns>_*` | те же, в таблице с ещё одним `_` | те же, без префикса |
| по умолчанию | `.data` (WA) | `__DATA,__data` | `.data` (rw) |
| `readOnly = true` | `.rodata` (A) | `__TEXT,__const` | `.rdata` (r) |
| особенности | ведущий `\0` в `.strtab` | `LC_SEGMENT_64`, `LC_SYMTAB`, `LC_DYSYMTAB` | длинные имена — в strtab |

`MachWriter` повторяет то, что генерирует clang для `MH_OBJECT`: пустое
`segname` у сегмента, `MH_SUBSECTIONS_VIA_SYMBOLS` в `flags`, выравнивание
данных на 16.

## API плагина

```kotlin
resources {
    packageName = "..."          // пакет сгенерированного кода
    from("dir1", "dir2")         // доп. каталоги (обычно не нужны: src/*/resources берутся сами)

    // Namespace C-символов. Пусто — считается из packageName (точки → "_").
    // Задавать руками нужно редко; обязан быть уникален в приложении.
    namespace = ""

    // Куда класть данные. По умолчанию — записываемая секция.
    readOnly = true              // .rodata / .rdata / __TEXT,__const
    sectionName = ""             // ручное имя: ".myres" или "__DATA,__myres"
}
```

`readOnly` — единственный переносимый способ выбрать «правильную» секцию: имена
и флаги подбираются по формату. Ресурсы неизменяемы, поэтому для больших
наборов это разумное умолчание: страницы не пишутся, шарятся между процессами,
случайная запись падает сразу.

`sectionName` — если очень хочется своё имя. Для Apple-таргетов пишется как
`SEGMENT,SECTION` (части ≤16 байт), для COFF — не длиннее 8 байт, для ELF —
любое. Флаг записи всё равно определяется `readOnly`.

Архитектуры и формат объектника плагин определяет сам по нативным таргетам
проекта — настраивать ничего не нужно.

## Задачи

```
gradle kmpresPrepare    — упаковать ресурсы и сгенерировать код
gradle linkDebugExecutableNative
```

`kmpresPrepare` подключается зависимостью ко всем задачам компиляции,
cinterop и обработки ресурсов — вручную вызывать не нужно.

## Что генерируется

```
build/generated/kmpres/
├── obj/<таргет>/resources.kres          контейнер этого таргета
├── obj/<таргет>/resources.o             ELF или Mach-O с ресурсами
├── obj/<таргет>/wrapper.o               ELF или Mach-O с C-обёрткой
├── obj/<таргет>/kmpres-<ns>.a           архив для klib
├── cinterop/kmpres.def                  cinterop: package = <packageName>.kmpres
├── cinterop/kmpres_wrapper.h
├── cinterop/kmpres_wrapper.c
├── kotlin/common/ResourceIndex.kt       оглавление (только JVM/Android)
├── kotlin/common/Resources.kt           expect object
├── kotlin/native/ContainerReader.kt     разбор формата
├── kotlin/native/ResourcesNative.kt     actual: символ из бинарника
├── kotlin/jvm/ResourcesJvm.kt           actual: classloader + jar-записи
├── kotlin/android/ResourcesJvm.kt       тот же actual для Android
└── jvm-resources/kmpres/<пакет>/<имя>   ресурсы для jar, по одному файлу
```

## Питфоллы, найденные при разработке

| Питфолл | Что происходит | Решение |
|---|---|---|
| `objcopy` после линковки | символов `__start_`/`__stop_` нет, из кода не достать | только `.o` **до** линковки |
| имя символа с путём | `res/x.tar` → `_binary_res_x_tar_start` | своё имя символа в писателе, с namespace модуля |
| один и тот же символ у всех модулей | `multiple definition` на линковке двух библиотек | namespace в символах, cinterop-пакете и jar-префиксе |
| `linkerOpts` у библиотеки | в опубликованный klib не попадают, потребителю ресурсы не доезжают | `-include-binary <архив>` в main-компиляцию |
| один объектник на все таргеты | `e_machine` не совпадает, линкер отвергает | архив и объектник на каждый таргет |
| Mach-O и префикс `_` | символ `_binary_…` хранится как `__binary_…` | писатель добавляет `_` сам, в C имя без него |
| `segname` у `MH_OBJECT` | clang оставляет его пустым; с именем ld64 ругается | пустое имя сегмента, как у clang |
| нет `LC_DYSYMTAB` | ld64 не принимает объектник | команда со списком внешних определений |
| main в `macosArm64Main` | `Could not find '/main' function` при сборке x64 | общий source set (`macosMain`/`appleMain`) |
| нет ведущего нуля в `.strtab` | нулевой символ и `_binary_…_start` получают одинаковое имя (`st_name = 0`); lld на Linux терпит, Android — нет: `undefined reference` + `multiple definition of no symbol` | первым байтом `.strtab` писать `\0` |
| COFF: имя символа > 8 байт | короткое имя хранится в записи, длинное — в таблице строк | `Zeroes` + смещение в strtab |
| имя секции на модуль | в Mach-O 16 байт на sectname/segname, в COFF 8 — `__kmpres_org_demo_lib_res` туда не влезает | уникальность даёт namespace в **символах**, а не в секции; имена секций одинаковые и сливаются линкером |
| AGP + новый Gradle | AGP 8.x не работает на Gradle ≥ 9.6 (`InternalProblems` удалён) | сборка Android-модулей на Gradle 8.14 |
| `unsigned char*` в cinterop | даёт `CPointer<UByteVar>`, несовместимый с `ByteVar` | объявлять `const char*` |
| `@file:OptIn` после `package` | «imports are only allowed in the beginning of file» | OptIn первой строкой файла |
| `expect fun` внутри object | «Function without a body must be abstract» | обычный класс + платформенная функция |
| один каталог в двух source sets | «can be a part of only one module» / дубли `actual` в тестах | генерировать только в default source set main-компиляции таргета |
| ручной `nativeMain` при одном нативном таргете | шаблон иерархии его не создаёт, набор оказывается вне дерева source sets — `actual` не видит `commonMain` | не заводить промежуточный набор, писать в default source set таргета |
| `Provider` в `linkerOpts` | «Argument type mismatch» | считать пути заранее через `File` |
| `SourceSetContainer.named("main")` | «SourceSet with name 'main' not found» в KMP | resources компиляции |
| нет зависимости у `processResources` | Gradle ругается на порядок | `dependsOn(kmpresPrepare)` |
| нет зависимости у `sourcesJar` | «uses this output without declaring a dependency» при `publish` | `dependsOn(kmpresPrepare)` для задач с `jar` в имени |
| `*/` или `*.kt` в KDoc | комментарий закрывается/открывается досрочно | не писать `llvm-*/bin` и `*.kt` в доке |

## Сборка

```bash
./gradlew :plugin:build                 # компиляция + validatePlugins
./gradlew :plugin:publishToMavenLocal   # локально, без ключей
```

## Публикация

GitHub Actions:

- `.github/workflows/ci.yml` — сборка на каждый push/PR;
- `.github/workflows/release.yml` — по тегу `N.N.N` публикует в Maven Central
  (артефакт + marker, sha-подписи), а если в репозитории заведены секреты
  `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` — ещё и в Gradle Plugin Portal.

```bash
git tag 1.4.1 && git push origin 1.4.1
```

Координаты:

```
pw.binom:kmp-resources-plugin:<версия>
pw.binom.resources:pw.binom.resources.gradle.plugin:<версия>   # marker
```

Тесты самого плагина — в отдельном репозитории `kmpres-test`; здесь прогоны
делались на временных проектах под Linux, macOS, Windows (wine) и Android.

## Лицензия

Apache License 2.0 — см. [LICENSE](./LICENSE).
