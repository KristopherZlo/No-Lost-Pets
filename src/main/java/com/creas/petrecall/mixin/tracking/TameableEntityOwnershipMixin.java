package com.creas.petrecall.mixin.tracking;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.util.VersionCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TamableAnimal.class)
public abstract class TameableEntityOwnershipMixin {
    @Inject(method = "setOwner(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("TAIL"))
    private void pet_recall$afterSetOwnerEntity(LivingEntity owner, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    @Inject(method = "setOwnerReference(Lnet/minecraft/world/entity/EntityReference;)V", at = @At("TAIL"))
    private void pet_recall$afterSetOwnerReference(EntityReference<LivingEntity> ownerReference, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    @Inject(method = "tame(Lnet/minecraft/world/entity/player/Player;)V", at = @At("TAIL"))
    private void pet_recall$afterSetTamedBy(Player player, CallbackInfo ci) {
        this.pet_recall$refreshTracker();
    }

    private void pet_recall$refreshTracker() {
        Entity self = (Entity) (Object) this;
        ServerLevel serverWorld = VersionCompat.getServerWorld(self);
        if (serverWorld != null) {
            PetRecallMod.getTracker().observe(self, serverWorld);
        }
    }
}
