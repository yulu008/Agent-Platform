package com.luyu.agent.tools;

import com.luyu.agent.service.WeatherInfo;
import com.luyu.agent.service.WeatherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具，暴露给主智能体调用。
 * <p>
 * 调用 WeatherService 获取上海市普陀区清涧路的实时天气，
 * 返回自然语言描述供智能体回答用户天气相关问题。
 */
@Component
public class WeatherTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(description = "查询当前实时天气。返回气温、体感温度、湿度、天气状况、风速与风向。用于回答用户关于天气的问题。")
    public String getWeather() {
        WeatherInfo weather = weatherService.fetchWeather();
        if (weather == null) {
            return "天气服务暂时不可用，请稍后再试。";
        }
        return String.format(
                "%s 当前天气：%s，气温 %s°C，体感 %s°C，湿度 %s%%，风速 %s km/h，风向 %s。",
                weather.location(),
                weather.weatherDesc(),
                weather.tempC(),
                weather.feelsLikeC(),
                weather.humidity(),
                weather.windspeedKmph(),
                weather.winddir16Point()
        );
    }
}
