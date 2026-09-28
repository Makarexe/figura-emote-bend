package com.figurabend;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Аддон целиком состоит из клиентского миксина в рендер Figura (см. figurabend.mixins.json),
 * класс нужен только чтобы NeoForge увидел мод.
 */
@Mod(value = FiguraBend.MOD_ID, dist = Dist.CLIENT)
public final class FiguraBend {
    public static final String MOD_ID = "figurabend";
}
