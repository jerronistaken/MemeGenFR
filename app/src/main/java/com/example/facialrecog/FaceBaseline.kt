package com.example.facialrecog

object FaceBaseline {
    private val mouthOpenSamples  = mutableListOf<Float>()
    private val mouthCurveSamples = mutableListOf<Float>()
    private val browRaiseSamples  = mutableListOf<Float>()
    private val browFurrowSamples = mutableListOf<Float>()

    var neutralMouthOpen: Float? = null; private set
    var neutralMouthCurve: Float? = null; private set
    var neutralBrowRaise: Float? = null; private set
    var neutralBrowFurrow: Float? = null; private set

    var threshSurprisedMouthOpen: Float = 50f; private set
    var threshAngryFurrow: Float = -9f; private set
    var threshSadFurrow: Float = -12f; private set

    val isCalibrated: Boolean get() = neutralMouthOpen != null
    val sampleCount: Int get() = mouthOpenSamples.size
    val targetSamples = 60

    fun addSample(mouthOpen: Float?, mouthCurve: Float?, browRaise: Float?, browFurrow: Float?) {
        if (isCalibrated) return
        mouthOpen?.let { mouthOpenSamples.add(it) }
        mouthCurve?.let { mouthCurveSamples.add(it) }
        browRaise?.let { browRaiseSamples.add(it) }
        browFurrow?.let { browFurrowSamples.add(it) }
        if (mouthOpenSamples.size >= targetSamples) computeBaseline()
    }

    private fun computeBaseline() {
        neutralMouthOpen = mouthOpenSamples.median()
        neutralMouthCurve = mouthCurveSamples.median()
        neutralBrowRaise = browRaiseSamples.median()
        neutralBrowFurrow = browFurrowSamples.median()

        val nMouthOpen = neutralMouthOpen!!
        val nBrowFurrow = neutralBrowFurrow!!

        threshSurprisedMouthOpen = nMouthOpen + 25f
        threshAngryFurrow = nBrowFurrow + 3.5f
        threshSadFurrow = nBrowFurrow - 3f
    }

    fun reset() {
        mouthOpenSamples.clear()
        mouthCurveSamples.clear()
        browRaiseSamples.clear()
        browFurrowSamples.clear()
        neutralMouthOpen = null
        neutralMouthCurve = null
        neutralBrowRaise = null
        neutralBrowFurrow = null
    }

    private fun List<Float>.median(): Float {
        val sorted = sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }
}