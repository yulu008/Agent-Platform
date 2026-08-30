package com.luyu.agent.controller;

import com.luyu.agent.service.WeatherInfo;
import com.luyu.agent.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 天气查询 REST API
 *
 * 提供页面顶部天气条所需的数据，供前端页面加载时拉取。
 * 路径前缀：/api/weather
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private static final Logger log = LoggerFactory.getLogger(WeatherController.class);

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 获取当前天气
     *
     * @return 天气信息 JSON，wttr.in 不可达时返回 {"error": "weather unavailable"}（HTTP 200）
     */
    @GetMapping
    public Object getWeather() {
        WeatherInfo weather = weatherService.fetchWeather();
        if (weather == null) {
            log.warn("天气数据不可达，返回降级响应");
            return Map.of("error", "weather unavailable");
        }
        return weather;
    }
}
