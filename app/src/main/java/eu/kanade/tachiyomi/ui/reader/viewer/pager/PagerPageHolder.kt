package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import logcat.LogPriority
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import tachiyomi.decoder.ImageDecoder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator: ReaderProgressIndicator = ReaderProgressIndicator(readerThemedContext).apply {
        updateLayoutParams<LayoutParams> {
            gravity = Gravity.CENTER
        }
    }

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Subscription for status changes of the page.
     */
    private var statusSubscription: Subscription? = null

    /**
     * Job for progress changes of the page.
     */
    private var progressJob: Job? = null

    /**
     * Subscription for status changes of the page.
     */
    private var extraStatusSubscription: Subscription? = null

    /**
     * Subscription for progress changes of the page.
     */
    private var extraProgressJob: Job? = null

    /**
     * Subscription used to read the header of the image. This is needed in order to instantiate
     * the appropiate image view depending if the image is animated (GIF).
     */
    private var readImageHeaderSubscription: Subscription? = null

    // SY -->
    var status: Page.State = Page.State.QUEUE
    var extraStatus: Page.State = Page.State.QUEUE
    // SY <--

    init {
        addView(progressIndicator)
        observeStatus()
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelProgressJob(1)
        cancelProgressJob(2)
        unsubscribeStatus(1)
        unsubscribeStatus(2)
        unsubscribeReadImageHeader()
    }

    /**
     * Observes the status of the page and notify the changes.
     *
     * @see processStatus
     */
    private fun observeStatus() {
        statusSubscription?.unsubscribe()

        val loader = page.chapter.pageLoader ?: return
        statusSubscription = loader.getPage(page)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                status = it
                processStatus(it)
            }

        val extraPage = extraPage ?: return
        val loader2 = extraPage.chapter.pageLoader ?: return
        extraStatusSubscription = loader2.getPage(extraPage)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                extraStatus = it
                processStatus2(it)
            }
    }

    private fun launchProgressJob() {
        progressJob?.cancel()
        progressJob = scope.launchUI {
            page.progressFlow.collectLatest { value -> progressIndicator.setProgress(value) }
        }
    }

    private fun launchProgressJob2() {
        extraProgressJob?.cancel()
        val extraPage = extraPage ?: return
        extraProgressJob = scope.launchUI {
            extraPage.progressFlow.collectLatest { value -> progressIndicator.setProgress(((page.progressFlow.value + value) / 2 * 0.95f).roundToInt()) }
        }
    }

    /**
     * Called when the status of the page changes.
     *
     * @param status the new status of the page.
     */
    private fun processStatus(status: Page.State) {
        when (status) {
            Page.State.QUEUE -> setQueued()
            Page.State.LOAD_PAGE -> setLoading()
            Page.State.DOWNLOAD_IMAGE -> {
                launchProgressJob()
                setDownloading()
            }
            Page.State.READY -> {
                if (extraStatus == Page.State.READY || extraPage == null) {
                    setImage()
                }
                cancelProgressJob(1)
            }
            Page.State.ERROR -> {
                setError()
                cancelProgressJob(1)
            }
        }
    }

    /**
     * Called when the status of the page changes.
     *
     * @param status the new status of the page.
     */
    private fun processStatus2(status: Page.State) {
        when (status) {
            Page.State.QUEUE -> setQueued()
            Page.State.LOAD_PAGE -> setLoading()
            Page.State.DOWNLOAD_IMAGE -> {
                launchProgressJob2()
                setDownloading()
            }
            Page.State.READY -> {
                if (this.status == Page.State.READY) {
                    setImage()
                }
                cancelProgressJob(2)
            }
            Page.State.ERROR -> {
                setError()
                cancelProgressJob(2)
            }
        }
    }

    /**
     * Unsubscribes from the status subscription.
     */
    private fun unsubscribeStatus(page: Int) {
        val subscription = if (page == 1) statusSubscription else extraStatusSubscription
        subscription?.unsubscribe()
        if (page == 1) statusSubscription = null else extraStatusSubscription = null
    }

    private fun cancelProgressJob(page: Int) {
        val job = if (page == 1) progressJob else extraProgressJob
        job?.cancel()
        if (page == 1) progressJob = null else extraProgressJob = null
    }

    /**
     * Unsubscribes from the read image header subscription.
     */
    private fun unsubscribeReadImageHeader() {
        readImageHeaderSubscription?.unsubscribe()
        readImageHeaderSubscription = null
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressIndicator.show()
        errorLayout?.root?.isVisible = false
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressIndicator.show()
        errorLayout?.root?.isVisible = false
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        progressIndicator.show()
        errorLayout?.root?.isVisible = false
    }

    /**
     * Called when the page is ready.
     */
    private fun setImage() {
        if (extraPage == null) {
            progressIndicator.setProgress(0)
        } else {
            progressIndicator.setProgress(95)
        }
        errorLayout?.root?.isVisible = false

        unsubscribeReadImageHeader()
        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        readImageHeaderSubscription = Observable
            .fromCallable {
                val stream = streamFn().buffered(16)
                // SY -->
                val stream2 = if (extraPage != null) streamFn2?.invoke()?.buffered(16) else null
                val itemStream = if (viewer.config.dualPageSplit) {
                    process(item.first, stream)
                } else {
                    mergePages(stream, stream2)
                }
                // SY <--
                val bais = ByteArrayInputStream(itemStream.readBytes())
                try {
                    val isAnimated = ImageUtil.isAnimatedAndSupported(bais)
                    bais.reset()
                    val background = if (!isAnimated && viewer.config.automaticBackground) {
                        ImageUtil.chooseBackground(context, bais)
                    } else {
                        null
                    }
                    bais.reset()
                    Triple(bais, isAnimated, background)
                } finally {
                    stream.close()
                    itemStream.close()
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext { (bais, isAnimated, background) ->
                bais.use {
                    setImage(
                        it,
                        isAnimated,
                        Config(
                            zoomDuration = viewer.config.doubleTapAnimDuration,
                            minimumScaleType = viewer.config.imageScaleType,
                            cropBorders = viewer.config.imageCropBorders,
                            zoomStartPosition = viewer.config.imageZoomType,
                            landscapeZoom = viewer.config.landscapeZoom,
                        ),
                    )
                    if (!isAnimated) {
                        pageBackground = background
                    }
                }
            }
            .subscribe({}, {})
    }

    private fun process(page: ReaderPage, imageStream: BufferedInputStream): InputStream {
        if (!viewer.config.dualPageSplit) {
            return imageStream
        }

        if (page is InsertPage) {
            return splitInHalf(imageStream)
        }

        val isDoublePage = ImageUtil.isWideImage(imageStream)
        if (!isDoublePage) {
            return imageStream
        }

        onPageSplit(page)

        return splitInHalf(imageStream)
    }

    private fun mergePages(imageStream: InputStream, imageStream2: InputStream?): InputStream {
        // Handle adding a center margin to wide images if requested
        if (imageStream2 == null) {
            if (imageStream is BufferedInputStream && ImageUtil.isWideImage(imageStream) &&
                viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
                !viewer.config.imageCropBorders
            ) {
                return ImageUtil.AddHorizontalCenterMargin(imageStream, getHeight(), context)
            } else {
                return imageStream
            }
        }

        if (page.fullPage) return imageStream
        if (ImageUtil.isAnimatedAndSupported(imageStream)) {
            page.fullPage = true
            splitDoublePages()
            return imageStream
        } else if (ImageUtil.isAnimatedAndSupported(imageStream2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageStream
        }
        val imageBytes = imageStream.readBytes()
        val imageBitmap = try {
            ImageDecoder.newInstance(imageBytes.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot combine pages" }
            null
        }
        if (imageBitmap == null) {
            imageStream2.close()
            imageStream.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageBytes.inputStream()
        }
        viewer.scope.launchUI { progressIndicator.setProgress(96) }
        val height = imageBitmap.height
        val width = imageBitmap.width

        if (height < width) {
            imageStream2.close()
            imageStream.close()
            page.fullPage = true
            splitDoublePages()
            return imageBytes.inputStream()
        }

        val imageBytes2 = imageStream2.readBytes()
        val imageBitmap2 = try {
            ImageDecoder.newInstance(imageBytes2.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot combine pages" }
            null
        }
        if (imageBitmap2 == null) {
            imageStream2.close()
            imageStream.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageBytes.inputStream()
        }
        viewer.scope.launchUI { progressIndicator.setProgress(97) }
        val height2 = imageBitmap2.height
        val width2 = imageBitmap2.width

        if (height2 < width2) {
            imageStream2.close()
            imageStream.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageBytes.inputStream()
        }
        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages

        imageStream.close()
        imageStream2.close()

        val centerMargin = if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (max(1, getHeight()) / max(height, height2))
        } else {
            0
        }

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
            viewer.scope.launchUI {
                if (it == 100) {
                    progressIndicator.hide()
                } else {
                    progressIndicator.setProgress(it)
                }
            }
        }
    }

    private fun splitDoublePages() {
        viewer.scope.launchUI {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageStream: InputStream): InputStream {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) > 0 &&
            viewer.config.doublePages && !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageStream, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError() {
        progressIndicator.hide()
        showErrorLayout(withOpenInWebView = false)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError() {
        super.onImageLoadError()
        progressIndicator.hide()
        showErrorLayout(withOpenInWebView = true)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(withOpenInWebView: Boolean): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
            val imageUrl = page.imageUrl
            if (imageUrl.orEmpty().startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl!!)
                    context.startActivity(intent)
                }
            }
        }
        errorLayout?.actionOpenInWebView?.isVisible = withOpenInWebView
        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }
}
