package com.itemgraph.query;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.itemgraph.util.CanonicalJson;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** A validated exact item-metadata predicate accepted by the audit lookup grammar. */
public record ItemMetadataPredicate(Kind kind, String key, String value) {

    public enum Kind {
        ITEM_ID,
        FINGERPRINT,
        CUSTOM_NAME,
        DAMAGE,
        TRIM,
        ENCHANTMENT,
        LORE,
        COMPONENT
    }

    public ItemMetadataPredicate {
        if (kind == null || key == null || value == null) {
            throw new IllegalArgumentException("item metadata predicate fields must be non-null");
        }
    }

    static ItemMetadataPredicate parse(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " filter cannot be empty");
        }
        return switch (name) {
            case "item" -> new ItemMetadataPredicate(Kind.ITEM_ID, "item_id", resourceId(value, "item"));
            case "fingerprint" -> {
                if (!value.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("fingerprint must be lowercase SHA-256 hex");
                }
                yield new ItemMetadataPredicate(Kind.FINGERPRINT, "fingerprint_hash", value);
            }
            case "name" -> new ItemMetadataPredicate(Kind.CUSTOM_NAME,
                    "minecraft:custom_name#plain_text", value);
            case "damage" -> {
                if (!value.matches("0|[1-9][0-9]{0,9}")) {
                    throw new IllegalArgumentException("damage must be a nonnegative integer");
                }
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("damage exceeds the supported integer range", invalid);
                }
                yield new ItemMetadataPredicate(Kind.DAMAGE, "minecraft:damage", value);
            }
            case "trim" -> {
                String[] parts = value.split("/", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("trim must use material/pattern registry IDs");
                }
                String material = resourceId(parts[0], "trim material");
                String pattern = resourceId(parts[1], "trim pattern");
                yield new ItemMetadataPredicate(Kind.TRIM, "minecraft:trim#material_pattern",
                        material + "/" + pattern);
            }
            case "enchantment" -> parseEnchantment(value);
            case "lore" -> new ItemMetadataPredicate(Kind.LORE, "minecraft:lore#plain_text", value);
            case "component" -> parseComponent(value);
            default -> throw new IllegalArgumentException("unknown item metadata filter '" + name + "'");
        };
    }

    String uniquenessKey() {
        return kind + ":" + key;
    }

    String indexedValue() {
        return switch (kind) {
            case CUSTOM_NAME, TRIM, LORE -> new com.google.gson.JsonPrimitive(value).toString();
            case DAMAGE, ENCHANTMENT -> value.isEmpty() ? null : value;
            case COMPONENT -> value;
            case ITEM_ID, FINGERPRINT -> null;
        };
    }

    public String normalizedToken() {
        return switch (kind) {
            case ITEM_ID -> "item." + value;
            case FINGERPRINT -> "fingerprint." + value;
            case CUSTOM_NAME -> "name.\"" + escape(value) + "\"";
            case DAMAGE -> "damage." + value;
            case TRIM -> "trim." + value;
            case ENCHANTMENT -> {
                String prefix = "minecraft:enchantments/";
                if (!key.startsWith(prefix) || key.length() == prefix.length()
                        || !value.isEmpty() && !value.matches("[1-9][0-9]{0,2}")) {
                    throw new IllegalArgumentException("enchantment predicate is not canonical");
                }
                yield "enchantment." + key.substring(prefix.length())
                        + (value.isEmpty() ? "" : ":" + value);
            }
            case LORE -> "lore.\"" + escape(value) + "\"";
            case COMPONENT -> "component." + key + "=" + value;
        };
    }

    private static ItemMetadataPredicate parseEnchantment(String value) {
        int lastColon = value.lastIndexOf(':');
        String id = value;
        String level = "";
        if (lastColon > value.indexOf(':') && lastColon < value.length() - 1) {
            String possibleLevel = value.substring(lastColon + 1);
            if (possibleLevel.matches("[1-9][0-9]{0,2}")) {
                id = value.substring(0, lastColon);
                level = possibleLevel;
            }
        }
        String resourceId = resourceId(id, "enchantment");
        return new ItemMetadataPredicate(Kind.ENCHANTMENT,
                "minecraft:enchantments/" + resourceId, level);
    }

    private static ItemMetadataPredicate parseComponent(String value) {
        int equals = value.indexOf('=');
        if (equals <= 0 || equals == value.length() - 1) {
            throw new IllegalArgumentException("component must use registry_id=canonical_json_value");
        }
        String id = resourceId(value.substring(0, equals), "component");
        String json = value.substring(equals + 1);
        validateJsonComplexity(json);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("component value must be valid JSON", invalid);
        }
        String canonical;
        try {
            canonical = CanonicalJson.encode(parsed);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("component JSON value exceeds canonical bounds", invalid);
        }
        if (canonical.length() > 4_096) {
            throw new IllegalArgumentException("canonical component JSON must be at most 4096 characters");
        }
        return new ItemMetadataPredicate(Kind.COMPONENT, id, canonical);
    }

    private static String resourceId(String value, String kind) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException(kind + " must be a valid registry ID");
        }
        return id.toString();
    }

    private static void validateJsonComplexity(String json) {
        int depth = 0;
        int nodes = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                if (++depth > 32) {
                    throw new IllegalArgumentException("component JSON nesting exceeds 32 levels");
                }
                if (++nodes > 256) {
                    throw new IllegalArgumentException("component JSON supports at most 256 values");
                }
            } else if (ch == '}' || ch == ']') {
                depth--;
            } else if (ch == ',' || ch == ':') {
                continue;
            } else if (!Character.isWhitespace(ch)) {
                if (++nodes > 256) {
                    throw new IllegalArgumentException("component JSON supports at most 256 values");
                }
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
