package com.luyu.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 天气数据服务
 *
 * 通过 JDK HttpClient 调用 wttr.in 的 format=j1 接口获取天气数据，
 * 解析后封装为 WeatherInfo。查询地址与显示标签由配置文件 weather.* 注入。
 *
 * 容错策略：任何异常（超时、IO、解析失败）均 catch 并返回 null，
 * 不向调用方抛出异常，确保天气功能故障不影响核心对话链路。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** 查询地址（wttr.in format=j1 接口），由 weather.url 配置注入 */
    private final String weatherUrl;

    /** 显示用地址标签，由 weather.location-label 配置注入 */
    private final String locationLabel;

    /** 连接与请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WeatherService(
            @Value("${weather.url:https://wttr.in/~31.253,121.397?format=j1}") String weatherUrl,
            @Value("${weather.location-label:上海市普陀区清涧路}") String locationLabel) {
        this.weatherUrl = weatherUrl;
        this.locationLabel = locationLabel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取当前天气
     *
     * @return 天气信息，wttr.in 不可达或解析失败时返回 null
     */
    public WeatherInfo fetchWeather() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("wttr.in 返回非 200 状态码: {}", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode current = root.path("current_condition").path(0);
            if (current.isMissingNode()) {
                log.warn("wttr.in 返回缺少 current_condition 字段");
                return null;
            }

            return new WeatherInfo(
                    current.path("temp_C").asText(),
                    current.path("FeelsLikeC").asText(),
                    current.path("humidity").asText(),
                    current.path("weatherDesc").path(0).path("value").asText(),
                    current.path("weatherCode").asText(),
                    current.path("weatherIconUrl").path(0).path("value").asText(),
                    current.path("windspeedKmph").asText(),
                    current.path("winddir16Point").asText(),
                    locationLabel
            );
        } catch (Exception e) {
            log.warn("获取天气失败: {}", e.getMessage());
            return null;
        }
    }
}
