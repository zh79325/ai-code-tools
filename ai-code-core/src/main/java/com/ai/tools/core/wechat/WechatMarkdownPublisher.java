package com.ai.tools.core.wechat;

import com.ai.tools.common.exception.BizException;
import okhttp3.*;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 微信公众号Markdown文章发布工具
 * 将本地Markdown文件上传为公众号草稿，自动上传本地图片到素材库
 */
public class WechatMarkdownPublisher {

    private static final Logger logger = LoggerFactory.getLogger(WechatMarkdownPublisher.class);

    private final String appId;
    private final String appSecret;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    // access_token缓存
    private String accessToken;
    private long accessTokenExpireTime;

    // API地址
    private static final String API_TOKEN = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String API_UPLOAD_IMAGE = "https://api.weixin.qq.com/cgi-bin/material/add_material";
    private static final String API_ADD_DRAFT = "https://api.weixin.qq.com/cgi-bin/draft/add";

    // 匹配Markdown图片格式: ![alt](path)
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");

    public WechatMarkdownPublisher(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.markdownParser = Parser.builder().build();
        this.htmlRenderer = HtmlRenderer.builder().build();
    }

    /**
     * 发布Markdown文件为公众号草稿
     * @param markdownFilePath 本地Markdown文件路径
     * @param title 文章标题
     * @return 创建结果，包含mediaId
     * @throws BizException 业务异常
     */
    public PublishResult publishDraft(String markdownFilePath, String title) {
        try {
            logger.info("开始发布Markdown文件: {}, 标题: {}", markdownFilePath, title);

            // 1. 读取Markdown文件
            String markdownContent = readMarkdownFile(markdownFilePath);
            String baseDir = getBaseDir(markdownFilePath);

            // 2. 提取并上传图片，替换链接
            markdownContent = processImages(markdownContent, baseDir);

            // 3. Markdown转HTML
            String htmlContent = markdownToHtml(markdownContent);

            // 4. 获取access_token
            String accessToken = getAccessToken();

            // 5. 创建草稿
            String mediaId = createDraft(accessToken, title, htmlContent);

            logger.info("草稿创建成功, mediaId: {}", mediaId);
            return new PublishResult(mediaId, title);

        } catch (Exception e) {
            logger.error("发布草稿失败", e);
            throw new BizException("发布草稿失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取Markdown文件
     */
    private String readMarkdownFile(String filePath) throws IOException {
        return Files.readString(Paths.get(filePath));
    }

    /**
     * 获取文件所在目录，用于处理相对路径
     */
    private String getBaseDir(String filePath) {
        File file = new File(filePath);
        return file.getParent();
    }

    /**
     * 处理图片：提取本地图片，上传，替换链接
     */
    private String processImages(String markdown, String baseDir) throws IOException {
        StringBuffer result = new StringBuffer();
        Matcher matcher = IMAGE_PATTERN.matcher(markdown);

        while (matcher.find()) {
            String imagePath = matcher.group(1).trim();

            // 判断是否是本地图片（不是http开头就是本地路径）
            if (!imagePath.startsWith("http://") && !imagePath.startsWith("https://")) {
                String fullPath = resolveFullPath(imagePath, baseDir);
                File imageFile = new File(fullPath);

                if (imageFile.exists() && imageFile.isFile()) {
                    logger.info("上传本地图片: {}", fullPath);
                    String accessToken = getAccessToken();
                    ImageResult imageResult = uploadImage(accessToken, imageFile);
                    // 替换为微信URL
                    matcher.appendReplacement(result, "![](" + imageResult.getUrl() + ")");
                    logger.info("图片上传成功: {}", imageResult.getUrl());
                } else {
                    logger.warn("图片文件不存在: {}, 保持原链接", fullPath);
                    matcher.appendReplacement(result, "![](" + imagePath + ")");
                }
            } else {
                // 网络图片保持不变
                matcher.appendReplacement(result, "![](" + imagePath + ")");
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 解析完整路径
     */
    private String resolveFullPath(String imagePath, String baseDir) {
        File file = new File(imagePath);
        if (file.isAbsolute()) {
            return imagePath;
        }
        return baseDir + File.separator + imagePath;
    }

    /**
     * 上传图片到素材库
     */
    private ImageResult uploadImage(String accessToken, File imageFile) throws IOException {
        String url = API_UPLOAD_IMAGE + "?access_token=" + accessToken + "&type=image";

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.parse("image/*")))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("上传图片失败: " + response);
            }

            String responseBody = response.body().string();
            JsonNode json = objectMapper.readTree(responseBody);

            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                throw new IOException("上传图片失败: " + json.get("errmsg").asText());
            }

            String mediaId = json.get("media_id").asText();
            String imageUrl = json.get("url").asText();
            return new ImageResult(mediaId, imageUrl);
        }
    }

    /**
     * Markdown转HTML
     */
    private String markdownToHtml(String markdown) {
        Node document = markdownParser.parse(markdown);
        return htmlRenderer.render(document);
    }

    /**
     * 获取access_token，带缓存
     */
    public String getAccessToken() throws IOException {
        // 检查缓存是否有效（提前5分钟过期）
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpireTime - 300000) {
            return accessToken;
        }

        String url = API_TOKEN + "?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取access_token失败: " + response);
            }

            String responseBody = response.body().string();
            JsonNode json = objectMapper.readTree(responseBody);

            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                throw new IOException("获取access_token失败: " + json.get("errmsg").asText());
            }

            accessToken = json.get("access_token").asText();
            int expiresIn = json.get("expires_in").asInt();
            accessTokenExpireTime = System.currentTimeMillis() + expiresIn * 1000L;

            logger.info("获取access_token成功，有效期: {}秒", expiresIn);
            return accessToken;
        }
    }

    /**
     * 创建草稿
     */
    private String createDraft(String accessToken, String title, String content) throws IOException {
        String url = API_ADD_DRAFT + "?access_token=" + accessToken;

        // 构建请求JSON
        String json = String.format("""
                {
                  "articles": [
                    {
                      "title": "%s",
                      "content": "%s"
                    }
                  ]
                }
                """, escapeJson(title), escapeJson(content));

        RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("创建草稿失败: " + response);
            }

            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            if (jsonNode.has("errcode") && jsonNode.get("errcode").asInt() != 0) {
                throw new IOException("创建草稿失败: " + jsonNode.get("errmsg").asText());
            }

            return jsonNode.get("media_id").asText();
        }
    }

    /**
     * 转义JSON中的特殊字符
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 图片上传结果
     */
    public static class ImageResult {
        private final String mediaId;
        private final String url;

        public ImageResult(String mediaId, String url) {
            this.mediaId = mediaId;
            this.url = url;
        }

        public String getMediaId() {
            return mediaId;
        }

        public String getUrl() {
            return url;
        }
    }

    /**
     * 发布结果
     */
    public static class PublishResult {
        private final String mediaId;
        private final String title;

        public PublishResult(String mediaId, String title) {
            this.mediaId = mediaId;
            this.title = title;
        }

        public String getMediaId() {
            return mediaId;
        }

        public String getTitle() {
            return title;
        }
    }
}
