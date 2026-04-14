package com.creas.petrecall.command;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.DebugStats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class PetRecallCommand {
    private PetRecallCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
                CommandManager.literal("petrecall")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                        .then(CommandManager.literal("force")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(PetRecallCommand::executeForce)))
                        .then(CommandManager.literal("rescan")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(PetRecallCommand::executeRescan)))
                        .then(CommandManager.literal("stats")
                                .executes(PetRecallCommand::executeStatsSelfOrGlobal)
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(PetRecallCommand::executeStatsForPlayer)))
        );
    }

    private static int executeForce(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        ServerCommandSource source = context.getSource();

        if (getService().isRecallActive(player.getUuid())) {
            source.sendError(Text.literal("Pet Recall is already running for " + player.getName().getString() + "."));
            return 0;
        }

        boolean started = getService().recallAllForPlayerAsync(player, summary -> {
            source.sendFeedback(() -> Text.literal(
                    "Pet Recall debug for " + player.getName().getString() + ": " +
                            summary.recalled + " recalled, " +
                            summary.skipped + " skipped, " +
                            summary.failed + " failed, " +
                            summary.totalKnown + " indexed."
            ), false);

            if (!summary.messages.isEmpty()) {
                int max = Math.min(summary.messages.size(), 8);
                for (int i = 0; i < max; i++) {
                    int messageIndex = i;
                    source.sendFeedback(() -> Text.literal(" - " + summary.messages.get(messageIndex)), false);
                }
                if (summary.messages.size() > max) {
                    source.sendFeedback(() -> Text.literal(" - ... and " + (summary.messages.size() - max) + " more"), false);
                }
            }
        });

        if (!started) {
            source.sendError(Text.literal("Failed to start Pet Recall debug run."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Pet Recall debug force started for " + player.getName().getString() + "."), false);
        return 1;
    }

    private static int executeRescan(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        int found = getService().rescanLoadedForPlayer(player);
        context.getSource().sendFeedback(
                () -> Text.literal("Pet Recall: re-indexed " + found + " loaded pets for " + player.getName().getString() + "."),
                false
        );
        return found;
    }

    private static int executeStatsSelfOrGlobal(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return sendStats(source, player);
        }

        return sendGlobalStats(source);
    }

    private static int executeStatsForPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        return sendStats(context.getSource(), player);
    }

    private static int sendStats(ServerCommandSource source, ServerPlayerEntity player) {
        long now = source.getServer().getOverworld() == null ? 0L : source.getServer().getOverworld().getTime();
        DebugStats stats = getService().getDebugStats(now);
        int ownerRecords = PetRecallMod.getTracker().getOwnerRecords(source.getServer(), player.getUuid()).size();

        source.sendFeedback(() -> Text.literal(
                "Pet Recall stats for " + player.getName().getString() + ": indexed=" + ownerRecords +
                        ", trackedLoaded=" + PetRecallMod.getTracker().getLoadedPetCount() +
                        ", activePlayers=" + stats.activePlayerRecalls() +
                        ", activePets=" + stats.activePetRecalls() +
                        ", activeChunks=" + stats.activeChunkOperations() +
                        ", queuedChunks=" + stats.queuedChunkOperations() +
                        ", runtimeStates=" + stats.trackedRuntimeStates() +
                        ", quarantined=" + stats.quarantinedPets()
        ), false);
        return ownerRecords;
    }

    private static int sendGlobalStats(ServerCommandSource source) {
        long now = source.getServer().getOverworld() == null ? 0L : source.getServer().getOverworld().getTime();
        DebugStats stats = getService().getDebugStats(now);
        int indexed = PetIndexState.get(source.getServer()).size();

        source.sendFeedback(() -> Text.literal(
                "Pet Recall global stats: indexed=" + indexed +
                        ", trackedLoaded=" + PetRecallMod.getTracker().getLoadedPetCount() +
                        ", activePlayers=" + stats.activePlayerRecalls() +
                        ", activePets=" + stats.activePetRecalls() +
                        ", activeChunks=" + stats.activeChunkOperations() +
                        ", queuedChunks=" + stats.queuedChunkOperations() +
                        ", runtimeStates=" + stats.trackedRuntimeStates() +
                        ", quarantined=" + stats.quarantinedPets()
        ), false);
        return indexed;
    }

    private static PetRecallService getService() {
        return PetRecallMod.getRecallService();
    }
}
