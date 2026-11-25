package com.david.gitly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarUrlTest {
    @Test fun githubPngGoesDirectAndSmall() =
        assertEquals("https://avatars.githubusercontent.com/octocat?s=128", avatarUrl("https://github.com/octocat.png", 100))

    @Test fun apiUrlGetsSize() =
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4&s=64", avatarUrl("https://avatars.githubusercontent.com/u/1?v=4", 40))

    @Test fun existingSizeIsReplaced() =
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4&s=256", avatarUrl("https://avatars.githubusercontent.com/u/1?s=40&v=4", 200))

    @Test fun otherUrlsUntouched() =
        assertEquals("https://example.com/a.png", avatarUrl("https://example.com/a.png", 64))
}
