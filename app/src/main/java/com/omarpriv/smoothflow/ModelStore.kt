package com.omarpriv.smoothflow

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object ModelStore {
    private data class ModelFile(val name: String, val size: Long, val gitSha1: String)

    private val files = listOf(
        ModelFile("contextnet.bin", 2_387_688L, "cc403446ccb5ec64c75d80587d7dfce648e569d4"),
        ModelFile("contextnet.param", 3_318L, "ec0085ef8237d90704f7cc15838978d6d3d8695c"),
        ModelFile("flownet.bin", 30_555_792L, "f5d2fc892707bed90b09e654a22bba1703145531"),
        ModelFile("flownet.param", 10_059L, "2129f0a6f9e2b87024c6c86cdfb3d8f8a5aecfe3"),
        ModelFile("fusionnet.bin", 23_686_092L, "435c8b081972846674dafe689fdb7e382006b79a"),
        ModelFile("fusionnet.param", 5_701L, "35ab12eb3d5554ff886a59ed98ad89c22d5c53bb")
    )

    private const val BASE_URL =
        "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7/models/rife-v2.3/"

    fun ensure(modelDir: File, progress: (Int, String) -> Unit) {
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            error("تعذّر إنشاء مجلد النموذج")
        }
        val totalBytes = files.sumOf { it.size }
        var completedBytes = 0L

        for (model in files) {
            val target = File(modelDir, model.name)
            if (target.exists() && target.length() == model.size &&
                gitBlobSha1(target) == model.gitSha1
            ) {
                completedBytes += model.size
                progress((completedBytes * 100 / totalBytes).toInt(), "النموذج جاهز: ${model.name}")
                continue
            }

            target.delete()
            val partial = File(modelDir, "${model.name}.part")
            partial.delete()
            val connection = (URL(BASE_URL + model.name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    error("تعذّر تنزيل ${model.name}: HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count: Int
                        while (input.read(buffer).also { count = it } >= 0) {
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            val current = completedBytes + partial.length()
                            progress((current * 100 / totalBytes).toInt().coerceIn(0, 99),
                                "تنزيل ملفات RIFE: ${model.name}")
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (partial.length() != model.size || gitBlobSha1(partial) != model.gitSha1) {
                partial.delete()
                error("فشل التحقق من ملف النموذج ${model.name}. أعد المحاولة.")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            completedBytes += model.size
            progress((completedBytes * 100 / totalBytes).toInt(), "تم تنزيل ${model.name}")
        }
    }

    private fun gitBlobSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
