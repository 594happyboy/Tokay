package com.happyboy.tokay.asm.pack

import org.tukaani.xz.FinishableOutputStream
import org.tukaani.xz.FinishableWrapperOutputStream
import org.tukaani.xz.LZMA2Options

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * 目录树 → 7z（**单个 LZMA2 固实块**），只依赖 JDK 与 xz 的 LZMA2 编码器。
 *
 * 为什么不用 commons-compress 的 `SevenZOutputFile`：它每个文件各开一条独立压缩流
 * （`closeArchiveEntry()` 会关掉当前流），**写不出固实块**——实测产物是 14 个 folder，
 * 而参考包是 1 个 folder + 14 条子流，体积差约 2%。所以容器（签名头 + 头部 + 子流表）在这里手写，
 * 压缩仍交给 xz，读回方是运行期同一个解码器 commons-compress `SevenZFile`（见测试与
 * `tools/gecko-package/Verify7z.java`）。
 *
 * 条目形态与 7-Zip 命令行 `a -t7z -m0=LZMA2 -mx=9 -md=128m -mfb=273 -ms=on -mf=off` 一致：
 * 名称是以 `/` 分隔的相对路径，目录也作为条目写入且**不带尾部斜杠**（解压端按 isDirectory 判定），
 * 顺序为「全部目录在前、全部文件在后，两组各自按名称升序」，全部文件内容拼成一条固实流。
 * 不写 mtime / Windows 属性：容器本身不含时间戳，解压端也不读它。整包仍**不可复现**——`res/base.apk`
 * 是打包任务现造的 zip，条目带当前时间戳，所以两次打包体积相同、字节不同。
 *
 * 编码器堆占用与字典同阶（64 MiB 字典约 1 GiB）；LZMA2 编码是单线程的。
 */
class SevenZWriter {

    /** 字典大小。原实现用 128 MiB，但那份内存会撑爆默认 2048m 的 Gradle 守护进程；64 MiB 只大 0.14%。 */
    static final int DICT_SIZE_BYTES = 64 * 1024 * 1024

    /** 对齐原 `-mfb=273`（同时也是 LZMA2 preset 9 的上限值）。 */
    static final int NICE_LEN = 273

    private static final int SIGNATURE_HEADER_SIZE = 32
    private static final byte[] SIGNATURE = [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C] as byte[]

    private static final int K_END = 0x00
    private static final int K_HEADER = 0x01
    private static final int K_MAIN_STREAMS_INFO = 0x04
    private static final int K_FILES_INFO = 0x05
    private static final int K_PACK_INFO = 0x06
    private static final int K_UNPACK_INFO = 0x07
    private static final int K_SUB_STREAMS_INFO = 0x08
    private static final int K_SIZE = 0x09
    private static final int K_CRC = 0x0A
    private static final int K_FOLDER = 0x0B
    private static final int K_CODERS_UNPACK_SIZE = 0x0C
    private static final int K_NUM_UNPACK_STREAM = 0x0D
    private static final int K_EMPTY_STREAM = 0x0E
    private static final int K_EMPTY_FILE = 0x0F
    private static final int K_NAME = 0x11

    private static final int LZMA2_METHOD_ID = 0x21

    /**
     * 用默认内核包参数（[DICT_SIZE_BYTES] + [NICE_LEN]）把 [rootDir] 的**子项**写成 7z 的顶层条目。
     */
    static void writeTree(File rootDir, File outputFile) {
        writeTree(rootDir, outputFile, DICT_SIZE_BYTES, NICE_LEN)
    }

    /**
     * 同 [writeTree(File, File)]，但由调用方给定字典大小与 fast bytes。
     * 字典可调小是给测试用的：编码器堆占用与字典同阶，64 MiB 会撑爆测试 JVM。
     */
    static void writeTree(File rootDir, File outputFile, int dictSizeBytes, int niceLen) {
        if (rootDir == null || !rootDir.isDirectory()) {
            throw new IllegalArgumentException("7z 打包根目录不存在: ${rootDir}")
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("7z 输出文件为空")
        }

        List<Entry> entries = collectEntries(rootDir.toPath())
        List<Entry> streams = entries.findAll { it.hasStream }
        if (streams.isEmpty()) {
            throw new IllegalArgumentException("7z 打包根目录里没有可压缩的文件: ${rootDir}")
        }

        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("无法覆盖已存在的 7z 文件: ${outputFile}")
        }
        outputFile.parentFile?.mkdirs()

