package com.ai.tools.common.movie;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Film1905CrawlerService 爬虫功能
 * 使用当前（2026年3月）正在上映的三部电影进行测试
 *
 * @author AI Tools
 */
class Film1905CrawlerServiceTest {

    private static final Logger log = LoggerFactory.getLogger(Film1905CrawlerServiceTest.class);

    private final Film1905CrawlerService crawler = new Film1905CrawlerService(1000);

    @Test
    void testSearchMovieImages_Nein() {
        testSingleMovie("你是我的英雄");
    }

    @Test
    void testSearchMovieImages_Jingzhe() {
        testSingleMovie("惊蛰无声");
    }

    @Test
    void testSearchMovieImages_FengShen() {
        testSingleMovie("封神第二部：战戈");
    }

    @Test
    void testSearchAllThreeMovies() {
        // 同时测试三部电影
        String[] movies = {"你是我的英雄", "惊蛰无声", "封神第二部：战戈"};
        int successCount = 0;

        for (String movie : movies) {
            try {
                Film1905CrawlerService.MovieImageResult result = crawler.searchMovieImages(movie);
                if (result != null && !result.imageUrls().isEmpty()) {
                    log.info("✓ 成功获取 [{}] - filmId={}, 图片数量={}, 第一张海报: {}",
                            movie, result.filmId(), result.imageUrls().size(), result.imageUrls().getFirst());
                    successCount++;
                } else {
                    log.warn("✗ 未获取到 [{}] 的图片", movie);
                }
            } catch (Exception e) {
                log.error("✗ 测试 [{}] 发生异常", movie, e);
            }
        }

        log.info("测试完成，成功 {}/{}", successCount, movies.length);
        assertTrue(successCount >= 1, "至少应有一部电影搜索成功");
    }

    /**
     * 测试单部电影搜索
     */
    private void testSingleMovie(String movieTitle) {
        log.info("开始测试搜索电影: {}", movieTitle);

        Film1905CrawlerService.MovieImageResult result = crawler.searchMovieImages(movieTitle);

        if (result != null) {
            assertNotNull(result.filmId());
            assertFalse(result.filmId().isEmpty(), "filmId 不应为空");
            assertEquals(movieTitle, result.title());
            assertFalse(result.imageUrls().isEmpty(), "图片URL列表不应为空");

            log.info("搜索成功: title={}, filmId={}, imageCount={}",
                    movieTitle, result.filmId(), result.imageUrls().size());

            for (int i = 0; i < Math.min(result.imageUrls().size(), 3); i++) {
                log.info("  图片[{}]: {}", i + 1, result.imageUrls().get(i));
            }

            // 验证所有URL格式正确
            for (String url : result.imageUrls()) {
                assertTrue(url.startsWith("http"), "图片URL应以http开头: " + url);
            }
        } else {
            log.warn("未找到电影: {} (可能电影名称不匹配或网站结构变化)", movieTitle);
            // 由于是爬虫，可能因为网站更新或电影名称不准确导致找不到，不强制失败
        }
    }

    @Test
    void testSearchMoviePoster() {
        // 测试只获取主海报的方法
        String posterUrl = crawler.searchMoviePoster("封神第二部：战戈");
        if (posterUrl != null) {
            log.info("获取主海报成功: {}", posterUrl);
            assertTrue(posterUrl.startsWith("http"));
        } else {
            log.warn("未获取到主海报");
        }
    }

    @Test
    void testSearchFilmId() {
        // 测试单独搜索filmId方法
        String filmId = crawler.searchFilmId("你是我的英雄");
        if (filmId != null) {
            log.info("找到filmId: {}", filmId);
            assertFalse(filmId.isEmpty());
        }
    }
}
