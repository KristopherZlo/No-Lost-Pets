package com.creas.petrecall.command;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class PetRecallCommand {
    private PetRecallCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("petrecall")
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .executes(PetRecallCommand::executeSelf)
                .then(CommandManager.literal("rescan")
                        .executes(PetRecallCommand::executeRescan)));
    }

    private static int executeSelf(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        if (!player.isOnGround()) {
            context.getSource().sendError(Text.literal("Stand on the ground before using Pet Recall."));
            return 0;
        }

        if (getService().isRecallActive(player.getUuid())) {
            context.getSource().sendError(Text.literal("Pet Recall is already running for you."));
            return 0;
        }

        boolean started = getService().recallAllForPlayerAsync(player, summary -> {
            if (player.isRemoved()) {
                return;
            }

            player.sendMessage(Text.literal(
                    "Pet Recall: " + summary.recalled + " recalled, " + summary.skipped + " skipped, " + summary.failed + " failed, " + summary.totalKnown + " indexed."
            ), false);

            if (!summary.messages.isEmpty()) {
                int max = Math.min(summary.messages.size(), 5);
                for (int i = 0; i < max; i++) {
                    player.sendMessage(Text.literal(" - " + summary.messages.get(i)), false);
                }
                if (summary.messages.size() > max) {
                    player.sendMessage(Text.literal(" - ... and " + (summary.messages.size() - max) + " more"), false);
                }
            }
        });

        if (!started) {
            context.getSource().sendError(Text.literal("Failed to start Pet Recall."));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("Pet Recall started..."), false);
        return 1;
    }

    private static int executeRescan(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        int found = getService().rescanLoadedForPlayer(player);
        context.getSource().sendFeedback(
                () -> Text.literal("Pet Recall: re-indexed " + found + " loaded pets for " + player.getName().getString() + "."),
                false
        );
        return found;
    }

    private static PetRecallService getService() {
        return PetRecallMod.getRecallService();
    }
}
