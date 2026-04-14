package com.creas.petrecall.mixin.accessor;

import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.world.entity.EntityLike;
import net.minecraft.world.storage.ChunkDataAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerAccessor<T extends EntityLike> {
    @Accessor("dataAccess")
    ChunkDataAccess<T> pet_recall$getDataAccess();
}
