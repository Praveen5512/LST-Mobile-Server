package com.localserver

import com.localserver.core.MimeTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypeResolverTest {

    @Test
    fun testVideoMimeTypes() {
        assertEquals("video/mp4", MimeTypeResolver.resolve("movie.mp4"))
        assertEquals("video/x-matroska", MimeTypeResolver.resolve("movie.mkv"))
        assertEquals("video/webm", MimeTypeResolver.resolve("clip.webm"))
        assertEquals("video/quicktime", MimeTypeResolver.resolve("video.mov"))

        assertTrue(MimeTypeResolver.isVideo("video/mp4"))
        assertTrue(MimeTypeResolver.isVideo("video/x-matroska"))
        assertFalse(MimeTypeResolver.isVideo("audio/mpeg"))
    }

    @Test
    fun testAudioMimeTypes() {
        assertEquals("audio/mpeg", MimeTypeResolver.resolve("song.mp3"))
        assertEquals("audio/flac", MimeTypeResolver.resolve("track.flac"))
        assertEquals("audio/wav", MimeTypeResolver.resolve("audio.wav"))

        assertTrue(MimeTypeResolver.isAudio("audio/mpeg"))
        assertFalse(MimeTypeResolver.isAudio("image/jpeg"))
    }

    @Test
    fun testImageMimeTypes() {
        assertEquals("image/jpeg", MimeTypeResolver.resolve("photo.jpg"))
        assertEquals("image/jpeg", MimeTypeResolver.resolve("photo.jpeg"))
        assertEquals("image/png", MimeTypeResolver.resolve("graphic.png"))
        assertEquals("image/webp", MimeTypeResolver.resolve("image.webp"))

        assertTrue(MimeTypeResolver.isImage("image/jpeg"))
    }

    @Test
    fun testDocumentAndFallbackMimeTypes() {
        assertEquals("application/pdf", MimeTypeResolver.resolve("doc.pdf"))
        assertEquals("text/plain", MimeTypeResolver.resolve("notes.txt"))
        assertEquals("application/json", MimeTypeResolver.resolve("data.json"))
        assertEquals("application/octet-stream", MimeTypeResolver.resolve("unknown.binxyz"))
    }
}
