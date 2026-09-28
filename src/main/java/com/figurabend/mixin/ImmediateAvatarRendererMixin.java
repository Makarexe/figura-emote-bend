package com.figurabend.mixin;

import com.figurabend.BendFrame;
import com.figurabend.Limb;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.Helper;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.AvatarRenderer;
import org.figuramc.figura.model.rendering.EntityRenderMode;
import org.figuramc.figura.model.rendering.ImmediateAvatarRenderer;
import org.figuramc.figura.model.rendering.Vertex;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Figura рисует модельку сама: берёт у ванильной модели игрока только позицию и поворот
 * частей (руки, ноги, торс, голова). Изгибы Emotecraft (playerAnimator + bendy-lib) при этом
 * терялись:
 * <ul>
 *   <li>локоть/колено/поясница — это деформация ванильного куба, у Figura её нет,
 *       и рука оставалась прямой палкой, «ломаясь» относительно ванильной позы;</li>
 *   <li>наклон торса («body») ванильная модель делает поворотом PoseStack для головы и рук,
 *       а Figura его не видела — руки и голова отрывались от груди.</li>
 * </ul>
 * Здесь оба эффекта повторяются в рендере Figura: группы-части тела запоминаются при обходе
 * дерева модели, верхним частям добавляется наклон торса, а вершины внутри сгибаемых частей
 * деформируются так же, как ванильный куб (см. {@link BendFrame}).
 */
@Mixin(ImmediateAvatarRenderer.class)
public abstract class ImmediateAvatarRendererMixin extends AvatarRenderer {
    /** Та же высота оси наклона торса, что и в {@code IBendHelper#rotateMatrixStack} (в блоках). */
    @Unique
    private static final double figurabend$BODY_PIVOT = 0.375;

    @Shadow
    @Final
    protected PartCustomization.PartCustomizationStack customizationStack;

    @Unique
    private final ArrayDeque<BendFrame> figurabend$frames = new ArrayDeque<>();
    @Unique
    @Nullable
    private AnimationApplier figurabend$animation;
    @Unique
    private boolean figurabend$pushingBent;

    private ImmediateAvatarRendererMixin(Avatar avatar) {
        super(avatar);
    }

    @Shadow
    public abstract void pushFaces(int faceCount, int[] remainingComplexity, FiguraTextureSet textureSet, List<Vertex> vertices);

    @Inject(method = "commonRender", at = @At("HEAD"))
    private void figurabend$beginRender(double vertOffset, CallbackInfoReturnable<Integer> cir) {
        figurabend$frames.clear();
        figurabend$animation = figurabend$findAnimation();
    }

    @Unique
    @Nullable
    private AnimationApplier figurabend$findAnimation() {
        if (!Helper.isBendEnabled() || !(entity instanceof IAnimatedPlayer player)) {
            return null;
        }
        // В руках от первого лица playerAnimator сам сбрасывает изгибы — там и мы не гнём.
        EntityRenderMode mode = avatar.renderMode;
        if (mode == EntityRenderMode.FIRST_PERSON || mode == EntityRenderMode.FIRST_PERSON_WORLD) {
            return null;
        }
        AnimationApplier animation = player.playerAnimator_getAnimation();
        return animation != null && animation.isActive() ? animation : null;
    }