        LZMA2Options options = new LZMA2Options(9)
        options.setDictSize(dictSizeBytes)
        options.setNiceLen(niceLen)

        CRC32 folderCrc = new CRC32()
        long unpackedSize = 0

        RandomAccessFile archive = new RandomAccessFile(outputFile, 'rw')
        try {
            archive.setLength(0)
            archive.seek(SIGNATURE_HEADER_SIZE)

            OutputStream fileOut = Channels.newOutputStream(archive.channel)
            FinishableOutputStream encoder =
                    options.getOutputStream(new FinishableWrapperOutputStream(fileOut))

            byte[] buffer = new byte[1 << 16]
            streams.each { Entry entry ->
                CRC32 entryCrc = new CRC32()
                long size = 0
                entry.file.withInputStream { input ->
                    int read
                    while ((read = input.read(buffer)) > 0) {
                        encoder.write(buffer, 0, read)
                        entryCrc.update(buffer, 0, read)
                        folderCrc.update(buffer, 0, read)
                        size += read
                    }
                }
                entry.size = size
                entry.crc = entryCrc.value
                unpackedSize += size
            }
            encoder.finish()

            long packedSize = archive.filePointer - SIGNATURE_HEADER_SIZE
            byte[] header = buildHeader(entries, streams, options, packedSize, unpackedSize, folderCrc.value)
            fileOut.write(header)

            archive.seek(0)
            archive.write(buildSignatureHeader(packedSize, header))
        } finally {
            archive.close()
        }
    }

    /** 目录在前、文件在后，两组各自按名称升序——与 7-Zip 的条目顺序一致。 */
    private static List<Entry> collectEntries(Path root) {
        List<Path> directories = []
        List<Path> files = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { it != root }.forEach { path ->
                if (Files.isDirectory(path)) {
                    directories << path
                } else if (Files.isRegularFile(path)) {
                    files << path
                }
            }
        }
        directories.sort { relativeName(root, it) }
        files.sort { relativeName(root, it) }

        List<Entry> entries = []
        directories.each { entries << new Entry(relativeName(root, it), null, true) }
        files.each { entries << new Entry(relativeName(root, it), it.toFile(), false) }
        return entries
    }

    private static byte[] buildHeader(List<Entry> entries, List<Entry> streams, LZMA2Options options,
                                      long packedSize, long unpackedSize, long folderCrc) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        DataOutputStream out = new DataOutputStream(bytes)

        out.writeByte(K_HEADER)
        out.writeByte(K_MAIN_STREAMS_INFO)

        out.writeByte(K_PACK_INFO)
        writeUint64(out, 0)                  // packPos：数据紧跟在签名头之后
        writeUint64(out, 1)                  // numPackStreams：固实块只有一条
        out.writeByte(K_SIZE)
        writeUint64(out, packedSize)
        out.writeByte(K_END)

        out.writeByte(K_UNPACK_INFO)
        out.writeByte(K_FOLDER)
        writeUint64(out, 1)                  // numFolders
        out.writeByte(0)                     // external
        writeUint64(out, 1)                  // numCoders
        out.writeByte(0x21)                  // coder flags：idSize=1 且带属性
        out.writeByte(LZMA2_METHOD_ID)
        out.writeByte(1)                     // 属性长度
        out.writeByte(lzma2Props(options.dictSize))
        out.writeByte(K_CODERS_UNPACK_SIZE)
        writeUint64(out, unpackedSize)
        out.writeByte(K_CRC)
        out.writeByte(1)                     // allAreDefined
        writeIntLe(out, (int) folderCrc)
        out.writeByte(K_END)

        out.writeByte(K_SUB_STREAMS_INFO)
        if (streams.size() > 1) {
            out.writeByte(K_NUM_UNPACK_STREAM)
            writeUint64(out, streams.size())
            out.writeByte(K_SIZE)            // 只写前 n-1 个子流大小，最后一条由 folder 总长推得
            streams[0..-2].each { writeUint64(out, it.size) }
            out.writeByte(K_CRC)
            out.writeByte(1)                 // allAreDefined
            streams.each { writeIntLe(out, (int) it.crc) }
        }
        out.writeByte(K_END)

        out.writeByte(K_END)                 // kMainStreamsInfo 结束

        out.writeByte(K_FILES_INFO)
        writeUint64(out, entries.size())

        List<Entry> emptyStreams = entries.findAll { !it.hasStream }
        if (!emptyStreams.isEmpty()) {
            out.writeByte(K_EMPTY_STREAM)
            writeSized(out, packBits(entries.collect { !it.hasStream }))
            // kEmptyFile 只在「无流条目里有文件」时才需要；空流条目全是目录时省略即表示目录
            if (emptyStreams.any { !it.directory }) {
                out.writeByte(K_EMPTY_FILE)
                writeSized(out, packBits(emptyStreams.collect { !it.directory }))
            }
        }

        out.writeByte(K_NAME)
        ByteArrayOutputStream names = new ByteArrayOutputStream()
        names.write(0)                       // external = 0
        entries.each {
            names.write(it.name.getBytes('UTF-16LE'))
            names.write(0)
            names.write(0)                   // UTF-16LE 的 NUL 结束符
        }
        writeSized(out, names.toByteArray())

        out.writeByte(K_END)                 // kFilesInfo 结束
        out.writeByte(K_END)                 // Header 结束
        out.flush()
        return bytes.toByteArray()
    }

    private static byte[] buildSignatureHeader(long nextHeaderOffset, byte[] headerBytes) {
        CRC32 headerCrc = new CRC32()
        headerCrc.update(headerBytes)

        ByteBuffer signature = ByteBuffer.allocate(SIGNATURE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        signature.put(SIGNATURE)
        signature.put((byte) 0).put((byte) 4)          // 7z 格式版本 0.4
        signature.putInt(0)                            // StartHeaderCRC 占位
        signature.putLong(nextHeaderOffset)
        signature.putLong(headerBytes.length)
        signature.putInt((int) headerCrc.value)

        CRC32 startHeaderCrc = new CRC32()
        startHeaderCrc.update(signature.array(), 12, 20)
        signature.putInt(8, (int) startHeaderCrc.value)
        return signature.array()
    }

    private static void writeSized(DataOutputStream out, byte[] body) {
        writeUint64(out, body.length)
        out.write(body)
    }

    /** MSB first，与 7z 的位向量编码一致；末尾不足一字节的部分补零。 */
    private static byte[] packBits(List<Boolean> bits) {
        byte[] packed = new byte[(bits.size() + 7) / 8]
        bits.eachWithIndex { Boolean bit, int index ->
            if (bit) {
                packed[index >> 3] = (byte) (packed[index >> 3] | (0x80 >> (index & 7)))
            }
        }
        return packed
    }

    /** 7z 的可变长整数：首字节的高位标记后续还有几个字节，数值低位在前。 */
    private static void writeUint64(DataOutputStream out, long value) {
        int firstByte = 0
        int mask = 0x80
        int i
        for (i = 0; i < 8; i++) {
            if (value < (1L << (7 * (i + 1)))) {
                firstByte |= (int) (value >>> (8 * i))
                break
            }
            firstByte |= mask
            mask >>>= 1
        }
        out.writeByte(firstByte)
        for (; i > 0; i--) {
            out.writeByte((int) (0xFF & value))
            value >>>= 8
        }
    }

    private static void writeIntLe(DataOutputStream out, int value) {
        out.writeByte(value & 0xFF)
        out.writeByte((value >>> 8) & 0xFF)
        out.writeByte((value >>> 16) & 0xFF)
        out.writeByte((value >>> 24) & 0xFF)
    }

    /** LZMA2 属性字节只编码字典大小；这个式子是 commons-compress / 7-Zip 用的反函数。 */
    private static int lzma2Props(int dictSize) {
        int lead = Integer.numberOfLeadingZeros(dictSize)
        int secondBit = (dictSize >>> (30 - lead)) - 2
        return (19 - lead) * 2 + secondBit
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/' as char)
    }

    private static final class Entry {
        final String name
        final File file
        final boolean directory
        long size
        long crc

        Entry(String name, File file, boolean directory) {
            this.name = name
            this.file = file
            this.directory = directory
        }

        boolean getHasStream() {
            return !directory && file != null && file.length() > 0
        }
    }
}
