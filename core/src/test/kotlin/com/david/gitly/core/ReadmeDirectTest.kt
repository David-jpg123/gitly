package com.david.gitly.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadmeDirectTest {
    @Test fun relativeImagesGoStraightToRaw() {
        assertEquals("https://raw.githubusercontent.com/o/r/HEAD/docs/a.png", ReadmeParser.resolve("docs/a.png", "o/r"))
        assertEquals("https://raw.githubusercontent.com/o/r/HEAD/img/b.gif", ReadmeParser.resolve("../img/b.gif", "o/r", "docs/"))
    }

    @Test fun blobAndRawLinksAreRewritten() {
        assertEquals("https://raw.githubusercontent.com/o/r/main/s.png", ReadmeParser.direct("https://github.com/o/r/blob/main/s.png?raw=true"))
        assertEquals("https://raw.githubusercontent.com/o/r/refs/heads/dev/x.jpg", ReadmeParser.direct("https://github.com/o/r/raw/refs/heads/dev/x.jpg"))
    }

    @Test fun otherHostsUntouched() {
        val u = "https://github.com/user-attachments/assets/1234"
        assertEquals(u, ReadmeParser.direct(u))
        assertEquals("https://i.imgur.com/x.png", ReadmeParser.direct("https://i.imgur.com/x.png"))
    }
}
