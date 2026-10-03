package com.example.cardengine

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Multi-card recognition + tracking engine.
 *
 * Holds one ORB reference per card. On every frame in SEARCHING state, tries every
 * reference and takes the one with the most RANSAC inliers that still passes the
 * threshold. On success, seeds LK tracking for that card until the track dies.
 */
class CardEngine(cards: List<CardCatalog.LoadedCard>) {

    enum class State { SEARCHING, TRACKING }

    class Result(
        val cardId: String?,
        val state: State,
        val mode: String,
        val corners: FloatArray?,
        val inliers: Int,
        val ms: Double
    )

    private class Ref(
        val id: String,
        val pts: Array<Point>,
        val desc: Mat,
        val corners: MatOfPoint2f
    )

    private class Fit(val cardId: String, val corners: FloatArray, val inliers: Int)

    private class RawMatch(
        val ref: Ref,
        val homography: Mat,
        val inlierRef: List<Point>,
        val inlierCur: List<Point>
    )

    companion object {
        private const val REF_WIDTH = 480.0
        private const val RATIO = 0.85f
        private const val MIN_GOOD_MATCHES = 8
        private const val MIN_DETECT_INLIERS = 10
        private const val MIN_TRACK_POINTS = 6
        private const val REDETECT_EVERY = 60
    }

    private val orb = ORB.create(1000, 1.2f, 8, 15, 0, 2, ORB.HARRIS_SCORE, 15, 7)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    private val refs: List<Ref>
    private var state = State.SEARCHING
    private var currentRef: Ref? = null
    private val prevGray = Mat()
    private var trackRef = MatOfPoint2f()
    private var trackCur = MatOfPoint2f()
    private var framesSinceDetect = 0

    init {
        val built = ArrayList<Ref>(cards.size)
        for (c in cards) {
            val full = c.referenceGray
            if (full.empty()) continue
            val scale = REF_WIDTH / full.cols()
            val ref = Mat()
            Imgproc.resize(full, ref, Size(REF_WIDTH, full.rows() * scale))
            val kp = MatOfKeyPoint()
            val desc = Mat()
            orb.detectAndCompute(ref, Mat(), kp, desc)
            if (desc.rows() < MIN_DETECT_INLIERS) {
                kp.release(); ref.release(); desc.release()
                continue
            }
            val pts = kp.toArray().map { it.pt }.toTypedArray()
            val w = ref.cols().toDouble()
            val h = ref.rows().toDouble()
            val corners = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
            built.add(Ref(c.id, pts, desc, corners))
            kp.release(); ref.release()
        }
        refs = built
    }

    val cardCount: Int get() = refs.size

    fun process(gray: Mat): Result {
        val t0 = System.nanoTime()
        var fit: Fit? = null
        var mode = "ORB"

        if (state == State.TRACKING) {
            mode = "LK"
            fit = track(gray)
            if (fit == null) {
                state = State.SEARCHING
                currentRef = null
            } else if (++framesSinceDetect >= REDETECT_EVERY) {
                val fresh = detect(gray)
                if (fresh != null) fit = fresh
                mode = "LK+ORB"
            }
        }
        if (state == State.SEARCHING) {
            mode = "ORB"
            fit = detect(gray)
        }

        val ms = (System.nanoTime() - t0) / 1e6
        return Result(fit?.cardId, state, mode, fit?.corners, fit?.inliers ?: 0, ms)
    }

    private fun detect(gray: Mat): Fit? {
        val kp = MatOfKeyPoint()
        val desc = Mat()
        try {
            orb.detectAndCompute(gray, Mat(), kp, desc)
            if (desc.rows() < 2) return null
            val framePts = kp.toArray()

            var best: RawMatch? = null
            for (ref in refs) {
                val m = matchOne(ref, framePts, desc) ?: continue
                if (best == null || m.inlierRef.size > best.inlierRef.size) best = m
            }
            if (best == null) return null

            val corners = projectCorners(best.ref, best.homography, gray.cols(), gray.rows())
                ?: return null

            currentRef = best.ref
            trackRef = MatOfPoint2f(*best.inlierRef.toTypedArray())
            trackCur = MatOfPoint2f(*best.inlierCur.toTypedArray())
            gray.copyTo(prevGray)
            framesSinceDetect = 0
            state = State.TRACKING
            return Fit(best.ref.id, corners, best.inlierRef.size)
        } finally {
            kp.release(); desc.release()
        }
    }

