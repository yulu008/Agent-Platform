package com.luyu.agent.service;

/**
 * 天气信息（只读 record）
 *
 * @param tempC           气温（摄氏度）
 * @param feelsLikeC      体感温度（摄氏度）
 * @param humidity        湿度（百分比）
 * @param weatherDesc     天气描述（英文，来自 wttr.in）
 * @param weatherCode     WWO 天气码（用于前端主题映射）
 * @param weatherIconUrl  天气图标 URL
 * @param windspeedKmph   风速（km/h）
 * @param winddir16Point  风向（16 方位，如 SE）
 * @param location        显示用地址标签（由 weather.location-label 配置注入）
 */
public record WeatherInfo(
        String tempC,
        String feelsLikeC,
        String humidity,
        String weatherDesc,
        String weatherCode,
        String weatherIconUrl,
        String windspeedKmph,
        String winddir16Point,
        String location
) {}
