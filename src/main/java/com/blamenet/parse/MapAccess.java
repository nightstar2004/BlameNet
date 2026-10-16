package com.blamenet.parse;

import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 采集结果（Map）的类型安全访问工具。 */
public final class MapAccess {

    private MapAccess() {
    }

    public static String text(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 宽松布尔解析：支持 true/True/是/1。 */
    public static boolean bool(Map<String, Object> map, String key) {
        return boolValue(map == null ? null : map.get(key));
    }

    public static boolean boolValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "是".equals(text) || "1".equals(text);
    }

    public static long number(Map<String, Object> map, String key, long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        return TextUtil.parseLong(String.valueOf(value), defaultValue);
    }

    /** 逗号分隔值 → 列表。 */
    public static List<String> csv(Map<String, Object> map, String key) {
        List<String> values = new ArrayList<>();
        String text = text(map, key);
        if (text.isEmpty()) {
            return values;
        }
        for (String part : text.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /** 嵌套数组 → 字符串列表。 */
    @SuppressWarnings("unchecked")
    public static List<String> stringList(Map<String, Object> map, String key) {
        List<String> values = new ArrayList<>();
        if (map == null) {
            return values;
        }
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    values.add(String.valueOf(element).trim());
                }
            }
        } else if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    /** 嵌套对象数组 → Map 列表。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objectList(Map<String, Object> map, String key) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (map == null) {
            return values;
        }
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> nested) {
                    values.add((Map<String, Object>) nested);
                }
            }
        } else if (value instanceof Map<?, ?> nested) {
            values.add((Map<String, Object>) nested);
        }
        return values;
    }

    /** 从 ipconfig 结果中取指定接口的某字段。 */
    public static String findByInterface(List<Map<String, Object>> rows, String interfaceAlias, String key) {
        for (Map<String, Object> row : rows) {
            if (TextUtil.nvl(interfaceAlias).equalsIgnoreCase(text(row, "InterfaceAlias"))) {
                return text(row, key);
            }
        }
        return "";
    }
}
