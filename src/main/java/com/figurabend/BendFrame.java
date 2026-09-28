package com.figurabend;

import dev.kosmx.playerAnim.core.util.Pair;
import org.figuramc.figura.model.rendering.Vertex;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Группа модели Figura, привязанная к части тела игрока (LeftArm, Body, ...), и изгиб,
 * который эмоция сейчас накладывает на эту часть.
 * <p>
 * Emotecraft сгибает ванильную модель через bendy-lib: куб руки разбит на пиксельные
 * полоски, половина со стороны кисти поворачивается вокруг локтя, а обе половины
 * скашиваются так, чтобы сойтись на биссекторной плоскости — получается ровный «митровый»
 * сгиб без щелей. У моделей Figura вершины свои, поэтому здесь та же формула
 * ({@code IBendable#applyBend}) применяется к каждой вершине, а грани, пересекающие
 * плоскость сгиба, разрезаются по ней надвое — иначе у длинного куба просто не было бы
 * вершин в районе локтя.
 * <p>
 * Системы координат:
 * <ul>
 *   <li><b>view</b> — то, что Figura отправляет в VertexConsumer (матрица {@code positionMatrix});</li>
 *   <li><b>group</b> — локальные координаты группы-части тела (пиксели Blockbench);</li>
 *   <li><b>L</b> — локальные координаты ванильной ModelPart: начало в точке поворота группы,
 *       X и Y перевёрнуты (Figura рисует модель повёрнутой на 180° вокруг Z).
 *       В них заданы размеры из {@link Limb} и работает формула bendy-lib.</li>
 * </ul>
 */
public final class BendFrame {
    private static final double EPSILON = 1.0E-4;

    /** Часть модели Figura, открывшая этот кадр (кадр снимается, когда её рендер закончится). */
    public final Object owner;
    @Nullable
    public final Limb limb;
    private final boolean active;

    private Matrix4d groupToView;
    private Matrix4d viewToGroup;
    /** group → L: сдвиг к точке поворота и разворот X/Y. */
    private Matrix4d groupToLocal;

    private Vector3d dir;
    private Vector3d fix;
    private Vector3d bendPlaneNormal;
    private Matrix3d rotation;
    private double tanHalf;
    private double sign;
    private double tBase;
    private double tOther;
    private double tMid;
    private double half;

    private BendFrame(Object owner, @Nullable Limb limb, boolean active) {
        this.owner = owner;
        this.limb = limb;
        this.active = active;
    }

    /** Кадр, который ничего не сгибает (голова, или поддерево, уже повёрнутое целиком). */
    public static BendFrame passive(Object owner, @Nullable Limb limb) {
        return new BendFrame(owner, limb, false);
    }

    public static BendFrame of(Object owner, Limb limb, Matrix4d groupToView, Vector3d pivot,
                               @Nullable Pair<Float, Float> bend) {
        if (limb.bendName == null || bend == null || Math.abs(bend.getRight()) < EPSILON) {
            return passive(owner, limb);
        }
        BendFrame frame = new BendFrame(owner, limb, true);
        frame.groupToView = new Matrix4d(groupToView);
        frame.viewToGroup = new Matrix4d(groupToView).invert();
        frame.groupToLocal = new Matrix4d().scaling(-1, -1, 1).translate(-pivot.x, -pivot.y, -pivot.z);

        float axisAngle = bend.getLeft();
        float value = bend.getRight();
        // Как в IBendable#applyBend: ось лежит в плоскости XZ и поворачивается вместе с
        // направлением куба (Direction.DOWN — поворот на 180° вокруг X).
        frame.sign = limb.bendsUp ? 1 : -1;
        frame.dir = new Vector3d(0, frame.sign, 0);
        Vector3d axis = new Vector3d(Math.cos(axisAngle), 0, frame.sign * Math.sin(axisAngle));
        frame.fix = new Vector3d(limb.fixX, (limb.minY + limb.maxY) / 2.0, 0);
        frame.bendPlaneNormal = new Vector3d(frame.dir).cross(axis).normalize();
        frame.rotation = new Matrix3d().rotation(value, axis);
        frame.tanHalf = Math.tan(value / 2.0);
        // Плоскости по концам куба: «base» — конец, который поворачивается.
        frame.tBase = limb.bendsUp ? limb.maxY : -limb.minY;
        frame.tOther = limb.bendsUp ? limb.minY : -limb.maxY;
        frame.tMid = (frame.tBase + frame.tOther) / 2.0;
        frame.half = (limb.maxY - limb.minY) / 2.0;
        return frame;
    }

    public boolean active() {
        return active;
    }

    private double along(Vector3d local) {
        return sign * local.y;
    }

    /** Лежит ли точка (view) в поворачивающейся половине: у кисти/стопы или в груди. */
    public boolean isOnRotatedSide(Vector3d viewPoint) {
        Vector3d local = viewToGroup.transformPosition(new Vector3d(viewPoint));
        groupToLocal.transformPosition(local);
        return along(local) > tMid;
    }

    /**
     * Жёсткий поворот поворачивающейся половины в view-координатах. Нужен точкам крепления
     * (LeftItemPivot и т.п.): ванильный предмет в руке playerAnimator поворачивает вместе
     * с кистью, а Figura с точкой крепления рисует его сама.
     */
    public Matrix4d rigidRotationInView() {
        Matrix4d localToGroup = new Matrix4d(groupToLocal).invert();
        Matrix4d rotateAroundFix = new Matrix4d()
                .translation(fix)
                .mul(new Matrix4d().set(rotation))
                .translate(-fix.x, -fix.y, -fix.z);
        return new Matrix4d(groupToView)
                .mul(localToGroup)
                .mul(rotateAroundFix)
                .mul(groupToLocal)
                .mul(viewToGroup);
    }

    /** Формула bendy-lib для одной точки в координатах L. */
    private void deform(Vector3d local, boolean rotated) {
        double distFromBend = bendPlaneNormal.x * (local.x - fix.x)
                + bendPlaneNormal.y * (local.y - fix.y)
                + bendPlaneNormal.z * (local.z - fix.z);
        if (limb.bendsUp) {
            distFromBend = -distFromBend;
        }
        double shift = tanHalf * distFromBend;
        double t = along(local);
        // bendHeight() у bendy-lib для Direction.DOWN отрицательный, отсюда множитель sign.
        // Ограничение [0, 1] — чтобы вершины за пределами ванильного куба (длинные руки
        // модельки) не растягивало: там сдвиг, как и у концов куба, нулевой.
        if (rotated) {
            double factor = sign * clamp(-(t - tBase) / half, 0, 1);
            local.fma(factor * shift, dir);
            local.sub(fix);
            rotation.transform(local);
            local.add(fix);
        } else {
            double factor = sign * clamp(-(t - tOther) / half, -1, 0);
            local.fma(factor * shift, dir);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Сгибает грани одной части модели.
     *
     * @param partToView матрица части (positionMatrix вершины детали Figura)
     * @param vertices   вершины, по 4 на грань
     * @param faceCount  сколько граней рисовать
     * @return новые вершины, по 4 на грань (грани, пересекающие сгиб, разрезаны)
     */
    public List<Vertex> deformFaces(Matrix4d partToView, List<Vertex> vertices, int faceCount) {
        Matrix4d toLocal = new Matrix4d(groupToLocal).mul(viewToGroup).mul(partToView);
        Matrix4d fromLocal = new Matrix4d(toLocal).invert();
        Matrix3d normalToLocal = toLocal.normal(new Matrix3d());
        Matrix3d normalFromLocal = fromLocal.normal(new Matrix3d());
        Matrix3d rotateNormal = new Matrix3d(normalFromLocal).mul(rotation).mul(normalToLocal);

        List<Vertex> out = new ArrayList<>(faceCount * 4 + 8);
        Point[] quad = new Point[4];
        List<Point> rotatedPart = new ArrayList<>(6);
        List<Point> fixedPart = new ArrayList<>(6);
        for (int face = 0; face < faceCount; face++) {
            int rotatedCount = 0;
            for (int i = 0; i < 4; i++) {
                Vertex vertex = vertices.get(face * 4 + i);
                Vector3d local = toLocal.transformPosition(new Vector3d(vertex.x, vertex.y, vertex.z));
                quad[i] = new Point(local, along(local), vertex.u, vertex.v, vertex.nx, vertex.ny, vertex.nz);
                if (quad[i].t > tMid) {
                    rotatedCount++;
                }
            }
            if (rotatedCount == 0 || rotatedCount == 4) {
                emit(out, List.of(quad), rotatedCount == 4, fromLocal, rotateNormal);
                continue;
            }
            rotatedPart.clear();
            fixedPart.clear();
            for (int i = 0; i < 4; i++) {
                Point a = quad[i];
                Point b = quad[(i + 1) % 4];
                boolean aRotated = a.t > tMid;
                (aRotated ? rotatedPart : fixedPart).add(a);
                if (aRotated != (b.t > tMid)) {
                    Point cut = Point.lerp(a, b, (tMid - a.t) / (b.t - a.t), tMid);
                    rotatedPart.add(cut);
                    fixedPart.add(cut);
                }
            }
            emit(out, rotatedPart, true, fromLocal, rotateNormal);
            emit(out, fixedPart, false, fromLocal, rotateNormal);
        }
        return out;
    }

    /** Выводит выпуклый многоугольник веером из четырёхугольников (последняя вершина может повторяться). */
    private void emit(List<Vertex> out, List<Point> polygon, boolean rotated, Matrix4d fromLocal, Matrix3d rotateNormal) {
        int n = polygon.size();
        if (n < 3) {
            return;
        }
        Vertex[] converted = new Vertex[n];
        for (int i = 0; i < n; i++) {
            converted[i] = toVertex(polygon.get(i), rotated, fromLocal, rotateNormal);
        }
        for (int i = 1; i + 1 < n; i += 2) {
            out.add(converted[0]);
            out.add(converted[i]);
            out.add(converted[i + 1]);
            out.add(converted[Math.min(i + 2, n - 1)]);
        }
    }

    private Vertex toVertex(Point point, boolean rotated, Matrix4d fromLocal, Matrix3d rotateNormal) {
        Vector3d pos = new Vector3d(point.local);
        deform(pos, rotated);
        fromLocal.transformPosition(pos);
        Vector3d normal = new Vector3d(point.nx, point.ny, point.nz);
        if (rotated) {
            rotateNormal.transform(normal);
            if (normal.lengthSquared() > 1.0E-12) {
                normal.normalize();
            }
        }
        return new Vertex((float) pos.x, (float) pos.y, (float) pos.z, point.u, point.v,
                (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private record Point(Vector3d local, double t, float u, float v, float nx, float ny, float nz) {
        static Point lerp(Point a, Point b, double k, double t) {
            return new Point(
                    new Vector3d(a.local).lerp(b.local, k),
                    t,
                    (float) (a.u + (b.u - a.u) * k),
                    (float) (a.v + (b.v - a.v) * k),
                    (float) (a.nx + (b.nx - a.nx) * k),
                    (float) (a.ny + (b.ny - a.ny) * k),
                    (float) (a.nz + (b.nz - a.nz) * k)
            );
        }
    }
}
