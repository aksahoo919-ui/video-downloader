package com.easyvideodownloader

/** One entry in the Quality list. */
data class Choice(
    val label: String,
    val height: Int? = null,     // video: maximum picture height, null = best available
    val codec: String? = null,   // audio: output format
    val bitrate: String? = null, // audio: kbps, null = keep original quality
)

object Choices {
    val VIDEO = listOf(
        Choice("Best available"),
        Choice("4K (2160p)", height = 2160),
        Choice("2K (1440p)", height = 1440),
        Choice("Full HD (1080p)", height = 1080),
        Choice("HD (720p)", height = 720),
        Choice("Standard (480p)", height = 480),
        Choice("Small file (360p)", height = 360),
    )
    val AUDIO = listOf(
        Choice("MP3 - Best (320 kbps)", codec = "mp3", bitrate = "320"),
        Choice("MP3 - Good (192 kbps)", codec = "mp3", bitrate = "192"),
        Choice("MP3 - Small file (128 kbps)", codec = "mp3", bitrate = "128"),
        Choice("M4A - Original quality", codec = "m4a"),
        Choice("FLAC - Lossless", codec = "flac"),
        Choice("WAV - Uncompressed (large)", codec = "wav"),
        Choice("OGG - 192 kbps", codec = "vorbis", bitrate = "192"),
    )

    fun forMode(audio: Boolean) = if (audio) AUDIO else VIDEO
    fun defaultIndex(audio: Boolean) = if (audio) 1 else 0
}

object Errors {
    /** Errors a newer download engine cannot fix, so they never trigger an update check. */
    private val NOT_FIXED_BY_UPDATE = listOf(
        "getaddrinfo", "name resolution", "network is unreachable", "timed out", "connection refused",
        "unable to connect", "no route to host", "private video", "sign in to confirm your age",
        "age-restricted", "age restricted", "members-only", "members only", "join this channel",
        "video unavailable", "has been removed", "no longer available", "account associated",
        "not available in your country", "http error 404", "http error 410", "no space left",
        "not enough space", "permission denied", "access is denied", "429", "too many requests",
    )

    private val RULES: List<Pair<List<String>, String>> = listOf(
        listOf("getaddrinfo", "name resolution", "network is unreachable", "timed out",
            "connection refused", "unable to connect", "no route to host") to
            "Could not reach the internet. Please check your connection and try again.",
        listOf("private video") to "This video is private, so it cannot be downloaded.",
        listOf("sign in to confirm your age", "age-restricted", "age restricted") to
            "This video is age-restricted and needs a signed-in account, so it cannot be downloaded.",
        listOf("members-only", "members only", "join this channel") to
            "This video is for channel members only.",
        listOf("video unavailable", "has been removed", "no longer available", "account associated") to
            "This video is unavailable (removed or blocked).",
        listOf("not available in your country", "geo") to
            "This video is not available in your country.",
        listOf("http error 404", "http error 410") to
            "That link did not work (page not found). Please copy the link again.",
        listOf("unsupported url", "is not a valid url") to
            "That link does not look like a video link. Please copy the link again.",
        listOf("no space left", "not enough space") to
            "Your phone's storage is full. Free up some space and try again.",
        listOf("permission denied", "access is denied") to
            "The phone did not allow saving the file. Please try again.",
        listOf("ffmpeg", "ffprobe") to
            "The video converter had a problem. Close the app, open it again and retry.",
        listOf("sign in to confirm you", "429", "too many requests") to
            "YouTube is temporarily limiting downloads from your connection. Please try again later.",
    )

    fun updateMightHelp(raw: String): Boolean {
        val low = raw.lowercase()
        return NOT_FIXED_BY_UPDATE.none { low.contains(it) }
    }

    fun friendly(raw: String): String {
        val low = raw.lowercase()
        RULES.firstOrNull { (needles, _) -> needles.any { low.contains(it) } }?.let { return it.second }
        val cleaned = Regex("^ERROR:\\s*(\\[[^\\]]*\\]\\s*)?([\\w-]+:\\s*)?").replace(raw.trim(), "").trim()
        return cleaned.take(220).ifEmpty { "Something went wrong with this download." }
    }
}

object Format {
    fun size(bytes: Double?): String {
        var n = bytes ?: return ""
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (n < 1024) return if (unit == "B") "${n.toInt()} $unit" else String.format("%.1f %s", n, unit)
            n /= 1024
        }
        return String.format("%.1f TB", n)
    }

    fun time(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        return when {
            s < 60 -> "$s sec"
            s < 3600 -> "${s / 60} min ${s % 60} sec"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    fun number(text: String): Double? = text.trim().toDoubleOrNull()
}
