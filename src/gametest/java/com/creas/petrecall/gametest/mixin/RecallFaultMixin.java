package com.creas.petrecall.gametest.mixin;

import com.creas.petrecall.gametest.RecallFaults;
import com.creas.petrecall.util.VersionCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VersionCompat.class, remap = false)
public abstract class RecallFaultMixin {
    @Inject(method = "areChunkEntitiesLoaded", at = @At("HEAD"), cancellable = true)
    private static void delayReadiness(ServerLevel world, ChunkPos chunk, CallbackInfoReturnable<Boolean> result) {
        if (RecallFaults.isBlocked(world, chunk)) result.setReturnValue(false);
    }

    @Inject(method = "holdRecallChunk", at = @At("HEAD"))
    private static void failAcquisition(ServerLevel world, ChunkPos chunk, CallbackInfo callback) {
        if (RecallFaults.consumeAcquisitionFailure(world, chunk)) {
            throw new IllegalStateException("Injected transient recall chunk acquisition failure");
        }
    }
}
