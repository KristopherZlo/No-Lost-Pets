package com.creas.petrecall.gametest.mixin;

import com.creas.petrecall.gametest.RecallBatchProbe;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PetRecallService.class, remap = false)
public abstract class RecallBatchMixin {
    @Inject(method = "recallSelectedPetsForPlayerAsyncSilent", at = @At("HEAD"))
    private void observeBatch(ServerPlayerEntity player, List<PetRecord> records,
            Consumer<RecallSummary> onComplete, CallbackInfoReturnable<Boolean> result) {
        RecallBatchProbe.record(player.getUuid(), records.size());
    }
}
