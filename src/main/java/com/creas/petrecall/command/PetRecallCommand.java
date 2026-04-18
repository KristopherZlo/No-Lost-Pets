package com.creas.petrecall.command;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.DebugStats;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.VersionCompat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class PetRecallCommand {
    private static final SimpleCommandExceptionType PLAYER_ONLY = new SimpleCommandExceptionType(Text.literal("This NoLostPets command must be run by a player."));

    private PetRecallCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
                CommandManager.literal("petrecall")
                        .requires(VersionCompat::hasAdminPermission)
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
                        .then(CommandManager.literal("verify")
                                .then(CommandManager.literal("singleplayer")
                                        .executes(PetRecallCommand::executeVerifySingleplayer))
                                .then(CommandManager.literal("multiplayer")
                                        .then(CommandManager.argument("otherPlayer", EntityArgumentType.player())
                                                .executes(PetRecallCommand::executeVerifyMultiplayer)))
                                .then(CommandManager.literal("status")
                                        .executes(PetRecallCommand::executeVerifyStatus))
                                .then(CommandManager.literal("cancel")
                                        .executes(PetRecallCommand::executeVerifyCancel)))
        );
    }

    private static int executeForce(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        ServerCommandSource source = context.getSource();
        DebugTrace.log("command", "force %s source=%s", DebugTrace.describePlayer(player), source.getName());

        if (getService().isRecallActive(player.getUuid())) {
            source.sendError(Text.literal("NoLostPets is already running for " + player.getName().getString() + "."));
            return 0;
        }

        boolean started = getService().recallAllForPlayerAsync(player, summary -> {
            source.sendFeedback(() -> Text.literal(
                    "NoLostPets debug for " + player.getName().getString() + ": " +
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
            source.sendError(Text.literal("Failed to start NoLostPets debug run."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("NoLostPets debug force started for " + player.getName().getString() + "."), false);
        return 1;
    }

    private static int executeRescan(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        DebugTrace.log("command", "rescan %s source=%s", DebugTrace.describePlayer(player), context.getSource().getName());
        int found = getService().rescanLoadedForPlayer(player);
        context.getSource().sendFeedback(
                () -> Text.literal("NoLostPets: re-indexed " + found + " loaded pets for " + player.getName().getString() + "."),
                false
        );
        return found;
    }

    private static int executeStatsSelfOrGlobal(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        DebugTrace.log("command", "stats source=%s hasEntity=%s", source.getName(), source.getEntity() != null);
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return sendStats(source, player);
        }

        return sendGlobalStats(source);
    }

    private static int executeStatsForPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        DebugTrace.log("command", "stats-for-player %s source=%s", DebugTrace.describePlayer(player), context.getSource().getName());
        return sendStats(context.getSource(), player);
    }

    private static int executeVerifySingleplayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = getSourcePlayer(context.getSource());
        boolean started = PetRecallMod.getSelfTestService().startSingleplayer(player, text -> context.getSource().sendFeedback(() -> text, false));
        if (!started) {
            context.getSource().sendError(Text.literal("NoLostPets self-test is already running."));
            return 0;
        }
        context.getSource().sendFeedback(() -> Text.literal("NoLostPets singleplayer self-test started."), false);
        return 1;
    }

    private static int executeVerifyMultiplayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity owner = getSourcePlayer(source);
        ServerPlayerEntity otherPlayer = EntityArgumentType.getPlayer(context, "otherPlayer");
        if (owner.getUuid().equals(otherPlayer.getUuid())) {
            source.sendError(Text.literal("Choose another online player for the multiplayer self-test."));
            return 0;
        }

        boolean started = PetRecallMod.getSelfTestService().startMultiplayer(owner, otherPlayer, text -> source.sendFeedback(() -> text, false));
        if (!started) {
            source.sendError(Text.literal("NoLostPets self-test is already running."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("NoLostPets multiplayer self-test started."), false);
        return 1;
    }

    private static int executeVerifyStatus(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(PetRecallMod.getSelfTestService()::getStatusText, false);
        return 1;
    }

    private static int executeVerifyCancel(CommandContext<ServerCommandSource> context) {
        boolean cancelled = PetRecallMod.getSelfTestService().cancel("Cancelled by command source " + context.getSource().getName() + ".");
        if (!cancelled) {
            context.getSource().sendError(Text.literal("NoLostPets self-test is not running."));
            return 0;
        }
        context.getSource().sendFeedback(() -> Text.literal("NoLostPets self-test cancelled."), false);
        return 1;
    }

    private static int sendStats(ServerCommandSource source, ServerPlayerEntity player) {
        long now = source.getServer().getOverworld() == null ? 0L : source.getServer().getOverworld().getTime();
        DebugStats stats = getService().getDebugStats(now);
        int ownerRecords = PetRecallMod.getTracker().getOwnerRecords(source.getServer(), player.getUuid()).size();

        source.sendFeedback(() -> Text.literal(
                "NoLostPets stats for " + player.getName().getString() + ": indexed=" + ownerRecords +
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
                "NoLostPets global stats: indexed=" + indexed +
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

    private static ServerPlayerEntity getSourcePlayer(ServerCommandSource source) throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player;
        }
        throw PLAYER_ONLY.create();
    }
}
