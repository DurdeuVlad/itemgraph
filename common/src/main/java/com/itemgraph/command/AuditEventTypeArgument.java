package com.itemgraph.command;

import com.itemgraph.query.UnifiedEvidenceQueryService;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Parses the finite event-type vocabulary used by the native lookup branches.
 *
 * <p>Rejecting dotted values here is intentional: a token such as
 * {@code radius.10} belongs to the direct GriefLogger filter branch, not to a
 * native event-type lookup. A plain word argument would make those two command
 * forms ambiguous for one-token filter expressions.</p>
 */
final class AuditEventTypeArgument implements ArgumentType<String> {
    private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(
            Component.literal("unknown ItemGraph audit event type"));

    private AuditEventTypeArgument() {}

    static AuditEventTypeArgument type() {
        return new AuditEventTypeArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int cursor = reader.getCursor();
        String value = reader.readUnquotedString();
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!UnifiedEvidenceQueryService.ACTION_TYPES.contains(normalized)) {
            reader.setCursor(cursor);
            throw INVALID.createWithContext(reader);
        }
        return normalized;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                               SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(UnifiedEvidenceQueryService.ACTION_TYPES, builder);
    }

    @Override
    public java.util.Collection<String> getExamples() {
        return UnifiedEvidenceQueryService.ACTION_TYPES;
    }
}
