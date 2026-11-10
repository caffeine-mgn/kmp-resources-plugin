package pw.binom.resources.plugin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Контейнер ресурсов: все файлы в одном блоке с оглавлением.
 *
 * ## Зачем свой формат, а не tar
 *
 * tar прост, но у него нет хешей и он раздут выравниванием до 512 байт на
 * каждый файл. Для вшивания в бинарник нужен компактный формат с проверкой
 * целостности — свой контейнер занимает ровно столько, сколько данных.
 *
 * ## Формат
 *
 * ```
 * Заголовок (16 байт):
 *   magic    4  "KRES"
 *   version  4  int LE, сейчас 1
 *   count    4  int LE, число файлов
 *   reserved 4
 *
 * Оглавление (count записей по 40 байт):
 *   nameOffset 4   смещение имени в блоке имён
 *   nameLength 4
 *   dataOffset 8   откуда в файле начинаются данные (от начала контейнера)
 *   dataLength 8
 *   hash       16  MD5 содержимого (проверка целостности)
 *
 * Блок имён: имена подряд, UTF-8, без нулей.
 *
 * Блок данных: содержимое файлов подряд, выровнено на 8 байт.
 * ```
 *
 * ## Память
 *
 * Контейнер пишется потоком: файлы копируются в него кусками по 64 КБ, MD5
 * считается на лету, оглавление дописывается в конец случайной записью. Так
 * набор на сотни мегабайт не поднимается в heap сборочной JVM.
 *
 * ## Почему MD5
 *
 * Нужна не криптостойкость, а дешёвая проверка «данные не побились при
 * упаковке». MD5 для этого достаточен и быстр; для защиты от подмены хеш
 * тут не предназначен.
 */
object ResourceContainer {

    private const val MAGIC = "KRES"
    private const val VERSION = 1
    private const val HEADER_SIZE = 16
    private const val ENTRY_SIZE = 40

    /** Запись оглавления: что и где лежит в контейнере. */
    data class IndexEntry(
        val name: String,
        val size: Int,
        val md5: String,
        val offset: Int,
    )

    /**
     * Упаковать файлы в контейнер, не читая их в память.
     *
     * @param files пары «имя в контейнере → файл» в нужном порядке.
     * @return оглавление (то же, что потом прочитает `ContainerReader`).
     */
    fun packTo(out: File, files: List<Pair<String, File>>): List<IndexEntry> {
        val seen = mutableSetOf<String>()
        for ((name, _) in files) {
            require(seen.add(name)) { "дубликат имени ресурса: $name" }
        }

        val namesBytes = files.joinToString("") { it.first }.toByteArray(Charsets.UTF_8)
        val namesOffset = HEADER_SIZE + files.size * ENTRY_SIZE

        // Смещения считаем по размерам на диске: содержимое ещё не читали.
        var dataCursor = align(namesOffset + namesBytes.size, 8)
        val offsets = mutableListOf<Int>()
        for ((_, file) in files) {
            offsets += dataCursor
            dataCursor = align((dataCursor + file.length()).toInt(), 8)
        }
        val total = dataCursor

        val entries = ArrayList<IndexEntry>(files.size)
        out.parentFile?.mkdirs()
        FileChannel.open(
            out.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            // 1. Данные: копируем потоком, MD5 считаем на лету.
            files.forEachIndexed { i, (name, file) ->
                channel.position(offsets[i].toLong())
                val digest = MessageDigest.getInstance("MD5")
                val written = ObjectFile.copyInto(channel, file, digest)
                check(written == file.length()) {
                    "kmpres: файл ${file.absolutePath} изменился во время упаковки " +
                        "($written из ${file.length()})"
                }
                entries += IndexEntry(
                    name = name,
                    size = file.length().toInt(),
                    md5 = digest.digest().toHex(),
                    offset = offsets[i],
                )
            }
            channel.position(0L)

            // 2. Заголовок и оглавление — уже зная хеши.
            val head = ObjectFile.littleEndian(namesOffset + namesBytes.size)
            head.put(MAGIC.toByteArray(Charsets.US_ASCII))
            head.putInt(VERSION)
            head.putInt(files.size)
            head.putInt(0)

            var nameCursor = 0
            entries.forEach { entry ->
                val nameLen = entry.name.toByteArray(Charsets.UTF_8).size
                head.putInt(nameCursor)
                head.putInt(nameLen)
                head.putLong(entry.offset.toLong())
                head.putLong(entry.size.toLong())
                head.put(hexToBytes(entry.md5))
                nameCursor += nameLen
            }
            head.put(namesBytes)
            head.flip()
            while (head.hasRemaining()) channel.write(head)
        }

        check(total >= 0) { "kmpres: контейнер пуст" }
        return entries
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment
}
