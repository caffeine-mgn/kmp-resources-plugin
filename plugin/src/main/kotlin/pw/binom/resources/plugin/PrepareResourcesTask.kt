package pw.binom.resources.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Упаковка ресурсов и генерация всего, что нужно линкеру и Kotlin.
 *
 * Выход в [outputDir]:
 * ```
 * resources.kres                    — контейнер (для нативных таргетов)
 * obj/<arch>/resources.o            — ELF-объектник с контейнером
 * obj/<arch>/wrapper.o              — ELF-объектник с C-обёрткой
 * obj/<arch>/kmpres-<ns>.a          — статический архив для klib
 * cinterop/kmpres.def               — def для cinterop
 * cinterop/kmpres_wrapper.h
 * cinterop/kmpres_wrapper.c
 * kotlin/common/ResourceIndex.kt
 * kotlin/native/                     — actual и разбор контейнера
 * kotlin/jvm/                        — actual для JVM
 * kotlin/android/                    — тот же actual для Android
 * jvm-resources/kmpres/<pkg>/<имя>  — ресурсы для jar, обычными записями
 * ```
 *
 * Архив на каждую архитектуру нужен, потому что `.a` попадает в klib
 * конкретного таргета (см. `-include-binary`), а объектник содержит
 * `e_machine` своей архитектуры.
 */
@CacheableTask
abstract class PrepareResourcesTask : DefaultTask() {

    /** Каталоги с ресурсами. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: org.gradle.api.file.ConfigurableFileCollection

    /** Пакет для оглавления. */
    @get:Input
    abstract val packageName: Property<String>

    /** Namespace символов; пусто — считается из [packageName]. */
    @get:Input
    abstract val namespace: Property<String>

    /**
     * Имя секции с данными, пусто — по умолчанию для формата.
     *
     * ELF/COFF — просто имя (`.rodata`); Mach-O — `SEG,SECT`
     * (`__TEXT,__const`).
     */
    @get:Input
    abstract val sectionName: Property<String>

    /** Секция только для чтения: без флага записи (ELF/COFF). */
    @get:Input
    abstract val readOnly: Property<Boolean>

    /** Имена нативных таргетов, под которые нужны объектники и архивы. */
    @get:Input
    abstract val targets: ListProperty<String>

    /**
     * Все каталоги, которые читает задача — только для отслеживания изменений
     * (up-to-date). В сборку попадают наборы из [targetResourceDirs] плюс
     * [sourceDirs], а не этот союз.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trackedDirs: org.gradle.api.file.ConfigurableFileCollection

    /**
     * Каталоги ресурсов по таргетам: имя таргета -> пути относительно [projectDir].
     *
     * У таргета свой набор: `linuxX64` видит `commonMain` + `linuxMain` +
     * `linuxX64Main`, `macosArm64` — свои. Поэтому и контейнер у каждого
     * таргета свой.
     */
    @get:Input
    abstract val targetResourceDirs: MapProperty<String, List<String>>

    /** Корень проекта — чтобы разворачивать относительные пути из карты. */
    @get:Internal
    abstract val projectDir: DirectoryProperty

