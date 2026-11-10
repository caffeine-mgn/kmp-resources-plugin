package pw.binom.resources.plugin

/**
 * Генерация Kotlin-кода: оглавление ресурсов и API доступа.
 *
 * ## Почему всё генерируется, а не лежит в библиотеке
 *
 * Потребитель получает один плагин и ноль зависимостей времени выполнения.
 * Сгенерированный `Resources` — обычный Kotlin-код в его же проекте:
 * отлаживается, виден в IDE, не тянет транзитивных артефактов.
 *
 * ## Что генерируется
 *
 * | Файл | Назначение |
 * |---|---|
 * | `ResourceIndex.kt` | оглавление: имя, размер, md5 |
 * | `Resources.kt` | `expect object Resources` — общий API |
 * | `ContainerReader.kt` | разбор контейнера `KRES` (только натив) |
 * | `ResourcesNative.kt` | `actual` для нативных таргетов |
 * | `ResourcesJvm.kt` | `actual` для JVM: jar-записи через classloader |
 */
object CodeGen {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun md5Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Namespace модуля: пакет, приведённый к C-идентификатору.
     *
     * Нужен, чтобы два модуля с плагином могли оказаться в одном бинарнике.
     * Символы, cinterop-пакет и префикс jar-путей строятся из одного и того же
     * значения, поэтому совпадают по смыслу и уникальны настолько, насколько
     * уникален `packageName`.
     *
     * Точки и всё не-ASCII заменяются на `_`: `pw.binom.lib.res` →
     * `pw_binom_lib_res`.
     */
    fun namespaceOf(packageName: String): String = buildString {
        for (c in packageName) {
            append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '_')
        }
    }

    /**
     * Namespace с учётом ручного переопределения.
     *
     * Пустая строка — посчитать из [packageName]. Заданное руками значение не
     * санитизируем, а проверяем: молча исправленная опечатка в имени символа
     * потом ищется по всему бинарнику.
     */
    fun resolveNamespace(packageName: String, explicit: String): String {
        val value = explicit.trim()
        if (value.isEmpty()) return namespaceOf(packageName)
        require(value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
            "kmpres: namespace '$value' может состоять только из латиницы, цифр и '_' " +
                "(он попадает в имена C-символов)"
        }
        return value
    }

    /**
     * Оглавление ресурсов для JVM/Android.
     *
     * Нужно только там: ресурсы лежат записями в jar, и список известен на
     * этапе компиляции. Натив читает оглавление из своего контейнера.
     */
    fun resourcesIndexKt(
        packageName: String,
        entries: List<ResourceContainer.IndexEntry>,
    ): String = buildString {
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine("package $packageName")
        appendLine()
        appendLine("/**")
        appendLine(" * Оглавление ресурсов модуля (JVM/Android).")
        appendLine(" */")
        appendLine("object ResourceIndex {")
        appendLine()
        appendLine("    /** Все ресурсы: имя -> описание. */")
        appendLine("    val ALL: Map<String, ResourceEntry> = mapOf(")
        entries.forEach { entry ->
            appendLine(
                "        \"${esc(entry.name)}\" to ResourceEntry(" +
                    "\"${esc(entry.name)}\", ${entry.size}, \"${entry.md5}\"),",
            )
        }
        appendLine("    )")
        appendLine("}")
        appendLine()
        appendLine("/** Описание одного ресурса из оглавления. */")
        appendLine("data class ResourceEntry(")
        appendLine("    val name: String,")
        appendLine("    val size: Int,")
        appendLine("    val md5: String,")
        appendLine(")")
    }

    /** Общий API — `expect`. */
    fun resourcesCommonKt(packageName: String): String = buildString {
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine("package $packageName")
        appendLine()
        appendLine("/**")
        appendLine(" * Доступ к ресурсам, вшитым в бинарник.")
        appendLine(" *")
        appendLine(" * ```")
        appendLine(" * val html = Resources.readText(\"web/index.html\")")
        appendLine(" * ```")
        appendLine(" *")
        appendLine(" * На нативных платформах контейнер лежит внутри исполняемого файла,")
        appendLine(" * на JVM — обычными записями в jar. API один, реализация разная.")
        appendLine(" */")
        appendLine("expect object Resources {")
        appendLine()
        appendLine("    /** Прочитать ресурс как байты. `null`, если имени нет в оглавлении. */")
        appendLine("    fun read(name: String): ByteArray?")
        appendLine()
        appendLine("    /** Прочитать ресурс как UTF-8 текст. */")
        appendLine("    fun readText(name: String): String?")
        appendLine()
        appendLine("    /** Проверить наличие ресурса. */")
        appendLine("    fun exists(name: String): Boolean")
        appendLine()
        appendLine("    /** Оглавление: имя -> описание. */")
        appendLine("    fun list(): List<ResourceEntry>")
        appendLine()
        appendLine("    /**")
        appendLine("     * Размер ресурса в байтах, `-1` если его нет.")
        appendLine("     *")
        appendLine("     * На нативе отвечает оглавление — данные не читаются.")
        appendLine("     */")
        appendLine("    fun size(name: String): Long")
        appendLine()
        appendLine("    /**")
        appendLine("     * Прочитать кусок ресурса в готовый буфер, без промежуточных копий.")
        appendLine("     *")
        appendLine("     * Так большой ресурс можно выгрузить на диск или отдать в сеть")
        appendLine("     * блоками, не поднимая его в память целиком:")
        appendLine("     *")
        appendLine("     * ```")
        appendLine("     * val buffer = ByteArray(64 * 1024)")
        appendLine("     * var offset = 0L")
        appendLine("     * while (true) {")
        appendLine("     *     val n = Resources.read(\"big.dll\", buffer, offset = offset)")
        appendLine("     *     if (n <= 0) break")
        appendLine("     *     file.write(buffer, 0, n)")
        appendLine("     *     offset += n")
        appendLine("     * }")
        appendLine("     * ```")
        appendLine("     *")
        appendLine("     * @param dst куда писать.");
        appendLine("     * @param dstOffset смещение в [dst];")
        appendLine("     * @param offset смещение в ресурсе;")
        appendLine("     * @param length сколько байт читать, `-1` — до конца ресурса.")
        appendLine("     * @return сколько байт записано, `-1` если ресурса нет.")
        appendLine("     */")
        appendLine("    fun read(")
        appendLine("        name: String,")
        appendLine("        dst: ByteArray,")
        appendLine("        dstOffset: Int = 0,")
        appendLine("        offset: Long = 0,")
        appendLine("        length: Int = -1,")
        appendLine("    ): Int")
        appendLine()
        appendLine("    /**")
        appendLine("     * Пройти ресурс блоками, не держа его в памяти целиком.")
        appendLine("     *")
        appendLine("     * На нативе после каждого отданного блока прочитанные страницы")
        appendLine("     * возвращаются ядру, поэтому RSS остаётся на уровне буфера, а не")
        appendLine("     * размера ресурса. На JVM — обычное чтение потока из jar.")
        appendLine("     *")
        appendLine("     * ```")
        appendLine("     * Resources.forEachChunk(\"big.dll\") { chunk, size ->")
        appendLine("     *     file.write(chunk, 0, size)")
        appendLine("     * }")
        appendLine("     * ```")
        appendLine("     *")
        appendLine("     * @param bufferSize размер блока;")
        appendLine("     * @param block получает буфер и сколько в нём валидных байт;")
        appendLine("     *   данные валидны только внутри вызова.")
        appendLine("     */")
        appendLine("    fun forEachChunk(")
        appendLine("        name: String,")
        appendLine("        bufferSize: Int = 64 * 1024,")
        appendLine("        block: (chunk: ByteArray, size: Int) -> Unit,")
        appendLine("    )")
        appendLine("}")
    }

    /**
     * Разбор контейнера — общий код формата, но нужен только нативу:
     * на JVM контейнера нет, ресурсы лежат в jar обычными записями.
     *
     * Поэтому файл генерируется в `kotlin/native`: держать в common код,
     * который компилируется для JVM и никогда там не вызывается, незачем.
     */
    fun containerReaderKt(packageName: String): String = buildString {
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
        appendLine()
        appendLine("package $packageName")
        appendLine()
        appendLine("import kotlinx.cinterop.ByteVar")
        appendLine("import kotlinx.cinterop.CPointer")
        appendLine("import kotlinx.cinterop.addressOf")
        appendLine("import kotlinx.cinterop.convert")
        appendLine("import kotlinx.cinterop.get")
        appendLine("import kotlinx.cinterop.plus")
        appendLine("import kotlinx.cinterop.readBytes")
        appendLine("import kotlinx.cinterop.usePinned")
        appendLine("import platform.posix.memcpy")
        appendLine()
        appendLine("/**")
        appendLine(" * Разбор контейнера ресурсов (`KRES`) прямо из отображённой памяти.")
        appendLine(" *")
        appendLine(" * Контейнер не копируется в кучу: указатель ведёт в секцию бинарника,")
        appendLine(" * которую ядро подгружает страницами по требованию. Поэтому стоимость")
        appendLine(" * `exists()`/`list()` — только блок имён (килобайты), а `read()` —")
        appendLine(" * ровно прочитанный файл. Так большой ресурс (сотни мегабайт) не")
        appendLine(" * занимает оперативку, пока его реально не прочитали, и его страницы")
        appendLine(" * остаются file-backed: под давлением ОС их вытесняет.")
        appendLine(" *")
        appendLine(" * @param base начало контейнера в памяти, `null` — ресурсов нет.")
        appendLine(" * @param total размер контейнера.")
        appendLine(" */")
        appendLine("internal class ContainerReader(")
        appendLine("    private val base: CPointer<ByteVar>?,")
        appendLine("    private val total: Int,")
        appendLine(") {")
        appendLine()
        appendLine("    private companion object {")
        appendLine("        const val HEADER_SIZE = 16")
        appendLine("        const val ENTRY_SIZE = 40")
        appendLine("        const val HASH_SIZE = 16")
        appendLine("    }")
        appendLine()
        appendLine("    private class Entry(val offset: Int, val size: Int, val md5: String)")
        appendLine()
        appendLine("    /** Оглавление: имя -> запись. Читает только заголовок и блок имён. */")
        appendLine("    private val index: Map<String, Entry> by lazy { parse() }")
        appendLine()
        appendLine("    /** Есть ли такой ресурс. */")
        appendLine("    fun exists(name: String): Boolean = name in index")
        appendLine()
        appendLine("    /** Размер ресурса, -1 если его нет. */")
        appendLine("    fun size(name: String): Int = index[name]?.size ?: -1")
        appendLine()
        appendLine("    /** Смещение данных ресурса в контейнере, -1 если его нет. */")
        appendLine("    internal fun offsetOf(name: String): Int = index[name]?.offset ?: -1")
        appendLine()
        appendLine("    /** Оглавление в порядке упаковки. */")
        appendLine("    fun entries(): List<ResourceEntry> =")
        appendLine("        index.map { (name, e) -> ResourceEntry(name, e.size, e.md5) }")
        appendLine()
        appendLine("    /** Прочитать ресурс целиком. */")
        appendLine("    fun read(name: String): ByteArray? {")
        appendLine("        val entry = index[name] ?: return null")
        appendLine("        val ptr = base ?: return null")
        appendLine("        return (ptr + entry.offset)!!.readBytes(entry.size)")
        appendLine("    }")
        appendLine()
        appendLine("    /**")
        appendLine("     * Прочитать кусок ресурса в готовый буфер.")
        appendLine("     *")
        appendLine("     * Копирует только запрошенное: ресурс можно выгружать на диск или")
        appendLine("     * отдавать в сеть блоками, не поднимая его в память целиком.")
        appendLine("     *")
        appendLine("     * @return сколько байт скопировано, -1 если ресурса нет.")
        appendLine("     */")
        appendLine("    fun read(")
        appendLine("        name: String,")
        appendLine("        dst: ByteArray,")
        appendLine("        dstOffset: Int,")
        appendLine("        offset: Long,")
        appendLine("        length: Int,")
        appendLine("    ): Int {")
        appendLine("        val entry = index[name] ?: return -1")
        appendLine("        val ptr = base ?: return -1")
        appendLine("        if (offset < 0) return 0")
        appendLine("        if (offset >= entry.size) return 0")
        appendLine("        val start = offset.toInt()")
        appendLine("        require(dstOffset in 0..dst.size) { \"kmpres: dstOffset вне буфера: \$dstOffset\" }")
        appendLine("        val room = dst.size - dstOffset")
        appendLine("        // length < 0 — «сколько влезет»: так вызывающий читает блоками,")
        appendLine("        // не зная заранее, что поместится в буфер.")
        appendLine("        val want = if (length < 0) room else length")
        appendLine("        val count = minOf(want, room, entry.size - start)")
        appendLine("        if (count <= 0) return 0")
        appendLine("        require(dstOffset >= 0 && dstOffset + count <= dst.size) {")

        appendLine("        }")
        appendLine("        dst.usePinned { pinned ->")
        appendLine("            memcpy(")
        appendLine("                pinned.addressOf(dstOffset),")
        appendLine("                (ptr + entry.offset + start)!!,")
        appendLine("                count.convert(),")
        appendLine("            )")
        appendLine("        }")
        appendLine("        return count")
        appendLine("    }")
        appendLine()
        appendLine("    private fun parse(): Map<String, Entry> {")
        appendLine("        if (base == null || total < HEADER_SIZE) return emptyMap()")
        appendLine("        if (byteAt(0) != 'K'.code || byteAt(1) != 'R'.code ||")
        appendLine("            byteAt(2) != 'E'.code || byteAt(3) != 'S'.code")
        appendLine("        ) {")
        appendLine("            return emptyMap()")
        appendLine("        }")
        appendLine()
        appendLine("        val count = intAt(8)")
        appendLine("        val namesOffset = HEADER_SIZE + count * ENTRY_SIZE")
        appendLine("        if (count < 0 || namesOffset > total) return emptyMap()")
        appendLine("        val result = mutableMapOf<String, Entry>()")
        appendLine()
        appendLine("        for (i in 0 until count) {")
        appendLine("            val at = HEADER_SIZE + i * ENTRY_SIZE")
        appendLine("            val nameOffset = intAt(at)")
        appendLine("            val nameLength = intAt(at + 4)")
        appendLine("            val dataOffset = longAt(at + 8).toInt()")
        appendLine("            val dataLength = longAt(at + 16).toInt()")
        appendLine("            val name = stringAt(namesOffset + nameOffset, nameLength)")
        appendLine("            result[name] = Entry(dataOffset, dataLength, md5Hex(at + 24))")
        appendLine("        }")
        appendLine("        return result")
        appendLine("    }")
        appendLine()
        appendLine("    private fun byteAt(at: Int): Int =")
        appendLine("        (base!![at].toInt() and 0xFF)")
        appendLine()
        appendLine("    private fun intAt(at: Int): Int =")
        appendLine("        byteAt(at) or")
        appendLine("            (byteAt(at + 1) shl 8) or")
        appendLine("            (byteAt(at + 2) shl 16) or")
        appendLine("            (byteAt(at + 3) shl 24)")
        appendLine()
        appendLine("    private fun longAt(at: Int): Long {")
        appendLine("        var v = 0L")
        appendLine("        for (i in 7 downTo 0) v = (v shl 8) or byteAt(at + i).toLong()")
        appendLine("        return v")
        appendLine("    }")
        appendLine()
        appendLine("    private fun stringAt(at: Int, length: Int): String {")
        appendLine("        val bytes = ByteArray(length)")
        appendLine("        for (i in 0 until length) bytes[i] = base!![at + i]")
        appendLine("        return bytes.decodeToString()")
        appendLine("    }")
        appendLine()
        appendLine("    /** MD5 из оглавления в hex: 16 байт подряд. */")
        appendLine("    private fun md5Hex(at: Int): String {")
        appendLine("        val digits = \"0123456789abcdef\"")
        appendLine("        val sb = StringBuilder(HASH_SIZE * 2)")
        appendLine("        for (i in 0 until HASH_SIZE) {")
        appendLine("            val b = byteAt(at + i)")
        appendLine("            sb.append(digits[b shr 4]).append(digits[b and 0x0F])")
        appendLine("        }")
        appendLine("        return sb.toString()")
        appendLine("    }")
        appendLine("}")
    }

    /**
     * Освобождение прочитанных страниц — реализация зависит от платформы.
     *
     * POSIX (Linux, Android, Apple): `madvise(MADV_DONTNEED)`. На Apple этот
     * вызов объявлен no-op, но и вреда не делает — там страницы вытесняет ядро.
     * Windows (mingw): POSIX нет, поэтому no-op: страницы также file-backed и
     * вытесняются системой.
     */
    fun pageReleaseKt(packageName: String, posix: Boolean): String = buildString {
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
        appendLine()
        appendLine("package $packageName")
        appendLine()
        appendLine("import kotlinx.cinterop.ByteVar")
        appendLine("import kotlinx.cinterop.CPointer")
        appendLine("import kotlinx.cinterop.convert")
        appendLine("import kotlinx.cinterop.plus")
        appendLine("import kotlinx.cinterop.rawValue")
        if (posix) {
            appendLine("import platform.posix.MADV_DONTNEED")
            appendLine("import platform.posix._SC_PAGESIZE")
            appendLine("import platform.posix.madvise")
            appendLine("import platform.posix.sysconf")
        }
        appendLine()
        appendLine("/**")
        appendLine(" * Вернуть ядру страницы контейнера в диапазоне [from]..[to] (смещения")
        appendLine(" * внутри контейнера, в байтах).")
        appendLine(" */")
        appendLine("internal fun releaseContainerPages(")
        appendLine("    data: CPointer<ByteVar>?,")
        appendLine("    misalign: Long,")
        appendLine("    pageSize: Long,")
        appendLine("    from: Int,")
        appendLine("    to: Int,")
        appendLine(") {")
        if (posix) {
            appendLine("    if (data == null || to <= from) return")
            appendLine("    val firstPage = from / pageSize * pageSize")
            appendLine("    val lastPage = to / pageSize * pageSize")
            appendLine("    if (lastPage <= firstPage) return")
            appendLine("    madvise(")
            appendLine("        (data + (firstPage - misalign).toInt())!!,")
            appendLine("        (lastPage - firstPage).convert(),")
            appendLine("        MADV_DONTNEED,")
            appendLine("    )")
        } else {
            appendLine("    // Windows: POSIX-советов нет, страницы вытесняет система.")
        }
        appendLine("}")
        appendLine()
        appendLine("/** Размер страницы: для выравнивания адреса под madvise. */")
        appendLine("internal fun containerPageSize(): Long =")
        if (posix) {
            appendLine("    sysconf(_SC_PAGESIZE).toLong().let { if (it > 0L) it else 4096L }")
        } else {
            appendLine("    4096L")
        }
    }

    /**
     * Реализация для нативных таргетов.
     *
     * @param ns namespace модуля (см. [namespaceOf]) — из него собраны имена
     *   C-символов и cinterop-пакет этого модуля.
     */
    fun resourcesNativeKt(packageName: String, ns: String): String = buildString {
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        // @file:OptIn обязан идти ДО package — иначе «imports are only allowed
        // in the beginning of file» и opt-in не применяется.
        appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
        appendLine()
        appendLine("package $packageName")
        appendLine()
        appendLine("import kotlinx.cinterop.ByteVar")
        appendLine("import kotlinx.cinterop.CPointer")
        // Cinterop-пакет свой у каждого модуля, иначе два модуля с плагином
        // дают конфликт объявлений у потребителя.
        appendLine("import $packageName.kmpres.kmpres_${ns}_data")
        appendLine("import $packageName.kmpres.kmpres_${ns}_size")
        appendLine("import kotlinx.cinterop.rawValue")
        appendLine()
        appendLine("/**")
        appendLine(" * Нативная реализация: контейнер лежит в секции бинарника и")
        appendLine(" * отображён в память. Указатель ведёт прямо туда — контейнер не")
        appendLine(" * копируется, страницы подгружаются по мере чтения.")
        appendLine(" */")
        appendLine("actual object Resources {")
        appendLine()
        appendLine("    internal val reader: ContainerReader by lazy {")
        appendLine("        ContainerReader(NativeContainer.data(), NativeContainer.size())")
        appendLine("    }")
        appendLine()
        appendLine("    actual fun read(name: String): ByteArray? = reader.read(name)")
        appendLine()
        appendLine("    actual fun readText(name: String): String? =")
        appendLine("        read(name)?.decodeToString()")
        appendLine()
        appendLine("    actual fun exists(name: String): Boolean = reader.exists(name)")
        appendLine()
        appendLine("    actual fun list(): List<ResourceEntry> = reader.entries()")
        appendLine()
        appendLine("    actual fun size(name: String): Long = reader.size(name).toLong()")
        appendLine()
        appendLine("    actual fun read(")
        appendLine("        name: String,")
        appendLine("        dst: ByteArray,")
        appendLine("        dstOffset: Int,")
        appendLine("        offset: Long,")
        appendLine("        length: Int,")
        appendLine("    ): Int = reader.read(name, dst, dstOffset, offset, length)")
        appendLine()
        appendLine()
        appendLine("    actual fun forEachChunk(")
        appendLine("        name: String,")
        appendLine("        bufferSize: Int,")
        appendLine("        block: (chunk: ByteArray, size: Int) -> Unit,")
        appendLine("    ) {")
        appendLine("        forEachChunkImpl(name, bufferSize, block)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/**")
        appendLine(" * Пройти ресурс блоками, не поднимая его в память целиком.")
        appendLine(" *")
        appendLine(" * После каждого отданного блока прочитанные страницы отдаются ядру")
        appendLine(" * (`madvise(MADV_DONTNEED)`), поэтому RSS остаётся на уровне буфера,")
        appendLine(" * а не размера ресурса — как при обычном чтении файла.")
        appendLine(" *")
        appendLine(" * ```")
        appendLine(" * Resources.forEachChunk(\"big.dll\") { chunk, size ->")
        appendLine(" *     file.write(chunk, 0, size)")
        appendLine(" * }")
        appendLine(" * ```")
        appendLine(" *")
        appendLine(" * @param bufferSize размер блока;")
        appendLine(" * @param releasePages отпускать прочитанные страницы (Linux/Android/macOS);")
        appendLine(" * @param block вызывается на каждый блок, данные валидны только внутри вызова.")
        appendLine(" */")
        appendLine("private fun Resources.forEachChunkImpl(")
        appendLine("    name: String,")
        appendLine("    bufferSize: Int,")
        appendLine("    block: (chunk: ByteArray, size: Int) -> Unit,")
        appendLine(") {")
        appendLine("    val releasePages = true")
        appendLine("    val total = size(name)")
        appendLine("    if (total <= 0L) return")
        appendLine("    val data = NativeContainer.data()")
        appendLine("    val dataOffset = NativeContainer.reader().offsetOf(name)")
        appendLine("    val pageSize = containerPageSize()")
        appendLine("    // База контейнера не выровнена на страницу: madvise требует выравнивания,")
        appendLine("    // поэтому считаем смещение от страницы, в которой лежит начало блока.")
        appendLine("    val misalign = if (data != null) {")
        appendLine("        data.rawValue.toLong() and (pageSize - 1L)")
        appendLine("    } else {")
        appendLine("        0L")
        appendLine("    }")
        appendLine()
        appendLine("    val buffer = ByteArray(bufferSize)")
        appendLine("    var offset = 0L")
        appendLine("    while (offset < total) {")
        appendLine("        val n = read(name, buffer, 0, offset, bufferSize)")
        appendLine("        if (n <= 0) break")
        appendLine("        block(buffer, n)")
        appendLine("        offset += n")
        appendLine("        if (releasePages && dataOffset >= 0) {")
        appendLine("            // Всё, что уже отдано, ядру больше не нужно.")
        appendLine("            releaseContainerPages(")
        appendLine("                data, misalign, pageSize,")
        appendLine("                (dataOffset + offset - n).toInt(),")
        appendLine("                (dataOffset + offset).toInt(),")
        appendLine("            )")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** Границы контейнера в бинарнике. */")
        appendLine("internal object NativeContainer {")
        appendLine("    /** Размер контейнера в байтах. */")
        appendLine("    fun size(): Int = kmpres_${ns}_size().toInt()")
        appendLine()
        appendLine("    /** Начало контейнера; `null`, если данных нет. */")
        appendLine("    fun data(): CPointer<ByteVar>? = kmpres_${ns}_data()")
        appendLine()
        appendLine("    /** Читатель оглавления — общий с `Resources`. */")
        appendLine("    fun reader(): ContainerReader = Resources.reader")
        appendLine("}")
    }

    /**
     * Реализация для JVM.
     *
     * Контейнера на JVM нет: каждый ресурс лежит в jar обычной записью
     * `kmpres/<пакет>/<имя>`. Jar и так zip, поэтому класслоадер умеет
     * отдавать отдельный ресурс потоком, не поднимая в память весь набор.
     *
     * Каталог с пакетом — namespace модуля: два модуля с одинаковым
     * `web/index.html` не должны перекрывать друг друга в classpath.
     */
    fun resourcesJvmKt(packageName: String): String = buildString {
        val prefix = "kmpres/" + packageName.replace('.', '/') + "/"
        appendLine("// СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine("package $packageName")
        appendLine()
        appendLine("import java.io.InputStream")
        appendLine()
        appendLine("/**")
        appendLine(" * JVM-реализация: ресурсы лежат в jar обычными записями")
        appendLine(" * под префиксом `$prefix`.")
        appendLine(" */")
        appendLine("actual object Resources {")
        appendLine()
        appendLine("    actual fun read(name: String): ByteArray? {")
        appendLine("        if (name !in ResourceIndex.ALL) return null")
        appendLine("        return openStream(name)?.use { it.readBytes() }")
        appendLine("    }")
        appendLine()
        appendLine("    actual fun readText(name: String): String? =")
        appendLine("        read(name)?.decodeToString()")
        appendLine()
        appendLine("    actual fun exists(name: String): Boolean = name in ResourceIndex.ALL")
        appendLine()
        appendLine("    actual fun list(): List<ResourceEntry> = ResourceIndex.ALL.values.toList()")
        appendLine()
        appendLine("    actual fun size(name: String): Long =")
        appendLine("        ResourceIndex.ALL[name]?.size?.toLong() ?: -1L")
        appendLine()
        appendLine("    actual fun read(")
        appendLine("        name: String,")
        appendLine("        dst: ByteArray,")
        appendLine("        dstOffset: Int,")
        appendLine("        offset: Long,")
        appendLine("        length: Int,")
        appendLine("    ): Int {")
        appendLine("        val entry = ResourceIndex.ALL[name] ?: return -1")
        appendLine("        val input = openStream(name) ?: return -1")
        appendLine("        return input.use {")
        appendLine("            // Пропускаем начало: ресурс не поднимается в память целиком.")
        appendLine("            var skipped = 0L")
        appendLine("            while (skipped < offset) {")
        appendLine("                val n = it.skip(offset - skipped)")
        appendLine("                if (n <= 0L) break")
        appendLine("                skipped += n")
        appendLine("            }")
        appendLine("            if (skipped < offset) return@use 0")
        appendLine("            // length < 0 — «сколько влезет»: чтение блоками.")
        appendLine("            val room = dst.size - dstOffset")
        appendLine("            val want = if (length < 0) room else length")
        appendLine("            val count = minOf(want, room, entry.size - skipped.toInt())")
        appendLine("            var total = 0")
        appendLine("            while (total < count) {")
        appendLine("                val n = it.read(dst, dstOffset + total, count - total)")
        appendLine("                if (n < 0) break")
        appendLine("                total += n")
        appendLine("            }")
        appendLine("            total")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    actual fun forEachChunk(")
        appendLine("        name: String,")
        appendLine("        bufferSize: Int,")
        appendLine("        block: (chunk: ByteArray, size: Int) -> Unit,")
        appendLine("    ) {")
        appendLine("        if (size(name) <= 0L) return")
        appendLine("        val buffer = ByteArray(bufferSize)")
        appendLine("        var offset = 0L")
        appendLine("        while (true) {")
        appendLine("            val n = read(name, buffer, 0, offset, bufferSize)")
        appendLine("            if (n <= 0) break")
        appendLine("            block(buffer, n)")
        appendLine("            offset += n")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** Каталог, в который плагин складывает ресурсы внутри jar. */")
        appendLine("private const val RESOURCES_PREFIX = \"$prefix\"")
        appendLine()
        appendLine("/**")
        appendLine(" * Поток ресурса из classpath. `null`, если записи нет в jar.")
        appendLine(" *")
        appendLine(" * Открывается заново на каждое чтение и закрывается вызывающим:")
        appendLine(" * поток — не общий ресурс, параллельные чтения друг другу не мешают.")
        appendLine(" */")
        appendLine("private fun openStream(name: String): InputStream? =")
        appendLine("    Resources::class.java.classLoader")
        appendLine("        ?.getResourceAsStream(RESOURCES_PREFIX + name)")
    }

    /**
     * C-обёртка: связывает объектник ресурсов с cinterop.
     *
     * Имена символов и функций содержат namespace модуля: два модуля с
     * плагином в одном бинарнике иначе дали бы duplicate definition на линковке.
     */
    fun wrapperC(ns: String): String = buildString {
        appendLine("/* СГЕНЕРИРОВАНО плагином kmp-resources. Не править руками.")
        appendLine(" *")
        appendLine(" * Символы _binary_kmpres_${ns}_start/_end создаёт объектник, который")
        appendLine(" * плагин пишет сам (см. ElfWriter) — без objcopy.")
        appendLine(" */")
        appendLine("extern char _binary_kmpres_${ns}_start[];")
        appendLine("extern char _binary_kmpres_${ns}_end[];")
        appendLine()
        appendLine("/* const char*, а не unsigned char*: cinterop превращает")
        appendLine(" * unsigned char* в CPointer<UByteVar>, несовместимый с ByteVar. */")
        appendLine("const char* kmpres_${ns}_data(void) { return _binary_kmpres_${ns}_start; }")
        appendLine("unsigned long kmpres_${ns}_size(void) {")
        appendLine("    return (unsigned long)(_binary_kmpres_${ns}_end - _binary_kmpres_${ns}_start);")
        appendLine("}")
    }

    fun wrapperH(ns: String): String = buildString {
        appendLine("#ifndef KMPRES_WRAPPER_H")
        appendLine("#define KMPRES_WRAPPER_H")
        appendLine()
        appendLine("const char* kmpres_${ns}_data(void);")
        appendLine("unsigned long kmpres_${ns}_size(void);")
        appendLine()
        appendLine("#endif")
    }

    /**
     * `def` для cinterop.
     *
     * `package` обязателен: без него cinterop кладёт обёртку в пакет по имени
     * cinterop (`kmpres`), и у потребителя два модуля с плагином получают
     * одинаковые объявления `kmpres.kmpres_data` — конфликт при компиляции.
     */
    fun wrapperDef(packageName: String): String =
        "headers = kmpres_wrapper.h\npackage = $packageName.kmpres\n"
}
