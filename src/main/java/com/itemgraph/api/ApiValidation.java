package com.itemgraph.api;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

final class ApiValidation {
    static final int MAX_ATTRIBUTE_BYTES = 16 * 1024;
    static final int MAX_COMPONENT_BYTES = 32 * 1024;
    static final int MAX_RAW_OBSERVATION_BYTES = 64 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Set<EndpointKind> WORLD_KINDS = Set.of(
            EndpointKind.CONTAINER, EndpointKind.GROUND, EndpointKind.ARMOR_STAND);

    private ApiValidation() {}

    static boolean validModId(String modId) {
        return modId != null && MOD_ID.matcher(modId).matches();
    }

    static boolean modLoaded(String modId) {
        try {
            ModList list = ModList.get();
            return list == null || list.isLoaded(modId);
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean validDisplayName(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    static boolean validInventoryId(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.trim().equals(value) && value.codePoints().noneMatch(Character::isISOControl);
    }

    record ValidationFailure(String code, String message) {}

    static ValidationFailure validateRegistration(SourceRegistration registration) {
        if (registration == null) {
            return new ValidationFailure("INVALID_INPUT", "registration must not be null");
        }
        if (!validModId(registration.modId())
                || Set.of("itemgraph", "minecraft", "neoforge").contains(registration.modId())) {
            return new ValidationFailure("INVALID_MOD_ID",
                    "modId must be a valid consumer NeoForge mod id");
        }
        if (!validDisplayName(registration.displayName())) {
            return new ValidationFailure("INVALID_DISPLAY_NAME",
                    "displayName must be non-blank and at most 128 characters");
        }
        if (!modLoaded(registration.modId())) {
            return new ValidationFailure("INVALID_MOD_ID", "modId does not name a loaded mod");
        }
        return null;
    }

    static ValidationFailure validateObservation(DirectObservation observation) {
        if (observation == null) {
            return new ValidationFailure("INVALID_INPUT", "observation must not be null");
        }
        if (observation.sourceEventId() <= 0) {
            return new ValidationFailure("INVALID_EVENT_ID", "sourceEventId must be positive");
        }
        if (observation.timestampMs() <= 0) {
            return new ValidationFailure("INVALID_TIME_RANGE", "timestampMs must be positive");
        }
        if (observation.action() == null) {
            return new ValidationFailure("INVALID_ACTION", "action must not be null");
        }
        if (observation.origin() == null || observation.destination() == null) {
            return new ValidationFailure("INVALID_ENDPOINT",
                    "origin and destination endpoints are required");
        }
        String endpointError = validateEndpoint(observation.origin());
        if (endpointError != null) {
            return new ValidationFailure("INVALID_ENDPOINT", "invalid origin: " + endpointError);
        }
        endpointError = validateEndpoint(observation.destination());
        if (endpointError != null) {
            return new ValidationFailure("INVALID_ENDPOINT", "invalid destination: " + endpointError);
        }
        if (observation.timestampEndMs() != null
                && observation.timestampEndMs() < observation.timestampMs()) {
            return new ValidationFailure("INVALID_TIME_RANGE",
                    "timestampEndMs must be null or at least timestampMs");
        }
        if (observation.attributes() == null) {
            return new ValidationFailure("INVALID_ATTRIBUTES",
                    "attributes must be a non-null map (empty is allowed)");
        }
        int attributeBytes = 0;
        for (Map.Entry<String, String> entry : observation.attributes().entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > 128 || entry.getValue() == null) {
                return new ValidationFailure("INVALID_ATTRIBUTES",
                        "attribute keys must be non-blank strings of at most 128 characters and values must not be null");
            }
            if (key.matches("(?i).*(secret|token|password|credential|api[-_]?key|private[-_]?key).*")) {
                return new ValidationFailure("INVALID_ATTRIBUTES",
                        "attributes must not contain credentials or secret-looking keys");
            }
            attributeBytes += utf8(key) + utf8(entry.getValue());
            if (attributeBytes > MAX_ATTRIBUTE_BYTES) {
                return new ValidationFailure("INVALID_ATTRIBUTES",
                        "attributes exceed the 16 KiB observation metadata bound");
            }
        }
        String itemError = validateItem(observation.item());
        return itemError == null ? null : new ValidationFailure("INVALID_ITEM", itemError);
    }

    private static String validateEndpoint(EndpointRef endpoint) {
        if (endpoint instanceof PlayerEndpoint player) {
            if (player.playerUuid() == null) {
                return "playerUuid must not be null";
            }
            return player.displayName() == null || player.displayName().length() <= 128
                    ? null : "displayName must be at most 128 characters";
        }
        if (endpoint instanceof WorldEndpoint world) {
            if (!WORLD_KINDS.contains(world.kind())) {
                return "world endpoints must be CONTAINER, GROUND, or ARMOR_STAND";
            }
            if (world.level() == null || world.position() == null) {
                return "level and position are required";
            }
            return world.displayName() == null || world.displayName().length() <= 128
                    ? null : "displayName must be at most 128 characters";
        }
        if (endpoint instanceof ExternalInventoryEndpoint external) {
            if (!validModId(external.ownerModId())) {
                return "ownerModId must be a valid mod id";
            }
            if (!validInventoryId(external.inventoryId())) {
                return "inventoryId must be non-blank, 1-128 chars, and stable";
            }
            if (external.displayName() != null && !validDisplayName(external.displayName())) {
                return "displayName must be at most 128 characters";
            }
            WorldLocation location = external.lastKnownLocation();
            if (location != null && (location.level() == null || location.position() == null)) {
                return "lastKnownLocation requires level and position";
            }
            return null;
        }
        if (endpoint instanceof UnknownEndpoint unknown) {
            if (unknown.level() == null) {
                return "UNKNOWN requires a level context";
            }
            return unknown.reason() == null || unknown.reason().length() <= 256
                    ? null : "reason must be at most 256 characters";
        }
        return "unsupported endpoint";
    }

    static String validateItem(ItemSnapshot item) {
        if (item == null) {
            return "item must not be null";
        }
        if (item.amount() <= 0) {
            return "item amount must be positive";
        }
        if (item.itemId() == null || ResourceLocation.tryParse(item.itemId()) == null) {
            return "itemId must be a valid resource location";
        }
        if (item.customName() != null && item.customName().length() > 256) {
            return "customName must be at most 256 characters";
        }
        Map<String, String> components = item.components();
        if (components == null) {
            return "components must be a non-null map (empty is allowed)";
        }
        int bytes = 0;
        for (Map.Entry<String, String> entry : components.entrySet()) {
            if (entry.getKey() == null || ResourceLocation.tryParse(entry.getKey()) == null
                    || entry.getValue() == null) {
                return "component keys must be resource locations and values must not be null";
            }
            bytes += utf8(entry.getKey()) + utf8(entry.getValue());
            if (bytes > MAX_COMPONENT_BYTES) {
                return "components exceed the 32 KiB item metadata bound";
            }
        }
        return null;
    }

    static String validateOptions(QueryOptions options) {
        if (options == null) {
            return null;
        }
        if (options.limit() < 1) {
            return "limit must be at least 1";
        }
        if (options.sinceMinutes() != null && options.sinceMinutes() < 1) {
            return "sinceMinutes must be null or at least 1";
        }
        return null;
    }

    static int selectorCount(ItemQuery query) {
        int count = 0;
        if (query.itemId() != null) {
            count++;
        }
        if (query.customName() != null) {
            count++;
        }
        if (query.fingerprintHash() != null) {
            count++;
        }
        return count;
    }

    static int utf8(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
