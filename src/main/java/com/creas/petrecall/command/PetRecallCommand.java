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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class PetRecallCommand {
    private static final SimpleCommandExceptionType PLAYER_ONLY = new SimpleCommandExceptionType(Component.literal("This NoLostPets command must be run by a player."));

    private PetRecallCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        dispatcher.register(
                Commands.literal("petrecall")
                        .requires(VersionCompat::hasAdminPermission)
                        .then(Commands.literal("force")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(PetRecallCommand::executeForce)))
                        .then(Commands.literal("rescan")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(PetRecallCommand::executeRescan)))
                        .then(Commands.literal("stats")
                                .executes(PetRecallCommand::executeStatsSelfOrGlobal)
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(PetRecallCommand::executeStatsForPlayer)))
                        .then(Commands.literal("verify")
                                .then(Commands.literal("singleplayer")
                                        .executes(PetRecallCommand::executeVerifySingleplayer))
                                .then(Commands.literal("multiplayer")
                                        .then(Commands.argument("otherPlayer", EntityArgument.player())
                                                .executes(PetRecallCommand::executeVerifyMultiplayer)))
                                .then(Commands.literal("status")
                                        .executes(PetRecallCommand::executeVerifyStatus))
                                .then(Commands.literal("cancel")
                                        .executes(PetRecallCommand::executeVerifyCancel)))
        );
    }

    private static int executeForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        CommandSourceStack source = context.getSource();
        DebugTrace.log("command", "force %s source=%s", DebugTrace.describePlayer(player), source.getTextName());

        if (getService().isRecallActive(player.getUUID())) {
            source.sendFailure(Component.literal("NoLostPets is already running for " + player.getName().getString() + "."));
            return 0;
        }

        boolean started = getService().recallAllForPlayerAsync(player, summary -> {
            source.sendSuccess(() -> Component.literal(
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
                    source.sendSuccess(() -> Component.literal(" - " + summary.messages.get(messageIndex)), false);
                }
                if (summary.messages.size() > max) {
                    source.sendSuccess(() -> Component.literal(" - ... and " + (summary.messages.size() - max) + " more"), false);
                }
            }
        });

        if (!started) {
            source.sendFailure(Component.literal("Failed to start NoLostPets debug run."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("NoLostPets debug force started for " + player.getName().getString() + "."), false);
        return 1;
    }

    private static int executeRescan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        DebugTrace.log("command", "rescan %s source=%s", DebugTrace.describePlayer(player), context.getSource().getTextName());
        int found = getService().rescanLoadedForPlayer(player);
        context.getSource().sendSuccess(
                () -> Component.literal("NoLostPets: re-indexed " + found + " loaded pets for " + player.getName().getString() + "."),
                false
        );
        return found;
    }

    private static int executeStatsSelfOrGlobal(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        DebugTrace.log("command", "stats source=%s hasEntity=%s", source.getTextName(), source.getEntity() != null);
        if (source.getEntity() instanceof ServerPlayer player) {
            return sendStats(source, player);
        }

        return sendGlobalStats(source);
    }

    private static int executeStatsForPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        DebugTrace.log("command", "stats-for-player %s source=%s", DebugTrace.describePlayer(player), context.getSource().getTextName());
        return sendStats(context.getSource(), player);
    }

    private static int executeVerifySingleplayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = getSourcePlayer(context.getSource());
        boolean started = PetRecallMod.getSelfTestService().startSingleplayer(player, text -> context.getSource().sendSuccess(() -> text, false));
        if (!started) {
            context.getSource().sendFailure(Component.literal("NoLostPets self-test is already running."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("NoLostPets singleplayer self-test started."), false);
        return 1;
    }

    private static int executeVerifyMultiplayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer owner = getSourcePlayer(source);
        ServerPlayer otherPlayer = EntityArgument.getPlayer(context, "otherPlayer");
        if (owner.getUUID().equals(otherPlayer.getUUID())) {
            source.sendFailure(Component.literal("Choose another online player for the multiplayer self-test."));
            return 0;
        }

        boolean started = PetRecallMod.getSelfTestService().startMultiplayer(owner, otherPlayer, text -> source.sendSuccess(() -> text, false));
        if (!started) {
            source.sendFailure(Component.literal("NoLostPets self-test is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("NoLostPets multiplayer self-test started."), false);
        return 1;
    }

    private static int executeVerifyStatus(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(PetRecallMod.getSelfTestService()::getStatusText, false);
        return 1;
    }

    private static int executeVerifyCancel(CommandContext<CommandSourceStack> context) {
        boolean cancelled = PetRecallMod.getSelfTestService().cancel("Cancelled by command source " + context.getSource().getTextName() + ".");
        if (!cancelled) {
            context.getSource().sendFailure(Component.literal("NoLostPets self-test is not running."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("NoLostPets self-test cancelled."), false);
        return 1;
    }

    private static int sendStats(CommandSourceStack source, ServerPlayer player) {
        long now = source.getServer().overworld() == null ? 0L : source.getServer().overworld().getGameTime();
        DebugStats stats = getService().getDebugStats(now);
        int ownerRecords = PetRecallMod.getTracker().getOwnerRecords(source.getServer(), player.getUUID()).size();

        source.sendSuccess(() -> Component.literal(
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

    private static int sendGlobalStats(CommandSourceStack source) {
        long now = source.getServer().overworld() == null ? 0L : source.getServer().overworld().getGameTime();
        DebugStats stats = getService().getDebugStats(now);
        int indexed = PetIndexState.get(source.getServer()).size();

        source.sendSuccess(() -> Component.literal(
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

    private static ServerPlayer getSourcePlayer(CommandSourceStack source) throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        throw PLAYER_ONLY.create();
    }
}
