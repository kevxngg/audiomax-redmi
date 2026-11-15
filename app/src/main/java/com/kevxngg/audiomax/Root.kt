package com.kevxngg.audiomax

import java.io.File

/** Runs commands with su (KernelSU). */
object Root {
    data class Result(val code: Int, val out: String)

    fun run(vararg cmds: String): Result {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            cmds.forEach { w.write(it); w.write("\n") }
            w.write("exit\n")
        }
        val out = p.inputStream.bufferedReader().readText()
        return Result(p.waitFor(), out)
    }

    fun available(): Boolean = try {
        run("id").out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /** Copies a root-owned file to an app-owned file (via stdout, avoiding SELinux issues). */
    fun readFile(src: String, dst: File): Boolean {
        val p = ProcessBuilder("su", "-c", "cat '$src'").start()
        dst.outputStream().use { o -> p.inputStream.use { it.copyTo(o) } }
        return p.waitFor() == 0 && dst.length() > 0
    }

    /** Writes an app file to a root-owned path (via stdin). */
    fun writeFile(src: File, dst: String): Boolean {
        val p = ProcessBuilder("su", "-c", "cat > '$dst'").start()
        p.outputStream.use { o -> src.inputStream().use { it.copyTo(o) } }
        return p.waitFor() == 0
    }
}
