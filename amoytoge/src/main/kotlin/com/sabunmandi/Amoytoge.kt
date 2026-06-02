package com.sabunmandi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.sabunmandi.common.ExtractorUtils
import java.net.URI

// https://perbokepan.xyz/
// https://194.233.78.131
// https://bokepina.cc
// avtub.app
class Amoytoge : MainAPI() {
    override var mainUrl = "https://amoytoge.beauty/"
    override var name = "Amoytoge"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override val hasDownloadSupport = true

    override val mainPage =
            mainPageOf(
                    "$mainUrl/?filter=latest" to "Latest",
                    "$mainUrl/?filter=most-viewed" to "Most Viewed",
                    "$mainUrl/?filter=random" to "Random"
            )

    // Helper extension function
    fun String?.fixUrl(): String {
        return when {
            this.isNullOrBlank() -> ""
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }.replace("/(?<=[^:]/)".toRegex(), "/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // val targetUrl = "${request.data}/page/${page}/"

        // Parse base URL and existing query parameters
        val (basePath, queryParams) =
                request.data.split("?", limit = 2).let {
                    it[0].removeSuffix("/") to it.getOrNull(1)
                }

        // Build paginated URL
        val targetUrl = buildString {
            append(basePath)
            if (page > 1) append("/page/$page")
            append("/") // Ensure trailing slash
            if (!queryParams.isNullOrEmpty()) append("?$queryParams")
        }

        println("DEBUG : TARGET URL :  $targetUrl : PAGE : $page")

        val document = app.get(targetUrl).document

        val items =
                document.select(".site-main article").mapNotNull { article ->
                    val href = article.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val title =
                            article.selectFirst(".post-thumbnail img")?.attr("alt") ?: "No Title"
                    val poster =
                            article.selectFirst(".post-thumbnail img")?.let { img ->
                                img.attr("data-src").takeIf { it.isNotEmpty() }
                                        ?: img.attr("src").takeIf { it.isNotEmpty() }
                            }

                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }

        // Pagination detection (same for all categories)
        // val hasNext = document.select("ul.pagination").let { pagination ->
        //     pagination?.last()?.select("li:nth-last-child(2)
        // a:contains(Next)")?.firstOrNull()?.let {
        //         it.text().equals("Next", ignoreCase = true) && it.attr("href").contains("/page/")
        //     } ?: false
        // }

        val hasNext =
                document.select(".pagination ul").any { pagination ->
                    pagination.select("a").any { link ->
                        link.text().equals("Next", ignoreCase = true) &&
                                !link.hasClass("disabled") &&
                                link.attr("href").contains("page")
                    }
                }

        println("DEBUG : HAS NEXT :  $hasNext")

        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = hasNext)
    }

    // =========================== Search ===========================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl).document

        return document.select(".video-item").mapNotNull {
            val href = it.selectFirst("a")?.attr("href")?.trim() ?: return@mapNotNull null
            val title = it.selectFirst("img")?.attr("alt")?.trim() ?: "No Title"
            val poster = it.selectFirst("img")?.attr("src")?.trim()

            newMovieSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.Movie,
            ) {
                this.posterUrl = poster
                // this.apiName = this@ExampleSitePlugin.name
            }
        }
    }

    // =========================== Load Links ===========================
    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        // Extract metadata from video-player div
        val videoPlayer =
                document.selectFirst(".video-player")
                        ?: throw ErrorLoadingException("Video player not found")

        // Get title from itemprop="name"
        val title =
                videoPlayer.select("meta[itemprop=name]").attr("content").trim().ifEmpty {
                    document.selectFirst("h1.title")?.text()?.trim()
                }
                        ?: "No Title"

        // Get poster from itemprop="thumbnailUrl"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.trim()

        // Get description (example additional field)
        val description =
                document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // Extract video URL (from previous implementation)
        // if (videoUrl.isNullOrEmpty()) {
        //     println("DEBUG - No data video URL found! : $url | $videoUrl")
        // }
        // val videoUrl = document.selectFirst("video source")?.attr("src")?.trim()
        // ?: document.selectFirst("iframe")?.attr("src")?.trim()
        // ?: throw ErrorLoadingException("No video found")
        // val videoUrl = document.selectFirst(".video-player iframe")?.attr("src")?.trim() ?: ""
        val initialIframeUrl =
                document.selectFirst(".responsive-player iframe")?.attr("data-lzl-src")?.fixUrl()
                        ?: throw ErrorLoadingException("No video iframe found")
        val videoUrl = initialIframeUrl
        // val videoUrl = resolveNestedIframe(initialIframeUrl)

        return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = videoUrl,
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {

            println("DEBUG : URL : $data")
            val manualCheck = ExtractorUtils.tryManualCheck(data)
            if (manualCheck != null) {
                val typeVideo =
                        when {
                            manualCheck.contains(".mp4") -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.M3U8
                        }

                callback.invoke(
                        ExtractorLink(
                                source = name,
                                name = "CDN Stream",
                                url = manualCheck,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = typeVideo,
                        )
                )
                return true
            }
            val isExtractorHost =
                    data.contains("streamsb") ||
                            data.contains("dood") ||
                            data.contains("filemoon") ||
                            data.contains("lulu")

            if (!isExtractorHost) {
                println("DEBUG: Manual Check Failed try webresolver")
                val resolver =
                        WebViewResolver(
                                interceptUrl = Regex("""(m3u8|master\.txt)"""),
                                additionalUrls = listOf(Regex("""(m3u8|master\.txt)""")),
                                useOkhttp = false,
                                timeout = 15_000L
                        )
                val interceptedUrl = app.get(url = data, interceptor = resolver).url
                if (!interceptedUrl.isNullOrEmpty()) {
                    println("DEBUG: found webresolver : $interceptedUrl")
                    callback.invoke(
                            ExtractorLink(
                                    source = name,
                                    name = "CDN Stream",
                                    url = interceptedUrl,
                                    referer = mainUrl,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8,
                            )
                    )
                    return true
                }
            }
            println("DEBUG: Manual check and webresolver not found try using loadExtractor")
            return loadExtractor(data, mainUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            println("ERROR - ${e.javaClass.simpleName}")
            println("ERROR - Message: ${e.message?.take(50)}")
            println("ERROR - ${e.stackTraceToString()}")
            return false
        }
    }

    private suspend fun resolveRedirects(initialUrl: String, maxRedirects: Int = 5): String {
        var currentUrl = initialUrl
        repeat(maxRedirects) {
            val response = app.get(currentUrl, allowRedirects = false)
            when (response.code) {
                in 300..399 -> {
                    println("DEBUG - REDIRECT TO : ${response.headers}")
                    currentUrl = response.headers["Location"]?.fixUrl() ?: return currentUrl
                }
                else -> return response.url
            }
        }
        return currentUrl
    }

    private suspend fun resolveNestedIframe(url: String, depth: Int = 3): String {
        println("Resolving iframe (depth ${4 - depth}): $url")
        if (depth <= 0) throw ErrorLoadingException("Maximum iframe depth reached")

        val doc = app.get(url, referer = mainUrl).document

        val newUrl = doc.selectFirst("iframe")?.attr("src")?.fixUrl()
        if (!newUrl.isNullOrEmpty()) {
            return resolveNestedIframe(newUrl, depth - 1)
        }

        return url
    }
}
