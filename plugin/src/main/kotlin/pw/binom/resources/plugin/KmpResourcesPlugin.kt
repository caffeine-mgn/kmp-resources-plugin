package pw.binom.resources.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

/**
 * Плагин `kmp-resources`: вшивает файлы в нативный бинарник и даёт к ним
 * доступ из общего кода.
 *
 * ## Применение
 *
 * ```kotlin
 * plugins {
 *     kotlin("multiplatform")
 *     id("pw.binom.resources")
 * }
 *
 * resources {
 *     packageName = "pw.binom.tgservice.resources"
 *     from("src/commonMain/resources/web")
 * }
 * ```
 *
 * ## Что делает
 *
 * 1. Собирает файлы из указанных каталогов в один контейнер (`KRES`).
 * 2. Генерирует ELF-объектник своими руками — без `objcopy`, которого нет
 *    в поставке Kotlin/Native.
 * 3. Собирает статический архив на каждую архитектуру проекта.
 * 4. Генерирует C-обёртку и cinterop-`def` (с namespace модуля).
 * 5. Генерирует Kotlin: оглавление, `expect object Resources`, оба `actual`.
 * 6. Вкладывает архив в klib таргета через `-include-binary` — поэтому ресурсы
 *    доезжают до приложения даже если плагин применён к библиотеке.
 *
 * ## Namespace модуля
 *
 * `packageName` — это не только пакет кода: из него строятся имена C-символов,
 * пакет cinterop и префикс jar-путей. Два модуля с плагином в одном приложении
 * не конфликтуют, пока пакеты разные.
 *
 * ## Чего не делает
 *
 * Не вшивает ресурсы в JS и wasm: там нет файла, в который можно дописать
 * байты. Для JVM контейнер едет в jar штатным путём.
 */
class KmpResourcesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("resources", KmpResourcesExtension::class.java)

        // Пакет по умолчанию содержит имя проекта: он же namespace модуля
        // (символы, cinterop-пакет, префикс jar). Общий пакет у двух модулей
        // дал бы duplicate definition на линковке.
        ext.packageName.convention(
            "pw.binom.resources.generated." + sanitizePackageSegment(project.name),
        )

        val prepare: TaskProvider<PrepareResourcesTask> = project.tasks.register(
            "kmpresPrepare",
            PrepareResourcesTask::class.java,
        ) { task ->
            task.group = "kmpres"
            task.description = "Упаковывает ресурсы и генерирует объектные файлы и архивы"
            task.sourceDirs.from(ext.sourceDirs)
            task.packageName.set(ext.packageName)
            task.namespace.set(ext.namespace)
            task.projectDir.set(project.layout.projectDirectory)
            task.sectionName.set(ext.sectionName)
            task.readOnly.set(ext.readOnly)
            task.targets.convention(emptyList())
            task.outputDir.set(project.layout.buildDirectory.dir("generated/kmpres"))
        }

        project.afterEvaluate {
            val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
                ?: return@afterEvaluate

            val genDir = project.layout.buildDirectory.dir("generated/kmpres").get().asFile
            val pkg = ext.packageName.get()
            val ns = CodeGen.resolveNamespace(pkg, ext.namespace.get())

            // Общий код: оглавление и expect — в commonMain.
            kmp.sourceSets.getByName("commonMain").kotlin.srcDir(File(genDir, "kotlin/common"))

            // Нативный код (actual и разбор контейнера) — в default source set
            // каждого нативного таргета, только в main-компиляцию: тестовая
            // компиляция видит main через associateWith, а тот же `actual`
            // второй раз в неё попадать не должен — иначе «actual has no
            // corresponding expect declaration».
            //
            // Отдельный промежуточный «nativeMain» не заводим: при одном
            // нативном таргете шаблон иерархии Kotlin его не создаёт, и
            // созданный вручную набор оказывается вне дерева source sets —
            // сгенерированный actual не видит ни commonMain, ни ResourceIndex.
            kmp.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                target.compilations.configureEach { compilation ->
                    if (compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) {
                        compilation.defaultSourceSet.kotlin.srcDir(File(genDir, "kotlin/native"))
                        // madvise есть только в POSIX: mingw получает заглушку.
                        val pageRelease = if (NativeTargetSpec.of(target.name)?.format == NativeFormat.COFF) {
                            "kotlin/pages/windows"
                        } else {
                            "kotlin/pages/posix"
                        }
                        compilation.defaultSourceSet.kotlin.srcDir(File(genDir, pageRelease))
                    }
                }
            }

            // Ресурсы каждого таргета = resources всех его main-наборов:
            // linuxX64 видит commonMain + linuxMain + linuxX64Main, macosArm64 —
            // свой набор. Поэтому и контейнер у таргета свой.
            //
            // Считаем лениво (provider): androidMain создаёт AGP позже, чем
            // выполняется наш afterEvaluate.
            val targetResourceDirs = project.provider {
                val result = linkedMapOf<String, List<String>>()
                kmp.targets.forEach { target ->
                    val type = target.platformType
                    if (type != KotlinPlatformType.native &&
                        type != KotlinPlatformType.jvm &&
                        type != KotlinPlatformType.androidJvm
                    ) {
                        return@forEach
                    }
                    val dirs = linkedSetOf<String>()
                    target.compilations.forEach { compilation ->
                        // Тестовые наборы не берём: ресурсы тестов в бандл не нужны.
                        if (compilation.name !in setOf("main", "debug", "release")) {
                            return@forEach
                        }
                        // Обходим dependsOn сами, а не через allKotlinSourceSets:
                        // KGP кэширует это множество и не видит наборы, которые
                        // подключили позже или «как попало» (например
                        // `linuxX64Main dependsOn customSet`).
                        // Берём объединение двух источников правды:
                        //  - allKotlinSourceSets — то, что KGP сам считает
                        //    набором компиляции (важно для android: связь
                        //    androidDebug ↔ androidMain живёт вне dependsOn);
                        //  - обход dependsOn — ловит наборы, подключённые
                        //    позже, которых в кэше allKotlinSourceSets нет.
                        val sourceSets = LinkedHashSet<KotlinSourceSet>()
                        sourceSets += compilation.allKotlinSourceSets
                        sourceSets += sourceSetClosure(compilation.defaultSourceSet)
                        sourceSets.forEach { sourceSet ->
                            sourceSet.resources.srcDirs.forEach { dir ->
                                // Свой же выход в наборе не нужен: плагин сам
                                // добавляет `jvm-resources` в source set, и без
                                // этой проверки задача читает то, что пишет.
                                if (dir.absolutePath.startsWith(genDir.absolutePath)) return@forEach
                                val rel = dir.relativeToOrNull(project.projectDir)?.invariantSeparatorsPath
                                dirs += rel ?: dir.absolutePath
                            }
                        }
                    }
                    result[target.name] = dirs.toList()
                }
                result
            }

            prepare.configure { task ->
                task.targetResourceDirs.set(targetResourceDirs)
                // Входы для up-to-date: все каталоги, включая явные from(...).
                // Отдельное свойство, чтобы союз таргетов не попадал в сборку.
                task.trackedDirs.from(
                    project.provider {
                        targetResourceDirs.get().values.flatten().map { project.file(it) }
                    },
                )
                task.trackedDirs.from(ext.sourceDirs)
            }

            // Набор таргетов = нативные таргеты проекта. Считаем здесь, до
            // запуска задачи: от него зависит, какие объектники и архивы она
            // изготовит. Неподдержанные (mingw, 32-битные ARM) в список не
            // попадают — для них нечем писать объектник.
            val nativeTargets = kmp.targets.withType(KotlinNativeTarget::class.java)
            prepare.configure { task ->
                task.targets.set(
                    nativeTargets.mapNotNull { NativeTargetSpec.of(it.name)?.name }.sorted(),
                )
            }

            // JVM и Android: ресурсы едут в jar/classes.jar обычными записями
            // `kmpres/<пакет>/<имя>`.
            //
            // На Android AGP кладёт java-ресурсы в classes.jar AAR, дальше они
            // попадают в APK — и classloader находит их так же, как на JVM.
            // Отдельного actual не нужно: класслоадерный код общий.
            //
            // Пишем в source set по имени, а не в компиляцию: у androidTarget
            // нет компиляции `main` — есть `debug`/`release`, и один source set
            // обслуживает обе.
            wireJvmLikeSourceSet(kmp, "jvmMain", genDir, "kotlin/jvm")
            wireJvmLikeSourceSet(kmp, "androidMain", genDir, "kotlin/android")

            if (kmp.targets.any { it.platformType == KotlinPlatformType.androidJvm }) {
                wireAndroidJavaResources(project, File(genDir, "jvm-resources"))
            }

            // Обработка ресурсов, компиляция и jar'ы (включая sourcesJar) читают
            // выход kmpresPrepare: без явной зависимости Gradle ругается на
            // неявную зависимость между задачами.
            project.tasks.matching { t ->
                val n = t.name.lowercase()
                n.contains("resource") || n.contains("cinterop") ||
                    n.contains("compile") || n.startsWith("link") || n.contains("jar")
            }.configureEach { t ->
                t.dependsOn(prepare)
            }
            //
            kmp.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                val spec = NativeTargetSpec.of(target.name)
                if (spec == null) {
                    project.logger.warn(
                        "kmpres: таргет ${target.name} не поддержан — плагин пишет ELF и Mach-O " +
                            "для 64-битных целей. Ресурсы в него не попадут.",
                    )
                }

                target.compilations.configureEach { compilation ->
                    if (compilation.name != KotlinCompilation.MAIN_COMPILATION_NAME) {
                        return@configureEach
                    }
                    compilation.cinterops.create("kmpres") { cinterop ->
                        cinterop.definitionFile.set(File(genDir, "cinterop/kmpres.def"))
                        cinterop.includeDirs(File(genDir, "cinterop"))
                    }

                    // Архив с ресурсами вкладываем в klib этого таргета.
                    //
                    // Именно `-include-binary`, а не `linkerOpts`: klib с
                    // вложенным архивом несёт ресурсы дальше — потребитель
                    // библиотеки получает их на линковке автоматически, без
                    // единой настройки у себя. `linkerOpts` же локальны и в
                    // опубликованный klib не попадают.
                    if (spec != null) {
                        val archive = File(genDir, "obj/${spec.id}/kmpres-$ns.a")
                        compilation.compileTaskProvider.configure { task ->
                            task.compilerOptions.freeCompilerArgs.addAll(
                                "-include-binary",
                                archive.absolutePath,
                            )
                        }
                    }
                }
                // Все задачи компиляции и линковки этого таргета зависят от
                // подготовки: без неё нет ни .def для cinterop, ни объектников.
                project.tasks.matching { t ->
                    t.name.contains("Kmpres", ignoreCase = false) &&
                        t.name.contains(target.name.replaceFirstChar { it.uppercase() })
                }.configureEach { t ->
                    if (t != prepare.get()) t.dependsOn(prepare)
                }

                project.tasks.matching { t ->
                    val n = t.name.lowercase()
                    n.contains(target.name.lowercase()) &&
                        (n.contains("cinterop") || n.contains("link") || n.contains("compile"))
                }.configureEach { t ->
                    t.dependsOn(prepare)
                }
            }
        }
    }

    /**
     * Транзитивное замыкание `dependsOn` от source set компиляции.
     *
     * Порядок не важен: набор может зависеть от любого числа других, граф может
     * быть сколь угодно хитрым — собираем всё, что реально видит компиляция.
     */
    private fun sourceSetClosure(root: KotlinSourceSet): Set<KotlinSourceSet> {
        val seen = LinkedHashSet<KotlinSourceSet>()
        val stack = ArrayDeque<KotlinSourceSet>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!seen.add(current)) continue
            current.dependsOn.forEach { dep -> stack.add(dep) }
        }
        return seen
    }

    /**
     * Подключить сгенерированный JVM-код и jar-ресурсы к source set.
     *
     * Код для Android генерируется в отдельный каталог (`kotlin/android`) с тем
     * же содержимым: один и тот же каталог исходников нельзя положить в два
     * source set. Ресурсы — можно, дерево файлов конфликта не даёт, поэтому
     * `jvm-resources` общий.
     */
    private fun wireJvmLikeSourceSet(
        kmp: KotlinMultiplatformExtension,
        sourceSetName: String,
        genDir: File,
        codeDir: String,
    ) {
        // configureEach, а не findByName: androidMain создаёт AGP в своём
        // afterEvaluate, и к нашему он может ещё не существовать.
        kmp.sourceSets.matching { it.name == sourceSetName }.configureEach { sourceSet ->
            sourceSet.kotlin.srcDir(File(genDir, codeDir))
            sourceSet.resources.srcDir(File(genDir, "jvm-resources"))
        }
    }

    /**
     * Отдать каталог ресурсов напрямую в AGP.
     *
     * KGP не всегда доносит resources androidMain до AGP: у application-модуля
     * каталог молча не попадает в APK (в библиотеке — попадает). Поэтому
     * регистрируем его в `android.sourceSets.main.resources` сами.
     *
     * Через рефлексию, чтобы не тянуть AGP в зависимости плагина: он есть только
     * у Android-модулей.
     */
    private fun wireAndroidJavaResources(project: Project, dir: File) {
        val android = project.extensions.findByName("android") ?: return
        runCatching {
            val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android)!!
            val main = sourceSets.javaClass.getMethod("getByName", String::class.java)
                .invoke(sourceSets, "main")!!
            val resources = main.javaClass.getMethod("getResources").invoke(main)!!
            resources.javaClass.getMethod("srcDir", Any::class.java).invoke(resources, dir)
        }.onFailure {
            project.logger.warn(
                "kmpres: не удалось добавить ресурсы в AGP (${it.message}); " +
                    "на Android их может не быть в APK",
            )
        }
    }

    /**
     * Имя проекта, пригодное как сегмент Kotlin-пакета.
     *
     * Gradle разрешает в именах дефисы и цифры в начале, а в пакете они
     * недопустимы: `my-lib` → `_my_lib`, `2fa` → `_2fa`.
     */
    private fun sanitizePackageSegment(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
            .joinToString("")
        return if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "_$cleaned"
    }
}