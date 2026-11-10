package pw.binom.resources.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись Mach-O-объектника (`MH_OBJECT`) с ресурсами — аналог [ElfWriter] для
 * Apple-платформ.
 *
 * ## Зачем свой писатель
 *
 * `objcopy -I binary` есть в Xcode, но он не умеет ELF и не является частью
 * поставки Kotlin/Native. Плагин пишет объектник сам, поэтому одна и та же
 * схема работает на обеих платформах.
 *
 * ## Структура файла
 *
 * ```
 * [0]  header (32)
 * [32] LC_SEGMENT_64 с одной секцией __DATA,__data (152)
 *      LC_SYMTAB (24)
 *      LC_DYSYMTAB (80)
 *      данные блоба, выровнены на 16
 *      nlist_64 × 3
 *      строки символов
 * ```
 *
 * ## Символы
 *
 * В Mach-O у C-символов в таблице имён стоит лишний `_`. Наш C-символ
 * `_binary_kmpres_x_start` хранится как `__binary_kmpres_x_start` — писатель
 * добавляет префикс сам.
 *
 * ## Память
 *
 * Блоб не поднимается в кучу: [writeTo] отдаёт его потоком из файла контейнера.
 */
object MachWriter {

    private const val MH_MAGIC_64 = 0xfeedfacf.toInt()
    private const val MH_OBJECT = 1

    private const val LC_SEGMENT_64 = 0x19
    private const val LC_SYMTAB = 0x2
    private const val LC_DYSYMTAB = 0xb

    private const val N_EXT = 0x01
    private const val N_ABS = 0x02
    private const val N_SECT = 0x0e

    /** Размер заголовка `mach_header_64`. */
    private const val HEADER_SIZE = 32

    /** `segment_command_64` + один `section_64`. */
    private const val SEGMENT_CMD_SIZE = 72 + 80

    private const val SYMTAB_CMD_SIZE = 24
    private const val DYSYMTAB_CMD_SIZE = 80
    private const val NLIST_SIZE = 16

    private const val NCMDS = 3

    /**
     * Собрать Mach-O-объектник с блобом в секции `__DATA,__data`.
     *
     * @param data файл контейнера; его содержимое льётся в секцию потоком.
     */
    fun writeTo(
        out: File,
        data: File,
        dataSize: Long,
        arch: NativeArch,
        startSymbol: String,
        endSymbol: String,
        sizeSymbol: String,
        segment: String = "__DATA",
        section: String = "__data",
        dataAlign: Int = 16,
    ) {
        val sizeofcmds = SEGMENT_CMD_SIZE + SYMTAB_CMD_SIZE + DYSYMTAB_CMD_SIZE
        val dataOffset = align(HEADER_SIZE + sizeofcmds, dataAlign)
        val symtabOffset = align(dataOffset + dataSize, 8)
        val nsyms = 3
        val strtabOffset = symtabOffset + nsyms * NLIST_SIZE

        // Таблица строк: первый байт — 0, дальше имена с завершающими нулями.
        // В Mach-O у C-имён префикс «_», поэтому он добавляется здесь.
        val strtab = ByteArrayOutputStream()
        strtab.write(0)
        fun intern(cName: String): Int {
            val offset = strtab.size()
            strtab.write(("_$cName").toByteArray(Charsets.UTF_8))
            strtab.write(0)
            return offset
        }

        val startName = intern(startSymbol)
        val endName = intern(endSymbol)
        val sizeName = intern(sizeSymbol)
        val strtabBytes = strtab.toByteArray()
        val total = strtabOffset + strtabBytes.size

        // Заголовок и load commands — до блока данных.
        val pre = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)

        pre.putInt(MH_MAGIC_64)
        pre.putInt(arch.machoCpuType)
        pre.putInt(arch.machoCpuSubtype)
        pre.putInt(MH_OBJECT)
        pre.putInt(NCMDS)
        pre.putInt(sizeofcmds)
        // MH_SUBSECTIONS_VIA_SYMBOLS — так же, как у clang.
        pre.putInt(0x2000)
        pre.putInt(0) // reserved

        pre.putInt(LC_SEGMENT_64)
        pre.putInt(SEGMENT_CMD_SIZE)
        // У объектных файлов clang оставляет имя сегмента пустым.
        putName(pre, "")
        pre.putLong(0)                     // vmaddr
        pre.putLong(dataSize)              // vmsize
        pre.putLong(dataOffset.toLong())   // fileoff
        pre.putLong(dataSize)              // filesize
        pre.putInt(7)                      // maxprot
        pre.putInt(7)                      // initprot
        pre.putInt(1)                      // nsects
        pre.putInt(0)                      // flags

        putName(pre, section)
        putName(pre, segment)
        pre.putLong(0)                     // addr
        pre.putLong(dataSize)              // size
        pre.putInt(dataOffset)             // offset
        pre.putInt(4)                      // align: 2^4 = 16
        pre.putInt(0)                      // reloff
        pre.putInt(0)                      // nreloc
        pre.putInt(0)                      // flags: S_REGULAR
        pre.putInt(0)                      // reserved1
        pre.putInt(0)                      // reserved2
        pre.putInt(0)                      // reserved3

        pre.putInt(LC_SYMTAB)
        pre.putInt(SYMTAB_CMD_SIZE)
        pre.putInt(symtabOffset)
        pre.putInt(nsyms)
        pre.putInt(strtabOffset)
        pre.putInt(strtabBytes.size)

        pre.putInt(LC_DYSYMTAB)
        pre.putInt(DYSYMTAB_CMD_SIZE)
        pre.putInt(0)      // ilocalsym
        pre.putInt(0)      // nlocalsym
        pre.putInt(0)      // iextdefsym
        pre.putInt(nsyms)  // nextdefsym
        pre.putInt(nsyms)  // iundefsym
        pre.putInt(0)      // nundefsym
        repeat(12) { pre.putInt(0) } // tocoff ... nlocrel

        // Таблицы после блока данных.
        val post = ByteBuffer.allocate((total - symtabOffset)).order(ByteOrder.LITTLE_ENDIAN)
        post.position(0)
        putSymbol(post, startName, N_SECT, 1, 0L)
        putSymbol(post, endName, N_SECT, 1, dataSize)
        putSymbol(post, sizeName, N_ABS, 0, dataSize)

        post.position((strtabOffset - symtabOffset))
        post.put(strtabBytes)

        ObjectFile.write(out, pre.array(), data, dataSize, symtabOffset, post.array())
    }

    private fun putSymbol(
        buf: ByteBuffer,
        strx: Int,
        type: Int,
        sect: Int,
        value: Long,
    ) {
        buf.putInt(strx)
        buf.put((type or N_EXT).toByte())
        buf.put(sect.toByte())
        buf.putShort(0) // n_desc
        buf.putLong(value)
    }

    /** Имя поля Mach-O: 16 байт, дополненные нулями. */
    private fun putName(buf: ByteBuffer, name: String) {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 16) { "имя '$name' длиннее 16 байт" }
        buf.put(bytes)
        repeat(16 - bytes.size) { buf.put(0) }
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment

    private fun align(value: Long, alignment: Int): Int =
        align(value.toInt(), alignment)
}
