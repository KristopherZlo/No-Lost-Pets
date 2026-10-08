package com.creas.petrecall.mixin.accessor;

import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StoredUserEntry.class)
public interface ServerConfigEntryAccessor {
    @Invoker("getUser")
    Object pet_recall$getKey();
}
