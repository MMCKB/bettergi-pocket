package com.zenlesszonezero.pocket.recognition

import com.zenlesszonezero.pocket.capture.Frame
import com.zenlesszonezero.pocket.recognition.area.GameCaptureRegion
import com.zenlesszonezero.pocket.recognition.area.ImageRegion
import com.zenlesszonezero.pocket.recognition.area.Region
import com.zenlesszonezero.pocket.recognition.ocr.IOcrService
import com.zenlesszonezero.pocket.recognition.ocr.OcrFactory
import com.zenlesszonezero.pocket.recognition.opencv.MatOps
import com.zenlesszonezero.pocket.recognition.opencv.OpenCvRuntime
import org.opencv.core.Mat

/**
 * 一帧捕获结果，对应 BetterGI 的 CaptureContent。
 * [captureRectArea] 是缩到不超过 1080P 宽后的识别区域。
 */
class CaptureContent(
    val nativeRegion: GameCaptureRegion,
    val captureRectArea: ImageRegion,
    val scale: CaptureScale,
    val frameIndex: Int,
) : AutoCloseable {
    fun find(ro: RecognitionObject): Region = captureRectArea.find(ro)

    fun findMulti(ro: RecognitionObject): List<Region> = captureRectArea.findMulti(ro)

    override fun close() {
        if (captureRectArea !== nativeRegion) {
            captureRectArea.close()
        }
        nativeRegion.close()
    }

    companion object {
        fun fromBgr(
            bgr: Mat,
            width: Int,
            height: Int,
            frameIndex: Int = 0,
            ocrService: IOcrService = OcrFactory.default,
        ): CaptureContent {
            if (!OpenCvRuntime.ensureLoaded()) {
                throw IllegalStateException("OpenCV is not loaded")
            }
            val native = GameCaptureRegion(bgr, 0, 0, ocrService = ocrService)
            val recognition = native.deriveTo1080P()
            return CaptureContent(
                nativeRegion = native,
                captureRectArea = recognition,
                scale = CaptureScale.fromCaptureSize(width, height),
                frameIndex = frameIndex,
            )
        }

        fun fromFrame(
            frame: Frame,
            frameIndex: Int = 0,
            ocrService: IOcrService = OcrFactory.default,
        ): CaptureContent = fromBgr(
            bgr = MatOps.frameToBgr(frame),
            width = frame.width,
            height = frame.height,
            frameIndex = frameIndex,
            ocrService = ocrService,
        )
    }
}
