package com.creas.petrecall.mixin.tracking;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.util.VersionCompat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TameableEntity.class)
public abstract class TameableEntityOwnershipMixin {
    @Inject(method = "setOwner(Lnet/minecraft/entity/LivingEntity;)V", at = @At("TAIL"))
    private void pet_recall$afterSetOwnerEntity(LivingEntity owner, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    @Inject(method = "setOwner(Lnet/minecraft/entity/LazyEntityReference;)V", at = @At("TAIL"))
    private void pet_recall$afterSetOwnerReference(LazyEntityReference<LivingEntity> ownerReference, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    @Inject(method = "setTamedBy(Lnet/minecraft/entity/player/PlayerEntity;)V", at = @At("TAIL"))
    private void pet_recall$afterSetTamedBy(PlayerEntity player, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    private void pet_recall$refreshTracker() {
        Entity self = (Entity) (Object) this;
        ServerWorld serverWorld = VersionCompat.getServerWorld(self);
        if (serverWorld != null) {
            PetRecallMod.getTracker().observe(self, serverWorld);
        }
    }
}
