package com.junkfood.seal.ui.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.junkfood.seal.R

/**
 * Coil 3 migration notes (this is the ONLY file in the project that touches Coil):
 *  - All imports moved from `coil.*` to `coil3.*`; `crossfade` is now an extension in
 *    `coil3.request`.
 *  - The explicit `imageLoader = LocalContext.current.imageLoader` argument is gone: Coil 3's
 *    `AsyncImage` resolves the singleton ImageLoader itself (`SingletonImageLoader`), which is
 *    what the old expression returned anyway — same loader, less plumbing.
 *  - Network fetching is no longer part of Coil's core; the `coil-network-okhttp` artifact in
 *    app/build.gradle.kts registers an OkHttp-backed fetcher via ServiceLoader. Without it, every
 *    remote thumbnail would fail. No code here needs to reference it.
 *  - The `transform`/`onState` parameter types are now `coil3.compose.AsyncImagePainter.State`;
 *    no external caller passes either parameter (verified across the codebase), so the type
 *    change is fully contained in this file.
 */
@Composable
fun AsyncImageImpl(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    transform: (AsyncImagePainter.State) -> AsyncImagePainter.State =
        AsyncImagePainter.DefaultTransform,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    isPreview: Boolean = LocalInspectionMode.current,
) {
    if (isPreview)
        Image(
            painter = painterResource(R.drawable.sample3),
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
        )
    else
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(model).crossfade(true).build(),
            contentDescription = contentDescription,
            modifier = modifier,
            transform = transform,
            onState = onState,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = filterQuality,
        )
}
