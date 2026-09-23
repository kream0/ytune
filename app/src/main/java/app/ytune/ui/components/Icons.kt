package app.ytune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small icon set (Material Design paths, Apache 2.0) so we don't ship the huge icons-extended lib. */
object Ic {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Search by lazy {
        icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    }
    val Close by lazy {
        icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    }
    val Back by lazy {
        icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    }
    val ChevronDown by lazy {
        icon("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    }
    val ChevronUp by lazy {
        icon("up", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")
    }
    val More by lazy {
        icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    }
    val Add by lazy {
        icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }
    val Queue by lazy {
        icon("queue", "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18c-0.31,-0.11 -0.65,-0.18 -1,-0.18 -1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3V8h3V6h-5z")
    }
    val PlaylistAdd by lazy {
        icon("playlist_add", "M14,10H2v2h12v-2zM14,6H2v2h12V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2,16h8v-2H2v2z")
    }
    val PlaylistPlay by lazy {
        icon("playlist_play", "M19,9H2v2h17V9zM19,5H2v2h17V5zM2,15h13v-2H2v2zM17,13v6l5,-3 -5,-3z")
    }
    val Shuffle by lazy {
        icon("shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z")
    }
    val Repeat by lazy {
        icon("repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    val RepeatOne by lazy {
        icon("repeat_one", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z")
    }
    val Download by lazy {
        icon("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }
    val Check by lazy {
        icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }
    val Delete by lazy {
        icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }
    val Refresh by lazy {
        icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    }
    val Bookmark by lazy {
        icon("bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
    }
    val BookmarkBorder by lazy {
        icon("bookmark_border", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
    }
    val Tune by lazy {
        icon("tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    }
    val PlayNext by lazy {
        icon("play_next", "M3,10h11v2H3zM3,6h11v2H3zM3,14h7v2H3zM16,13v8l6,-4z")
    }
}
