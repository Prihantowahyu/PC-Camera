package com.wahyu.pccam

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Penulis file AVI (Motion JPEG) sederhana, tanpa audio. */
class AviWriter(val file: File) {
    var w = 0
        private set
    var h = 0
        private set
    var bytes = 0L
        private set
    var frames = 0
        private set

    private val raf = RandomAccessFile(file, "rw")
    private val offs = ArrayList<Int>()
    private val sizes = ArrayList<Int>()
    private var startMs = 0L
    private var maxChunk = 0
    private val moviPos = 220L

    fun add(jpg: ByteArray, width: Int, height: Int) {
        if (frames == 0) {
            w = width
            h = height
            startMs = System.currentTimeMillis()
            raf.setLength(0)
            raf.write(header(0, 200000, 0, 0))
        }
        val pos = raf.filePointer
        val hd = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        hd.put("00dc".toByteArray(Charsets.US_ASCII))
        hd.putInt(jpg.size)
        raf.write(hd.array())
        raf.write(jpg)
        if (jpg.size % 2 == 1) raf.write(0)
        offs.add((pos - moviPos).toInt())
        sizes.add(jpg.size)
        if (jpg.size > maxChunk) maxChunk = jpg.size
        frames++
        bytes = raf.filePointer
    }

    fun finish() {
        try {
            if (frames == 0) {
                raf.close()
                file.delete()
                return
            }
            val chunksEnd = raf.filePointer
            val idx = ByteBuffer.allocate(8 + 16 * frames).order(ByteOrder.LITTLE_ENDIAN)
            idx.put("idx1".toByteArray(Charsets.US_ASCII))
            idx.putInt(16 * frames)
            for (i in 0 until frames) {
                idx.put("00dc".toByteArray(Charsets.US_ASCII))
                idx.putInt(0x10)
                idx.putInt(offs[i])
                idx.putInt(sizes[i])
            }
            raf.write(idx.array())
            val fileEnd = raf.filePointer
            val elapsed = maxOf(1L, System.currentTimeMillis() - startMs)
            val usPerFrame = (elapsed * 1000L / frames).toInt()
            raf.seek(0)
            raf.write(header(frames, usPerFrame, (fileEnd - 8).toInt(), (chunksEnd - moviPos).toInt()))
        } catch (e: Exception) {
            // abaikan
        } finally {
            try { raf.close() } catch (e: Exception) { }
        }
    }

    private fun header(total: Int, usPerFrame: Int, riffSize: Int, moviSize: Int): ByteArray {
        val b = ByteBuffer.allocate(224).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); b.putInt(riffSize); tag("AVI ")
        tag("LIST"); b.putInt(192); tag("hdrl")
        tag("avih"); b.putInt(56)
        b.putInt(usPerFrame); b.putInt(0); b.putInt(0); b.putInt(0x10)
        b.putInt(total); b.putInt(0); b.putInt(1); b.putInt(maxChunk)
        b.putInt(w); b.putInt(h)
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0)
        tag("LIST"); b.putInt(116); tag("strl")
        tag("strh"); b.putInt(56)
        tag("vids"); tag("MJPG")
        b.putInt(0); b.putInt(0); b.putInt(0)
        b.putInt(usPerFrame); b.putInt(1000000); b.putInt(0); b.putInt(total); b.putInt(maxChunk)
        b.putInt(-1); b.putInt(0)
        b.putShort(0); b.putShort(0); b.putShort(w.toShort()); b.putShort(h.toShort())
        tag("strf"); b.putInt(40)
        b.putInt(40); b.putInt(w); b.putInt(h)
        b.putShort(1); b.putShort(24)
        tag("MJPG"); b.putInt(w * h * 3)
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0)
        tag("LIST"); b.putInt(moviSize); tag("movi")
        return b.array()
    }
}
