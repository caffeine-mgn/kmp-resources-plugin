package pw.binom.resources.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись PE/COFF-объектника (`x86-64`) с ресурсами — вариант для mingw.
 *
 * ## Зачем свой писатель
 *
 * Тот же резон, что и для ELF/Mach-O: `objcopy` нет в поставке Kotlin/Native,
 * а внешний binutils — лишняя зависимость. COFF-объект простой, поэтому
 * пишется целиком здесь.
 *
 * ## Структура
 *
 * ```
 * [0]  IMAGE_FILE_HEADER (20)
 * [20] одна запись IMAGE_SECTION_HEADER (40)
 *      данные блоба, выровнены на 16
 *      таблица символов (18 × 3)
 *      таблица строк: u32 размер, затем имена с нулями
 * ```
 *
 * В отличие от 32-битного x86, в x86-64 COFF C-символы хранятся без
 * дополнительного `_` — имена пишутся как есть.
 *
 * ## Память
 *
 * Блоб не поднимается в кучу: [writeTo] отдаёт его потоком из файла контейнера.
 */
object CoffWriter {

    private const val MACHINE_AMD64 = 0x8664
    private const val FILE_HEADER_SIZE = 20
    private const val SECTION_HEADER_SIZE = 40
    private const val SYMBOL_SIZE = 18

    private const val SYM_CLASS_EXTERNAL = 2
    private const val SYM_ABSOLUTE_SECTION = -1

    private const val SCN_CNT_INITIALIZED_DATA = 0x00000040
    private const val SCN_MEM_READ = 0x40000000
    private const val SCN_MEM_WRITE = 0x80000000.toInt()
    private const val SCN_ALIGN_16 = 0x00500000

    /**
     * Собрать COFF-объектник с блобом в секции `.data`.
     */
    fun writeTo(
        out: File,
        data: File,
        dataSize: Long,
        arch: NativeArch,
        startSymbol: String,
        endSymbol: String,
        sizeSymbol: String,
        sectionName: String = ".data",
        writable: Boolean = true,
        dataAlign: Int = 16,
    ) {
        require(arch == NativeArch.X86_64) {
            "COFF-писатель поддерживает только x86-64, запрошено $arch"
        }

        val dataOffset = align(FILE_HEADER_SIZE + SECTION_HEADER_SIZE, dataAlign)
        val symtabOffset = align(dataOffset + dataSize, 4)
        val nsyms = 3
        val strtabOffset = symtabOffset + nsyms * SYMBOL_SIZE

        val strtab = ByteArrayOutputStream()
        strtab.write(ByteArray(4)) // размер таблицы допишем в конце
        fun intern(name: String): Int {
            // Короткое имя (≤8) хранится прямо в записи символа.
            if (name.length <= 8) return 0
            val offset = strtab.size()
            strtab.write(name.toByteArray(Charsets.UTF_8))
            strtab.write(0)
            return offset
        }

        val startName = intern(startSymbol)
        val endName = intern(endSymbol)
        val sizeName = intern(sizeSymbol)

        val strtabBytes = strtab.toByteArray()
        ByteBuffer.wrap(strtabBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, strtabBytes.size)

        // Заголовки — до блока данных.
        val pre = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        pre.putShort(MACHINE_AMD64.toShort())
        pre.putShort(1)                       // NumberOfSections
        pre.putInt(0)                         // TimeDateStamp
        pre.putInt(symtabOffset)              // PointerToSymbolTable
        pre.putInt(nsyms)                     // NumberOfSymbols
        pre.putShort(0)                       // SizeOfOptionalHeader
        pre.putShort(0)                       // Characteristics

        val sectionBytes = sectionName.toByteArray(Charsets.US_ASCII)
        require(sectionBytes.size <= 8) {
            "COFF: имя секции '$sectionName' длиннее 8 байт — формат требует " +
                "ссылку в таблицу строк, плагин этого не делает"
        }
        pre.put(sectionBytes)
        repeat(8 - sectionBytes.size) { pre.put(0) }
        pre.putInt(dataSize.toInt())          // VirtualSize
        pre.putInt(0)                         // VirtualAddress
        pre.putInt(dataSize.toInt())          // SizeOfRawData
        pre.putInt(dataOffset)                // PointerToRawData
        pre.putInt(0)                         // PointerToRelocations
        pre.putInt(0)                         // PointerToLinenumbers
        pre.putShort(0)                       // NumberOfRelocations
        pre.putShort(0)                       // NumberOfLinenumbers
        var characteristics = SCN_CNT_INITIALIZED_DATA or SCN_MEM_READ or SCN_ALIGN_16
        if (writable) characteristics = characteristics or SCN_MEM_WRITE
        pre.putInt(characteristics)

        // Символы и строки — после данных.
        val post = ByteBuffer.allocate((strtabOffset + strtabBytes.size - symtabOffset))
            .order(ByteOrder.LITTLE_ENDIAN)
        post.position(0)
        putSymbol(post, startSymbol, startName, 0L, 1)
        putSymbol(post, endSymbol, endName, dataSize, 1)
        putSymbol(post, sizeSymbol, sizeName, dataSize, SYM_ABSOLUTE_SECTION)

        post.position(strtabOffset - symtabOffset)
        post.put(strtabBytes)

        ObjectFile.write(out, pre.array(), data, dataSize, symtabOffset, post.array())
    }

    private fun putSymbol(
        buf: ByteBuffer,
        name: String,
        strtabOffset: Int,
        value: Long,
        section: Int,
    ) {
        if (strtabOffset == 0 && name.length <= 8) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            buf.put(bytes)
            repeat(8 - bytes.size) { buf.put(0) }
        } else {
            buf.putInt(0)                  // Zeroes: имя в таблице строк
            buf.putInt(strtabOffset)
        }
        buf.putInt(value.toInt())          // Value
        buf.putShort(section.toShort())    // SectionNumber
        buf.putShort(0)                    // Type: не функция
        buf.put(SYM_CLASS_EXTERNAL.toByte())
        buf.put(0)                         // NumberOfAuxSymbols
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment

    private fun align(value: Long, alignment: Int): Int =
        align(value.toInt(), alignment)
}