    /** Куда сложить результат. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        val pkg = packageName.get()
        val ns = CodeGen.resolveNamespace(pkg, namespace.get())
        val specs = targets.get().mapNotNull { NativeTargetSpec.of(it) }
        val extraDirs = sourceDirs.files.toList()
        val perTarget = targetResourceDirs.get()

        fun filesFor(targetName: String): List<Pair<String, File>> {
            val dirs = perTarget[targetName].orEmpty()
                .map { File(projectDir.get().asFile, it) }
            return collectFiles((dirs + extraDirs).distinctBy { it.absoluteFile.normalize() })
        }

        // --- обёртка: C, заголовок и def. От таргета не зависит.
        val wrapperC = File(out, "cinterop/kmpres_wrapper.c")
        wrapperC.parentFile.mkdirs()
        wrapperC.writeText(CodeGen.wrapperC(ns))

        File(out, "cinterop/kmpres_wrapper.h").writeText(CodeGen.wrapperH(ns))
        File(out, "cinterop/kmpres.def").writeText(CodeGen.wrapperDef(pkg))

        // --- контейнер и архив на каждый нативный таргет.
        //
        // Набор ресурсов у таргетов разный, поэтому и контейнер свой. Архив
        // вкладывается в klib (`-include-binary`) и линкуется уже в приложении;
        // собственные бинарники модуля тоже берут его из своего klib.
        for (spec in specs) {
            val files = filesFor(spec.name)
            val dir = File(out, "obj/${spec.id}")
            dir.mkdirs()
            // Контейнер пишется потоком: файлы не поднимаются в heap.
            val container = File(dir, "resources.kres")
            val entries = ResourceContainer.packTo(container, files)
            logger.lifecycle(
                "kmpres: ${spec.name} — ${files.size} файлов, контейнер ${container.length()} байт",
            )
            writeTargetArtifacts(out, container, container.length(), wrapperC, ns, spec)
        }

        // --- Kotlin.
        //
        // Оглавление генерируется только для JVM/Android: там ресурсы лежат
        // записями в jar, и список нужен на этапе компиляции. Нативу оно не
        // нужно — он читает оглавление из своего контейнера в рантайме.
        val jvmKey = listOf("jvm", "android").firstOrNull { perTarget.containsKey(it) }
        val jvmFiles = jvmKey?.let { filesFor(it) } ?: emptyList()
        // Индекс нужен только JVM/Android; хеши считаем потоком.
        val jvmEntries = jvmFiles.map { (name, file) ->
            ResourceContainer.IndexEntry(
                name = name,
                size = file.length().toInt(),
                md5 = ObjectFile.md5(file).joinToString("") { "%02x".format(it) },
                offset = 0,
            )
        }

        writeKt(out, "kotlin/common/ResourceIndex.kt", CodeGen.resourcesIndexKt(pkg, jvmEntries))
        writeKt(out, "kotlin/common/Resources.kt", CodeGen.resourcesCommonKt(pkg))
        writeKt(out, "kotlin/native/ContainerReader.kt", CodeGen.containerReaderKt(pkg))
        writeKt(out, "kotlin/native/ResourcesNative.kt", CodeGen.resourcesNativeKt(pkg, ns))
        // Освобождение страниц: POSIX и Windows-заглушка — по файлу на вариант,
        // плагин подключит нужный таргету.
        writeKt(out, "kotlin/pages/posix/PageRelease.kt", CodeGen.pageReleaseKt(pkg, posix = true))
        writeKt(out, "kotlin/pages/windows/PageRelease.kt", CodeGen.pageReleaseKt(pkg, posix = false))
        writeKt(out, "kotlin/jvm/ResourcesJvm.kt", CodeGen.resourcesJvmKt(pkg))
        // Android: тот же класслоадерный код, но в отдельном каталоге — один
        // каталог исходников нельзя положить в два source set.
        writeKt(out, "kotlin/android/ResourcesJvm.kt", CodeGen.resourcesJvmKt(pkg))

        // --- ресурсы для JVM: каждый файл отдельной записью в jar, без
        // контейнера. Jar — уже zip с произвольным доступом и ленивой
        // подгрузкой, а список ресурсов известен из сгенерированного индекса.
        writeJvmResources(out, pkg, jvmFiles)

        logger.lifecycle("kmpres: готово — ${out.absolutePath}")
    }

    /**
     * Объектник с контейнером, объектник обёртки и архив для одного таргета.
     *
     * Обёртка компилируется тем же clang, что приезжает с Kotlin/Native —
     * системный компилятор не нужен. `--target` позволяет собрать её для
     * чужой архитектуры: в файле нет ни одного `#include`, поэтому sysroot
     * не требуется (проверено и для ELF, и для Apple-триплов).
     */
    private fun writeTargetArtifacts(
        out: File,
        container: File,
        containerSize: Long,
        wrapperC: File,
        ns: String,
        spec: NativeTargetSpec,
    ) {
        val dir = File(out, "obj/${spec.id}")
        dir.mkdirs()

        val startSymbol = "_binary_kmpres_${ns}_start"
        val endSymbol = "_binary_kmpres_${ns}_end"
        val sizeSymbol = "_binary_kmpres_${ns}_size"

        val resObj = File(dir, "resources.o")
        val customSection = sectionName.get().trim()
        val readOnly = readOnly.get()
        when (spec.format) {
            NativeFormat.ELF -> ElfWriter.writeTo(
                out = resObj,
                data = container,
                dataSize = containerSize,
                arch = spec.arch,
                startSymbol = startSymbol,
                endSymbol = endSymbol,
                sizeSymbol = sizeSymbol,
                sectionName = customSection.ifEmpty {
                    if (readOnly) ".rodata" else ".data"
                },
                writable = !readOnly,
            )

            NativeFormat.MACHO -> {
                val (segment, section) = when {
                    customSection.isNotEmpty() -> parseMachoSection(customSection)
                    readOnly -> "__TEXT" to "__const"
                    else -> "__DATA" to "__data"
                }
                MachWriter.writeTo(
                    out = resObj,
                    data = container,
                    dataSize = containerSize,
                    arch = spec.arch,
                    startSymbol = startSymbol,
                    endSymbol = endSymbol,
                    sizeSymbol = sizeSymbol,
                    segment = segment,
                    section = section,
                )
            }

            NativeFormat.COFF -> CoffWriter.writeTo(
                out = resObj,
                data = container,
                dataSize = containerSize,
                arch = spec.arch,
                startSymbol = startSymbol,
                endSymbol = endSymbol,
                sizeSymbol = sizeSymbol,
                sectionName = customSection.ifEmpty {
                    if (readOnly) ".rdata" else ".data"
                },
                writable = !readOnly,
            )
        }

        val wrapperObj = File(dir, "wrapper.o")
        val clang = ObjectFile.findKonanTool("clang")
            ?: throw IllegalStateException(
                "kmp-resources: не найден clang в ~/.konan/dependencies. " +
                    "Он ставится вместе с Kotlin/Native — проверьте, что нативная " +
                    "сборка вообще работает.",
            )
        run(
            clang, "-c", "-O2", "-fPIC",
            "--target=${spec.clangTarget}",
            "-o", wrapperObj.absolutePath,
            wrapperC.absolutePath,
        )

        // Архив: в него попадают оба объектника. Имя содержит namespace, чтобы
        // в klib (и в будущем поиске по зависимостям) было видно, чей это архив.
        val archive = File(dir, "kmpres-$ns.a")
        val ar = ObjectFile.findKonanTool("llvm-ar")
            ?: throw IllegalStateException("kmp-resources: не найден llvm-ar в ~/.konan/dependencies")
        // Пересоздаём: `r` в llvm-ar обновляет, но не удаляет старые члены.
        archive.delete()
        run(ar, "rcs", archive.absolutePath, resObj.absolutePath, wrapperObj.absolutePath)

        logger.lifecycle("kmpres: ${spec.name} — архив ${archive.length()} байт")
    }

