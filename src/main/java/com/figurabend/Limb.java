package com.figurabend;

import org.figuramc.figura.model.ParentType;
import org.jetbrains.annotations.Nullable;

/**
 * Части тела игрока, которые Emotecraft (playerAnimator + bendy-lib) умеет сгибать,
 * с размерами ванильного куба этой части.
 * <p>
 * Координаты — локальные координаты ванильной {@code ModelPart} в пикселях
 * (ось Y смотрит вниз, начало — в точке поворота части), те же, в которых bendy-lib
 * строит свой {@code BendableCuboid}. Для изгиба важны только высота куба и центр изгиба:
 * <ul>
 *   <li>руки и ноги гнутся с {@code Direction.UP} — поворачивается нижняя половина (кисть, стопа);</li>
 *   <li>торс гнётся с {@code Direction.DOWN} — поворачивается верхняя половина (грудь).</li>
 * </ul>
 */
public enum Limb {
    HEAD(ParentType.Head, null, true, false, 0, 0, 0),
    BODY(ParentType.Body, "torso", false, false, 0, 0, 12),
    RIGHT_ARM(ParentType.RightArm, "rightArm", true, true, -1, -2, 10),
    LEFT_ARM(ParentType.LeftArm, "leftArm", true, true, 1, -2, 10),
    RIGHT_LEG(ParentType.RightLeg, "rightLeg", false, true, 0, 0, 12),
    LEFT_LEG(ParentType.LeftLeg, "leftLeg", false, true, 0, 0, 12);

    public final ParentType parentType;
    /** Имя части в playerAnimator, по которому берётся изгиб; {@code null} — часть не гнётся. */
    @Nullable
    public final String bendName;
    /** «Верхняя» часть: как и у ванильной модели, поворачивается вместе с наклоном торса. */
    public final boolean upper;
    /** {@code true} — Direction.UP (руки, ноги), {@code false} — Direction.DOWN (торс). */
    public final boolean bendsUp;
    public final float fixX;
    public final float minY;
    public final float maxY;

    Limb(ParentType parentType, @Nullable String bendName, boolean upper, boolean bendsUp,
         float fixX, float minY, float maxY) {
        this.parentType = parentType;
        this.bendName = bendName;
        this.upper = upper;
        this.bendsUp = bendsUp;
        this.fixX = fixX;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Nullable
    public static Limb of(ParentType type) {
        for (Limb limb : values()) {
            if (limb.parentType == type) {
                return limb;
            }
        }
        return null;
    }
}
