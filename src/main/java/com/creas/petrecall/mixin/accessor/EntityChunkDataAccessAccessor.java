package com.creas.petrecall.mixin.accessor;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityChunkDataAccess.class)
public interface EntityChunkDataAccessAccessor {
    @Accessor("storage")
    VersionedChunkStorage pet_recall$getStorage();

    @Accessor("emptyChunks")
    LongSet pet_recall$getEmptyChunks();
}
