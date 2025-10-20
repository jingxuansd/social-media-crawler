/*
 * Project: social-media-crawler
 *
 * File Created at 2025/10/20
 */
package com.jingxuansd.parser;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author jingxuan
 * @Type DouyinParser.java
 * @Desc
 * @date 2025/10/20 13:57
 */
public class DouyinParser {
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .cookieJar(new InMemoryCookieJar())
            .followRedirects(true)
            .build();

    private static final Gson gson = new Gson();
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9./]+");

    public static void main(String[] args) {
        for (int i = 0; i < 1; i++) {
            // 每个线程执行具体任务（通过Runnable封装）
            Thread thread = new Thread(new Task(i));
            // 启动线程（调用start()，而非直接调用run()）
            thread.start();
        }
    }

    static class Task implements Runnable {
        private int threadId;

        public Task(int threadId) {
            this.threadId = threadId;
        }

        @Override
        public void run() {
            // 这里编写具体的任务逻辑（例如请求处理、数据下载等）
            System.out.println("线程 " + threadId + " 开始执行");
            exec();
            System.out.println("线程 " + threadId + " 执行完毕");
        }

        public static void exec() {
            String shareText = "https://v.douyin.com/c4qMYXf93fk/";

            try {
                String videoUrl = getDouyinVideoUrl(shareText);
                if (videoUrl != null) {
                    System.out.println("----------------------------------------");
                    System.out.println("✅ 成功提取到下载地址:");
                    System.out.println(videoUrl);
                    System.out.println("----------------------------------------");
                } else {
                    System.out.println("❌ 提取失败。");
                }
            } catch (Exception e) {
                System.err.println("程序执行出错: " + e.getMessage());
                e.printStackTrace();
            }
        }

        public static String getDouyinVideoUrl(String shareText) throws IOException {
            Matcher matcher = SHARE_URL_PATTERN.matcher(shareText);
            if (!matcher.find()) {
                throw new IOException("错误：未在分享文案中找到有效的URL。");
            }
            String shortUrl = matcher.group(0);
            System.out.println("提取到的短链接: " + shortUrl);

            Request request = new Request.Builder()
                    .url(shortUrl)
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
                    .build();

            String html;
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("错误：请求最终页面失败。状态码: " + response.code() + ", URL: " + response.request().url());
                }

                // 一次性读取所有字节
                byte[] bytes = response.body().bytes();
                System.out.println("HTML 大小：" + bytes.length + " 字节");

                // 转换为字符串
                html = new String(bytes, StandardCharsets.UTF_8);
                System.out.println("成功获取到最终页面内容，URL: " + response.request().url());
            }

            Document doc = Jsoup.parse(html);

            // --- 关键修改 1: 寻找新的数据位置 ---
            // 遍历所有 script 标签，找到包含 "window._ROUTER_DATA" 的那个
            String jsonData = null;
            for (Element scriptElement : doc.select("script")) {
                String scriptContent = scriptElement.data();
                if (scriptContent.contains("window._ROUTER_DATA")) {
                    // 找到了！现在需要从中提取出 JSON 部分
                    // 移除 "window._ROUTER_DATA = " 部分
                    jsonData = scriptContent.replaceFirst("window._ROUTER_DATA = ", "");
                    break;
                }
            }

            if (jsonData == null) {
                throw new IOException("在HTML中未找到 '_ROUTER_DATA' 数据块。页面结构可能再次变更。");
            }

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> data = gson.fromJson(jsonData, type);

            try {
                // --- 关键修改 2: 使用新的 JSON 路径进行解析 ---
                Map<String, Object> loaderData = (Map<String, Object>) data.get("loaderData");
                Map<String, Object> pageData = (Map<String, Object>) loaderData.get("video_(id)/page");
                Map<String, Object> videoInfoRes = (Map<String, Object>) pageData.get("videoInfoRes");
                List<Map<String, Object>> itemList = (List<Map<String, Object>>) videoInfoRes.get("item_list");

                if (itemList == null || itemList.isEmpty()) {
                    throw new IOException("解析失败：JSON中 'item_list' 为空或不存在。");
                }

                Map<String, Object> firstItem = itemList.get(0);
                Map<String, Object> videoData = (Map<String, Object>) firstItem.get("video");
                Map<String, Object> playAddr = (Map<String, Object>) videoData.get("play_addr");
                List<String> urlList = (List<String>) playAddr.get("url_list");
                String originalUrl = urlList.get(0);

                // 抖音现在返回的直接就是无水印地址，但为保险起见，替换逻辑可以保留
                return originalUrl.replace("/playwm/", "/play/");
            } catch (ClassCastException | NullPointerException e) {
                System.err.println("解析JSON数据结构时出错，可能是抖音页面结构已更新。");
                throw new IOException("JSON navigation failed.", e);
            }
        }
    }
}