    /**
     * Разобрать имя секции Mach-O вида `SEG,SECT`.
     *
     * Проверяем то, что проверил бы линкер: две части, каждая не длиннее 16
     * байт. Иначе ошибка приходит из ld64 в виде невнятного сообщения.
     */
    private fun parseMachoSection(value: String): Pair<String, String> {
        val parts = value.split(',')
        require(parts.size == 2) {
            "kmpres: sectionName для Apple-таргетов задаётся как `SEGMENT,SECTION` " +
                "(например, `__TEXT,__const`), получено: '$value'"
        }
        val segment = parts[0].trim()
        val section = parts[1].trim()
        require(segment.isNotEmpty() && section.isNotEmpty()) {
            "kmpres: пустой сегмент или секция в sectionName: '$value'"
        }
        require(segment.toByteArray(Charsets.US_ASCII).size <= 16) {
            "kmpres: имя сегмента '$segment' длиннее 16 байт — столько в Mach-O"
        }
        require(section.toByteArray(Charsets.US_ASCII).size <= 16) {
            "kmpres: имя секции '$section' длиннее 16 байт — столько в Mach-O"
        }
        return segment to section
    }

    /** Запустить внешнюю команду и упасть с её выводом при ошибке. */
    private fun run(vararg command: String) {
        val proc = ProcessBuilder(*command).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) {
            "kmp-resources: команда завершилась с кодом $code: ${command.joinToString(" ")}\n$log"
        }
    }

    /**
     * Разложить ресурсы по jar-путям `kmpres/<пакет>/<имя>`.
     *
     * `kmpres/` — своё пространство имён (без него имена попали бы в корень
     * jar), `<пакет>` — namespace модуля: два модуля с одинаковым
     * `web/index.html` не должны перекрывать друг друга в classpath.
     */
    private fun writeJvmResources(out: File, packageName: String, files: List<Pair<String, File>>) {
        val root = File(out, "jvm-resources")

        // Каталог пересоздаём целиком: иначе удалённый из проекта ресурс
        // остался бы в jar с прошлой сборки — Gradle чистит выходные каталоги
        // не для каждой задачи. Удаляем весь `jvm-resources`, а не только
        // каталог текущего пакета: при смене `packageName` старый префикс
        // иначе остаётся в jar.
        root.deleteRecursively()
        val pkgDir = File(root, "kmpres/" + packageName.replace('.', '/'))
        pkgDir.mkdirs()
        files.forEach { (name, file) ->
            val target = File(pkgDir, name)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
        }

        logger.lifecycle("kmpres: ${files.size} файлов разложены в jar-ресурсы")
    }

    private fun writeKt(out: java.io.File, relPath: String, content: String) {
        // Файл кладём в подкаталог, соответствующий пакету: Gradle ждёт
        // соответствия пути и пакета при компиляции.
        val file = java.io.File(out, relPath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** Собрать файлы указанных каталогов: относительный путь -> файл. */
    private fun collectFiles(dirs: Collection<File>): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        for (root in dirs) {
            if (!root.exists()) continue
            if (root.isFile) {
                result += root.name to root
                continue
            }
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    result += rel to file
                }
        }
        return result.sortedBy { it.first }
    }


    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
