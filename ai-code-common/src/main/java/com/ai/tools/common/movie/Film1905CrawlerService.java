package com.ai.tools.common.movie;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1905电影网爬虫工具类
 * 基于电影标题检索电影海报和剧照
 * 使用 OkHttp + Jsoup 实现网页抓取和解析
 *
 * @author AI Tools
 */
public class Film1905CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(Film1905CrawlerService.class);
    private static final String FILM1905_SEARCH_URL = "https://m.1905.com/m/search/?q=";
    private static final String FILM1905_STILL_URL = "https://www.1905.com/mdb/film/%s/still/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long DEFAULT_REQUEST_DELAY_MS = 2000;

    private final OkHttpClient okHttpClient;
    private final long requestDelayMs;

    /**
     * 默认构造方法
     * 使用默认的请求延迟（2秒）
     */
    public Film1905CrawlerService() {
        this(DEFAULT_REQUEST_DELAY_MS);
    }

    /**
     * 构造方法，允许自定义请求延迟
     *
     * @param requestDelayMs 请求延迟（毫秒），用于控制请求频率
     */
    public Film1905CrawlerService(long requestDelayMs) {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.requestDelayMs = requestDelayMs;
    }

    /**
     * 使用自定义OkHttpClient构造
     *
     * @param okHttpClient 自定义OkHttpClient实例
     * @param requestDelayMs 请求延迟（毫秒）
     */
    public Film1905CrawlerService(OkHttpClient okHttpClient, long requestDelayMs) {
        this.okHttpClient = okHttpClient;
        this.requestDelayMs = requestDelayMs;
    }

    /**
     * 搜索电影，返回电影信息和所有图片（海报+剧照）URL列表
     *
     * @param movieTitle 电影标题
     * @return 搜索结果，包含电影ID、标题和图片URL列表，未找到返回null
     */
    public MovieImageResult searchMovieImages(String movieTitle) {
        log.info("1905网搜索电影图片: title={}", movieTitle);

        try {
            // Step 1: 搜索获取filmId
            String filmId = searchFilmId(movieTitle);
            if (filmId == null) {
                log.warn("1905网未找到电影: title={}", movieTitle);
                return null;
            }
            log.info("1905网找到电影ID: title={}, filmId={}", movieTitle, filmId);

            // Step 2: 从剧照页找到海报详情页链接或直接提取图片
            String galleryResult = findPosterGalleryUrl(filmId);
            if (galleryResult == null) {
                log.warn("1905网未找到海报图片: title={}, filmId={}", movieTitle, filmId);
                return null;
            }

            // Step 3: 从海报详情页提取高清原图URL
            List<String> posterImages = fetchPosterImages(galleryResult);
            if (posterImages.isEmpty()) {
                log.warn("1905网未提取到海报图片: title={}, galleryResult={}", movieTitle, galleryResult);
                return null;
            }

            MovieImageResult result = new MovieImageResult(filmId, movieTitle, posterImages);
            log.info("1905网获取电影图片成功: title={}, filmId={}, imageCount={}",
                    movieTitle, filmId, posterImages.size());
            return result;
        } catch (Exception e) {
            log.error("1905网搜索电影图片失败: title={}", movieTitle, e);
            return null;
        }
    }

    /**
     * 只搜索电影主海报URL
     *
     * @param movieTitle 电影标题
     * @return 主海报URL，未找到返回null
     */
    public String searchMoviePoster(String movieTitle) {
        MovieImageResult result = searchMovieImages(movieTitle);
        if (result == null || result.imageUrls().isEmpty()) {
            return null;
        }
        return result.imageUrls().getFirst();
    }

    /**
     * Step 1: 通过电影名称搜索1905网获取电影ID
     *
     * @param movieTitle 电影名称
     * @return 电影ID（filmId），未找到返回null
     */
    public String searchFilmId(String movieTitle) {
        try {
            String encodedTitle = URLEncoder.encode(movieTitle, StandardCharsets.UTF_8);
            String searchUrl = FILM1905_SEARCH_URL + encodedTitle;
            String html = fetchHtml(searchUrl);
            Document doc = parseDocument(html);

            // 解析搜索结果列表
            Elements movieItems = doc.select("section.moiveResources-module ul li");
            if (movieItems.isEmpty()) {
                log.debug("1905网搜索无结果: title={}", movieTitle);
                return null;
            }

            // 第一轮：精确匹配（名称完全一致）
            for (Element item : movieItems) {
                String resultTitle = extractResultTitle(item);
                if (resultTitle == null) {
                    continue;
                }

                String filmId = extractFilmIdFromItem(item);
                if (filmId != null && resultTitle.equals(movieTitle)) {
                    log.debug("1905网精确匹配成功: title={}, resultTitle={}, filmId={}", movieTitle, resultTitle, filmId);
                    return filmId;
                }
            }

            // 第二轮：包含匹配（名称包含关系，要求至少有50%的字符重叠）
            for (Element item : movieItems) {
                String resultTitle = extractResultTitle(item);
                if (resultTitle == null) {
                    continue;
                }

                String filmId = extractFilmIdFromItem(item);
                if (filmId != null && (resultTitle.contains(movieTitle) || movieTitle.contains(resultTitle))) {
                    log.debug("1905网包含匹配成功: title={}, resultTitle={}, filmId={}", movieTitle, resultTitle, filmId);
                    return filmId;
                }
            }

            // 不再兜底取第一个结果，避免返回完全不相关的电影
            log.warn("1905网搜索结果中未找到匹配的电影: title={}, resultCount={}", movieTitle, movieItems.size());
            return null;
        } catch (Exception e) {
            log.error("1905网搜索电影ID失败: title={}", movieTitle, e);
            return null;
        }
    }

    /**
     * Step 2: 访问剧照页面，找到"海报"或"剧照"分类区域的图片
     * 优先查找"海报"分类的详情页链接，找不到则降级提取"剧照"区域的所有图片原图URL
     *
     * @param filmId 电影ID
     * @return 结果字符串（海报详情页URL / stills:前缀表示多个原图URL / og:前缀表示单张图片），未找到返回null
     */
    public String findPosterGalleryUrl(String filmId) {
        try {
            String stillUrl = String.format(FILM1905_STILL_URL, filmId);
            String html = fetchHtml(stillUrl);
            Document doc = parseDocument(html);

            // 查找所有图片分类区域
            Elements picSections = doc.select("div.secPag-pics");

            // 第一轮：查找标题包含"海报"的区域
            for (Element section : picSections) {
                Element titleElement = section.selectFirst("h3.title-common");
                if (titleElement == null) {
                    continue;
                }

                String sectionTitle = titleElement.text().trim();
                if (sectionTitle.contains("海报")) {
                    Element firstLink = section.selectFirst("ul.secPag-pics-list li a");
                    if (firstLink != null) {
                        String href = firstLink.attr("href");
                        if (!href.isEmpty()) {
                            if (!href.startsWith("http")) {
                                href = "https://www.1905.com" + href;
                            }
                            return href;
                        }
                    }
                }
            }

            // 第二轮：降级提取"剧照"或任意图片区域的所有缩略图，转换为原图URL
            for (Element section : picSections) {
                Elements imgElements = section.select("ul.secPag-pics-list li img");
                if (!imgElements.isEmpty()) {
                    List<String> originalUrls = new ArrayList<>();
                    for (Element img : imgElements) {
                        String src = img.attr("src");
                        if (!src.isEmpty()) {
                            String originalUrl = convertToOriginalUrl(src);
                            originalUrls.add(originalUrl);
                        }
                    }
                    if (!originalUrls.isEmpty()) {
                        log.info("1905网降级提取剧照区域图片: filmId={}, count={}", filmId, originalUrls.size());
                        return "stills:" + String.join(",", originalUrls);
                    }
                }
            }

            // 第三轮：兜底使用 meta[property=og:image] 中的海报URL
            Element ogImage = doc.selectFirst("meta[property=og:image]");
            if (ogImage != null) {
                String ogImageUrl = ogImage.attr("content");
                if (!ogImageUrl.isEmpty()) {
                    String originalUrl = convertToOriginalUrl(ogImageUrl);
                    log.info("1905网降级使用og:image: filmId={}, url={}", filmId, originalUrl);
                    return "og:" + originalUrl;
                }
            }

            return null;
        } catch (Exception e) {
            log.error("1905网查找海报详情页失败: filmId={}", filmId, e);
            return null;
        }
    }

    /**
     * Step 3: 从海报详情页提取高清海报原图URL列表
     *
     * @param galleryResult 结果字符串（来自findPosterGalleryUrl）
     * @return 海报原图URL列表
     */
    public List<String> fetchPosterImages(String galleryResult) {
        List<String> imageUrls = new ArrayList<>();

        try {
            // 处理降级方案：stills: 前缀表示已从剧照列表页提取的原图URL列表
            if (galleryResult.startsWith("stills:")) {
                String urlList = galleryResult.substring(7);
                for (String url : urlList.split(",")) {
                    String trimmed = url.trim();
                    if (!trimmed.isEmpty()) {
                        imageUrls.add(trimmed);
                    }
                }
                log.info("1905网使用剧照列表原图: count={}", imageUrls.size());
                return imageUrls;
            }

            // 处理降级方案：og:image 直接返回图片URL
            if (galleryResult.startsWith("og:")) {
                String directUrl = galleryResult.substring(3);
                imageUrls.add(directUrl);
                return imageUrls;
            }

            String html = fetchHtml(galleryResult);
            Document doc = parseDocument(html);

            // 从海报详情页提取原图URL
            // HTML结构: div.pic_img_gallery a 的 href 属性包含原图URL
            Elements galleryLinks = doc.select("div.pic_img_gallery a");
            for (Element link : galleryLinks) {
                String href = link.attr("href");
                if (!href.isEmpty() && isImageUrl(href)) {
                    imageUrls.add(href);
                }
            }

            // 备选方案：如果上面没找到，尝试从 img 标签获取
            if (imageUrls.isEmpty()) {
                Elements imgElements = doc.select("div.pic_img_gallery img");
                for (Element img : imgElements) {
                    String src = img.attr("src");
                    if (!src.isEmpty() && isImageUrl(src)) {
                        imageUrls.add(src);
                    }
                }
            }

            // 再备选：尝试从页面中所有大图链接获取
            if (imageUrls.isEmpty()) {
                Elements allLinks = doc.select("a[href]");
                for (Element link : allLinks) {
                    String href = link.attr("href");
                    if (!href.isEmpty() && href.contains("image") && href.contains("m1905.cn") && isImageUrl(href)) {
                        imageUrls.add(href);
                    }
                }
            }

            log.info("1905网提取海报图片: galleryUrl={}, count={}", galleryResult, imageUrls.size());
        } catch (Exception e) {
            log.error("1905网提取海报图片失败: galleryUrl={}", galleryResult, e);
        }

        return imageUrls;
    }

    /**
     * 使用OkHttp发送HTTP请求获取HTML
     *
     * @param url 请求URL
     * @return HTML内容
     */
    private String fetchHtml(String url) throws Exception {
        if (requestDelayMs > 0) {
            Thread.sleep(requestDelayMs); // 控制请求频率
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", "https://www.1905.com/")
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("请求失败: " + response.code() + ", url=" + url);
            }
            return response.body().string();
        }
    }

    /**
     * 使用Jsoup解析HTML文档
     *
     * @param html HTML内容
     * @return Jsoup Document对象
     */
    private Document parseDocument(String html) {
        return Jsoup.parse(html);
    }

    /**
     * 从搜索结果的单个li元素中提取电影名称
     * 始终取 a 标签的完整文本作为电影名称（span.active 只是高亮部分，不是完整名称）
     *
     * @param item 搜索结果li元素
     * @return 电影名称，未找到返回null
     */
    private String extractResultTitle(Element item) {
        Element nameLink = item.selectFirst("h3.moiveName a");
        if (nameLink == null) {
            return null;
        }
        return nameLink.text().trim();
    }

    /**
     * 从搜索结果的单个li元素中提取filmId
     * 优先从 h3.moiveName a 的href提取，如果href不是/mdb/film/格式（如播放链接），
     * 则降级从同一li中的 a.detailBtn 链接提取filmId
     *
     * @param item 搜索结果li元素
     * @return filmId，未找到返回null
     */
    private String extractFilmIdFromItem(Element item) {
        // 优先从电影名称链接提取
        Element nameLink = item.selectFirst("h3.moiveName a");
        if (nameLink != null) {
            String filmId = extractFilmId(nameLink.attr("href"));
            if (filmId != null) {
                return filmId;
            }
        }

        // 降级：从详情按钮链接提取（部分电影名称链接是播放链接，不含filmId）
        Element detailBtn = item.selectFirst("a.detailBtn");
        if (detailBtn != null) {
            String filmId = extractFilmId(detailBtn.attr("href"));
            if (filmId != null) {
                return filmId;
            }
        }

        return null;
    }

    /**
     * 从URL中提取1905电影ID
     * 支持格式：https://www.1905.com/mdb/film/2258111/ 或 /mdb/film/2258111/
     *
     * @param url URL字符串
     * @return 电影ID
     */
    private String extractFilmId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        Pattern pattern = Pattern.compile("/mdb/film/(\\d+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * 将1905网缩略图URL转换为原图URL
     * 缩略图格式: .../thumb_X_XXX_XXX_20240612122031595559.jpg
     * 原图格式:   .../20240612122031595559.jpg
     * 规则：去掉文件名中的 thumb_数字_数字_数字_ 前缀
     *
     * @param thumbnailUrl 缩略图URL
     * @return 原图URL，如果不是缩略图格式则原样返回
     */
    private String convertToOriginalUrl(String thumbnailUrl) {
        if (thumbnailUrl == null || thumbnailUrl.isEmpty()) {
            return thumbnailUrl;
        }
        // 匹配 thumb_数字_数字_数字_ 前缀并去除
        return thumbnailUrl.replaceAll("thumb_\\d+_\\d+_\\d+_", "");
    }

    /**
     * 判断URL是否为图片URL
     *
     * @param url URL字符串
     * @return true-是图片URL
     */
    private boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        return lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")
                || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".webp")
                || lowerUrl.endsWith(".gif")
                || lowerUrl.contains("uploadfile");
    }

    /**
     * 电影图片搜索结果记录
     *
     * @param filmId 1905电影ID
     * @param title 电影标题
     * @param imageUrls 图片URL列表（第一张通常是海报）
     */
    public record MovieImageResult(String filmId, String title, List<String> imageUrls) {}
}
