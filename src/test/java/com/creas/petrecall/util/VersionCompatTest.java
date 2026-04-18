package com.creas.petrecall.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class VersionCompatTest {
    @Test
    void matchesGameProfileByUuid() {
        UUID uuid = UUID.randomUUID();
        GameProfile player = new GameProfile(uuid, "PlayerOne");
        GameProfile operator = new GameProfile(uuid, "RenamedPlayer");

        assertTrue(VersionCompat.matchesProfileKey(player, operator));
    }

    @Test
    void matchesRecordLikeProfileByUuidOrName() {
        UUID uuid = UUID.randomUUID();
        GameProfile player = new GameProfile(uuid, "PlayerOne");

        assertTrue(VersionCompat.matchesProfileKey(player, new ProfileKey(uuid, "SomeoneElse")));
        assertTrue(VersionCompat.matchesProfileKey(player, new ProfileKey(UUID.randomUUID(), "playerone")));
        assertFalse(VersionCompat.matchesProfileKey(player, new ProfileKey(UUID.randomUUID(), "DifferentName")));
    }

    private record ProfileKey(UUID id, String name) {
    }
}
