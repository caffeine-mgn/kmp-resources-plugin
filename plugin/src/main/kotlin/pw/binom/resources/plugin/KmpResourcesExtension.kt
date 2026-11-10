package pw.binom.resources.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File

/**
 * Настройки плагина `kmp-resources`.
 *
 * ```kotlin
 * resources {
 *     packageName = "pw.binom.tgservice.resources"
 *     from("src/commonMain/resources/web")
 * }
 * ```
 */
abstract class KmpResourcesExtension {

    /** Пакет для сгенерированного кода ресурсов. */
    abstract val packageName: Property<String>

    /**
     * Namespace модуля для C-символов: `_binary_kmpres_<namespace>_start` и
     * обёртки `kmpres_<namespace>_data`.
     *
     * Пусто — считается из [packageName] (точки → `_`). Задавать руками нужно
     * редко: если хочется короткое читаемое имя в `nm` или если два модуля
     * зачем-то делят один пакет. Обязан быть уникален в пределах приложения,
     * иначе линковка упадёт на duplicate symbol.
     */
    abstract val namespace: Property<String>

    /**
     * Имя секции с данными. Пусто — значение по умолчанию для формата:
     * `.data` (ELF, COFF), `__DATA,__data` (Mach-O).
     *
     * Нужно редко. Для Apple-таргетов пишется как `SEGMENT,SECTION`, например
     * `__TEXT,__const`. Для COFF имя не длиннее 8 байт.
     */
    abstract val sectionName: Property<String>

    /**
     * Положить данные в секцию только для чтения.
     *
     * Ресурсы неизменяемы, поэтому это разумное умолчание для больших наборов:
     * страницы не пишутся, шарятся между процессами, случайная запись падает
     * сразу. Подбирает имена сама: `.rodata` (ELF), `.rdata` (COFF),
     * `__TEXT,__const` (Mach-O).
     *
     * Несовместимо с `sectionName`, если то задаёт записываемое имя — флаг
     * записи определяется именно этим свойством.
     */
    abstract val readOnly: Property<Boolean>

    /** Каталоги с ресурсами. */
    abstract val sourceDirs: ListProperty<File>

    /**
     * Добавить каталоги с ресурсами.
     *
     * Принимает что угодно, что можно превратить в путь: строку, `File`,
     * `Directory`. ListProperty<File> в этой версии Gradle принимает только
     * готовые File, поэтому приводим здесь.
     */
    fun from(vararg dirs: Any) {
        sourceDirs.addAll(
            dirs.map { dir ->
                when (dir) {
                    is File -> dir
                    is org.gradle.api.file.Directory -> dir.asFile
                    else -> File(dir.toString())
                }
            },
        )
    }

    init {
        sourceDirs.convention(emptyList())
        namespace.convention("")
        sectionName.convention("")
        readOnly.convention(false)
    }
}