    /** Сразу после того, как матрица части легла на стек: здесь её ещё можно подправить для всего поддерева. */
    @Inject(
            method = "renderPart",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/figuramc/figura/model/PartCustomization$PartCustomizationStack;push(Lorg/figuramc/figura/model/PartCustomization;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void figurabend$afterPush(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate,
                                      CallbackInfoReturnable<Boolean> cir) {
        AnimationApplier animation = figurabend$animation;
        if (animation == null) {
            return;
        }
        PartCustomization peek = customizationStack.peek();
        Limb limb = Limb.of(part.parentType);
        if (limb != null) {
            if (limb.upper && !figurabend$insideUpperPart()) {
                figurabend$applyBodyBend(peek, animation.getBend("body"));
            }
            figurabend$frames.push(BendFrame.of(part, limb, figurabend$toJoml(peek.positionMatrix),
                    figurabend$toJoml(part.customization.getPivot()), figurabend$bendOf(animation, limb)));
            return;
        }

        BendFrame frame = figurabend$frames.peek();
        if (part.parentType.isPivot && frame != null && frame.active()) {
            PartCustomization custom = part.customization;
            FiguraVec3 pivot = custom.getPivot().add(custom.getOffsetPivot()).subtract(custom.getOffsetPos());
            Vector3d viewPoint = figurabend$toJoml(peek.positionMatrix).transformPosition(figurabend$toJoml(pivot));
            if (frame.isOnRotatedSide(viewPoint)) {
                figurabend$premultiply(peek, frame.rigidRotationInView());
                // поддерево уже повёрнуто целиком — второй раз его вершины не гнём
                figurabend$frames.push(BendFrame.passive(part, null));
            }
        }
    }

    @Inject(method = "renderPart", at = @At("RETURN"))
    private void figurabend$afterPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate,
                                      CallbackInfoReturnable<Boolean> cir) {
        BendFrame frame = figurabend$frames.peek();
        if (frame != null && frame.owner == part) {
            figurabend$frames.pop();
        }
    }

    @Inject(method = "pushFaces", at = @At("HEAD"), cancellable = true)
    private void figurabend$bendFaces(int faceCount, int[] remainingComplexity, FiguraTextureSet textureSet,
                                      List<Vertex> vertices, CallbackInfo ci) {
        if (figurabend$pushingBent || faceCount <= 0) {
            return;
        }
        BendFrame frame = figurabend$frames.peek();
        if (frame == null || !frame.active()) {
            return;
        }
        int faces = Math.min(faceCount, vertices.size() / 4);
        List<Vertex> bent = frame.deformFaces(figurabend$toJoml(customizationStack.peek().positionMatrix), vertices, faces);
        figurabend$pushingBent = true;
        try {
            pushFaces(bent.size() / 4, remainingComplexity, textureSet, bent);
        } finally {
            figurabend$pushingBent = false;
        }
        ci.cancel();
    }

    @Unique
    private boolean figurabend$insideUpperPart() {
        for (BendFrame frame : figurabend$frames) {
            if (frame.limb != null && frame.limb.upper) {
                return true;
            }
        }
        return false;
    }

    @Unique
    @Nullable
    private static Pair<Float, Float> figurabend$bendOf(AnimationApplier animation, Limb limb) {
        if (limb.bendName == null) {
            return null;
        }
        Pair<Float, Float> bend = animation.getBend(limb.bendName);
        if (limb == Limb.BODY) {
            // куб торса гнётся на torso + body, как в AnimationApplier#updatePart
            Pair<Float, Float> body = animation.getBend("body");
            return new Pair<>(bend.getLeft() + body.getLeft(), bend.getRight() + body.getRight());
        }
        return bend;
    }

    /**
     * Наклон торса для головы и рук: ванильная модель поворачивает их PoseStack-ом вокруг
     * точки на высоте 6px ({@code IBendHelper#rotateMatrixStack}). Поворот задан в пространстве
     * модели (матрица {@code posMat}), поэтому матрицу части умножаем на posMat·B·posMat⁻¹.
     */
    @Unique
    private void figurabend$applyBodyBend(PartCustomization peek, Pair<Float, Float> bend) {
        if (Math.abs(bend.getRight()) < 1.0E-4F) {
            return;
        }
        float axisAngle = -bend.getLeft();
        Matrix4d modelToView = figurabend$toJoml(posMat);
        Matrix4d bodyBend = new Matrix4d()
                .translation(0, figurabend$BODY_PIVOT, 0)
                .rotate(bend.getRight(), Math.cos(axisAngle), 0, Math.sin(axisAngle))
                .translate(0, -figurabend$BODY_PIVOT, 0);
        Matrix4d inView = new Matrix4d(modelToView).mul(bodyBend).mul(new Matrix4d(modelToView).invert());
        figurabend$premultiply(peek, inView);
    }

    @Unique
    private static void figurabend$premultiply(PartCustomization peek, Matrix4d transform) {
        peek.positionMatrix.set(new Matrix4d(transform).mul(figurabend$toJoml(peek.positionMatrix)));
        Matrix3d normal = transform.normal(new Matrix3d()).mul(figurabend$toJoml(peek.normalMatrix));
        peek.normalMatrix.set(new Matrix3f(
                (float) normal.m00, (float) normal.m01, (float) normal.m02,
                (float) normal.m10, (float) normal.m11, (float) normal.m12,
                (float) normal.m20, (float) normal.m21, (float) normal.m22));
    }

    @Unique
    private static Matrix4d figurabend$toJoml(FiguraMat4 m) {
        return new Matrix4d(
                m.v11, m.v21, m.v31, m.v41,
                m.v12, m.v22, m.v32, m.v42,
                m.v13, m.v23, m.v33, m.v43,
                m.v14, m.v24, m.v34, m.v44);
    }

    @Unique
    private static Matrix3d figurabend$toJoml(FiguraMat3 m) {
        return new Matrix3d(m.toMatrix3f());
    }

    @Unique
    private static Vector3d figurabend$toJoml(FiguraVec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