    private fun matchOne(ref: Ref, framePts: Array<KeyPoint>, frameDesc: Mat): RawMatch? {
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(ref.desc, frameDesc, knn, 2)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (m in knn) {
            val a = m.toArray()
            if (a.size == 2 && a[0].distance < RATIO * a[1].distance) {
                src.add(ref.pts[a[0].queryIdx])
                dst.add(framePts[a[0].trainIdx].pt)
            }
        }
        knn.forEach { it.release() }
        if (src.size < MIN_GOOD_MATCHES) return null

        val srcM = MatOfPoint2f(*src.toTypedArray())
        val dstM = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        val h = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 4.0, mask)
        if (h.empty()) return null

        val flags = ByteArray(src.size)
        mask.get(0, 0, flags)
        val inRef = ArrayList<Point>()
        val inCur = ArrayList<Point>()
        for (i in flags.indices) if (flags[i].toInt() != 0) { inRef.add(src[i]); inCur.add(dst[i]) }
        if (inRef.size < MIN_DETECT_INLIERS) return null

        return RawMatch(ref, h, inRef, inCur)
    }

    private fun track(gray: Mat): Fit? {
        val ref = currentRef ?: return null
        val next = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        Video.calcOpticalFlowPyrLK(prevGray, gray, trackCur, next, status, err, Size(21.0, 21.0), 3)

        val st = status.toArray()
        val nxt = next.toArray()
        val refArr = trackRef.toArray()
        val keepRef = ArrayList<Point>()
        val keepCur = ArrayList<Point>()
        for (i in st.indices) if (st[i].toInt() != 0) { keepRef.add(refArr[i]); keepCur.add(nxt[i]) }
        next.release(); status.release(); err.release()
        if (keepRef.size < MIN_TRACK_POINTS) return null

        val srcM = MatOfPoint2f(*keepRef.toTypedArray())
        val dstM = MatOfPoint2f(*keepCur.toTypedArray())
        val mask = Mat()
        val h = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 3.0, mask)
        if (h.empty()) return null

        val flags = ByteArray(keepRef.size)
        mask.get(0, 0, flags)
        val inRef = ArrayList<Point>()
        val inCur = ArrayList<Point>()
        for (i in flags.indices) if (flags[i].toInt() != 0) { inRef.add(keepRef[i]); inCur.add(keepCur[i]) }
        if (inRef.size < MIN_TRACK_POINTS) return null

        val corners = projectCorners(ref, h, gray.cols(), gray.rows()) ?: return null

        trackRef = MatOfPoint2f(*inRef.toTypedArray())
        trackCur = MatOfPoint2f(*inCur.toTypedArray())
        gray.copyTo(prevGray)
        return Fit(ref.id, corners, inRef.size)
    }

    private fun projectCorners(ref: Ref, h: Mat, fw: Int, fh: Int): FloatArray? {
        val out = MatOfPoint2f()
        Core.perspectiveTransform(ref.corners, out, h)
        val p = out.toArray()
        out.release()
        if (p.size != 4) return null

        var pos = 0; var neg = 0
        var area = 0.0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 0) pos++ else if (cross < 0) neg++
            area += a.x * b.y - b.x * a.y
        }
        area = Math.abs(area) / 2.0
        if (pos != 4 && neg != 4) return null
        if (area < 0.01 * fw * fh || area > 4.0 * fw * fh) return null

        return FloatArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toFloat() }
    }
}