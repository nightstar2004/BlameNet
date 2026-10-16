package com.blamenet.util;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具。
 * <p>
 * 项目严格遵循“JSON 是唯一主数据源”：所有报表（HTML/PDF/MD/TXT/XLSX/README/manifest）
 * 都由这里序列化出的 JSON 反序列化后再生成，导出层不允许再次采集数据。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final DefaultPrettyPrinter PRINTER = buildPrinter();

    private JsonUtil() {
    }

    private static DefaultPrettyPrinter buildPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("    ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writer(PRINTER).writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static String toCompactJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PowerShell 采集到的 JSON 归一化为“对象列表”。
     * <p>
     * PowerShell 5.1 的 ConvertTo-Json 在结果只有一条记录时可能输出单个对象而不是数组，
     * 该差异在此统一抹平，保证采集层行为一致。
     */
    public static List<Map<String, Object>> toObjectList(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (TextUtil.isBlank(json)) {
            return result;
        }
        String text = json.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return result;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception e) {
            return result;
        }
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode element : array) {
                result.add(toMap(element));
            }
        } else if (node.isObject()) {
            result.add(toMap(node));
        }
        return result;
    }

    /** 将 JSON 对象转换为 Map，值保持原始类型（字符串、数字、布尔、嵌套列表）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                map.put(entry.getKey(), null);
            } else if (value.isTextual()) {
                map.put(entry.getKey(), value.asText());
            } else if (value.isBoolean()) {
                map.put(entry.getKey(), value.asBoolean());
            } else if (value.isIntegralNumber()) {
                map.put(entry.getKey(), value.asLong());
            } else if (value.isFloatingPointNumber()) {
                map.put(entry.getKey(), value.asDouble());
            } else if (value.isArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonNode element : value) {
                    if (element.isObject()) {
                        list.add(toMap(element));
                    } else if (element.isTextual()) {
                        list.add(element.asText());
                    } else if (element.isNumber()) {
                        list.add(element.asDouble());
                    } else if (element.isBoolean()) {
                        list.add(element.asBoolean());
                    } else {
                        list.add(element.asText());
                    }
                }
                map.put(entry.getKey(), list);
            } else {
                map.put(entry.getKey(), toMap(value));
            }
        });
        return map;
    }
}
