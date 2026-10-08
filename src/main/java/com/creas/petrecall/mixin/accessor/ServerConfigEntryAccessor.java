package com.creas.petrecall.mixin.accessor;

import net.minecraft.server.ServerConfigEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerConfigEntry.class)
public interface ServerConfigEntryAccessor {
    @Invoker("getKey")
    Object pet_recall$getKey();
}
