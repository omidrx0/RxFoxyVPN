package com.vauth.rxfoxyvpn.ui.screens

/**
 * Vector art of the neon fox, in the same 200 x 200 "orb space" as the Windows web UI
 * (traced from Neon_Fox_Power_State_UI_Showcase.png). Generated - do not edit by hand.
 *
 * Every shape is a flat float array of path commands:
 *   0 = moveTo(x, y)   1 = lineTo(x, y)   2 = cubicTo(x1, y1, x2, y2, x, y)
 *   3 = quadraticBezierTo(x1, y1, x2, y2)   4 = close()
 */
internal object FoxGeometry {
    /** body */
    val BODY = floatArrayOf(
        0f, 16.8f, 133.6f, 2f, 16.8f, 133.6f, 18.3f, 141.1f, 19.4f, 144.8f, 2f, 20.4f,
        148.5f, 21.5f, 152.5f, 23.2f, 156f, 2f, 24.9f, 159.5f, 26.9f, 162.4f, 29.6f, 165.6f,
        2f, 32.3f, 168.8f, 34.9f, 172.3f, 39.2f, 175.2f, 2f, 43.5f, 178.1f, 49.9f, 180.8f,
        55.2f, 183.2f, 2f, 60.5f, 185.6f, 65.9f, 188.1f, 71.2f, 189.6f, 2f, 76.5f, 191.1f,
        87.2f, 192f, 87.2f, 192f, 2f, 87.2f, 192f, 80.8f, 163.2f, 80.8f, 163.2f, 2f,
        80.8f, 163.2f, 74.7f, 161.2f, 71.2f, 160f, 2f, 67.7f, 158.8f, 63.5f, 157.7f, 60f,
        156f, 2f, 56.5f, 154.3f, 53.1f, 151.7f, 50.4f, 149.6f, 2f, 47.7f, 147.5f, 46.4f,
        145.3f, 44f, 143.2f, 2f, 41.6f, 141.1f, 36f, 137.1f, 36f, 137.1f, 2f, 36f,
        137.1f, 19.4f, 135.8f, 19.4f, 135.8f, 2f, 19.4f, 135.8f, 16.8f, 133.6f, 16.8f, 133.6f,
        4f,
    )

    /** st3 */
    val BODY_STREAK = floatArrayOf(
        0f, 29.6f, 128.8f, 3f, 30.9f, 151.2f, 45f, 165.3f, 3f, 68f, 180.6f, 93.6f,
        181.9f,
    )

    /** rimb */
    val BODY_RIM = floatArrayOf(
        0f, 16.8f, 133.3f, 3f, 19.4f, 146.1f, 24.5f, 156.3f, 3f, 30.9f, 167.2f, 41.1f,
        176.2f, 3f, 55.2f, 184.5f, 71.8f, 189.6f, 3f, 80.8f, 191.5f, 87.2f, 192.2f,
    )

    /** tail */
    val TAIL = floatArrayOf(
        0f, 71.2f, 189.6f, 2f, 75.3f, 192.7f, 81.9f, 191.7f, 87.2f, 192f, 2f, 92.5f,
        192.2f, 98.4f, 192.1f, 103.2f, 191.2f, 2f, 108f, 190.3f, 112.3f, 188.5f, 116f, 186.4f,
        2f, 119.7f, 184.3f, 122.9f, 181.1f, 125.6f, 178.4f, 2f, 128.3f, 175.7f, 129.3f, 173.1f,
        132f, 170.4f, 2f, 134.7f, 167.7f, 138.3f, 164.1f, 141.6f, 162.4f, 2f, 144.9f, 160.7f,
        151.8f, 160.2f, 151.8f, 160.2f, 2f, 151.8f, 160.2f, 147.6f, 158.5f, 144.8f, 157.6f, 2f,
        142f, 156.7f, 138.7f, 155.3f, 135.2f, 154.7f, 2f, 131.7f, 154.1f, 127.5f, 153.9f, 124f,
        154.1f, 2f, 120.5f, 154.3f, 117.3f, 155.1f, 114.4f, 156f, 2f, 111.5f, 156.9f, 109.1f,
        158f, 106.4f, 159.2f, 2f, 103.7f, 160.4f, 101.1f, 162.4f, 98.4f, 163.2f, 2f, 95.7f,
        164f, 93.3f, 164f, 90.4f, 164f, 2f, 87.5f, 164f, 84f, 163.9f, 80.8f, 163.2f,
        2f, 77.6f, 162.6f, 74.2f, 158.3f, 71.2f, 160f, 2f, 68.2f, 161.8f, 62.9f, 173.6f,
        62.9f, 173.6f, 2f, 62.9f, 173.6f, 67.1f, 186.5f, 71.2f, 189.6f, 4f,
    )

    /** blade */
    val TAIL_BLADE = floatArrayOf(
        0f, 66.7f, 162.7f, 3f, 87.2f, 167.8f, 106.4f, 161.4f, 3f, 123.7f, 154.4f, 144.8f,
        157.6f, 1f, 151.8f, 160.2f, 3f, 138.4f, 165.3f, 122.4f, 170.4f, 3f, 100f, 176.8f,
        80.8f, 171.7f, 3f, 69.3f, 167.8f, 66.7f, 162.7f, 4f,
    )

    /** st1 */
    val TAIL_STREAK_1 = floatArrayOf(
        0f, 55.2f, 158.9f, 3f, 80.8f, 173.6f, 112.8f, 167.2f, 3f, 132f, 162.7f, 148f,
        159.5f,
    )

    /** st2 */
    val TAIL_STREAK_2 = floatArrayOf(
        0f, 45.6f, 148f, 3f, 68f, 176.8f, 106.4f, 178.1f, 3f, 122.4f, 175.5f, 133.3f,
        167.8f,
    )

    /** rimt */
    val TAIL_RIM = floatArrayOf(
        0f, 87.2f, 192.2f, 3f, 103.8f, 191.5f, 116.6f, 187.7f, 3f, 122.4f, 183.2f, 125.6f,
        178.7f,
    )

    /** glint */
    val TAIL_GLINT = floatArrayOf(
        0f, 71.2f, 160.2f, 3f, 90.4f, 165.3f, 106.4f, 158.9f, 3f, 123.7f, 153.8f, 144.8f,
        157.6f, 1f, 151.8f, 160.2f,
    )

    /** earL */
    val EAR_L = floatArrayOf(
        0f, 24.5f, 110.2f, 1f, 36.6f, 91f, 1f, 40.5f, 101.3f, 1f, 36f, 107.7f,
        4f,
    )

    /** earR */
    val EAR_R = floatArrayOf(
        0f, 37.9f, 105.1f, 1f, 42.9f, 93.6f, 1f, 46.2f, 107.7f, 4f,
    )

    /** head */
    val HEAD = floatArrayOf(
        0f, 29.6f, 103.8f, 2f, 27.7f, 105f, 25.1f, 108.1f, 23.2f, 110.2f, 2f, 21.3f,
        112.4f, 19.5f, 114.3f, 18.4f, 116.6f, 2f, 17.3f, 118.9f, 16.7f, 121.2f, 16.5f, 124f,
        2f, 16.2f, 126.8f, 16.3f, 130.1f, 16.8f, 133.6f, 2f, 17.3f, 137.1f, 19.4f, 144.8f,
        19.4f, 144.8f, 2f, 19.4f, 144.8f, 44f, 143.2f, 44f, 143.2f, 2f, 44f, 143.2f,
        42.9f, 139.7f, 42.4f, 138.4f, 2f, 41.9f, 137.1f, 41.5f, 136.5f, 41.1f, 135.2f, 2f,
        40.7f, 133.9f, 39.8f, 130.4f, 39.8f, 130.4f, 2f, 39.8f, 130.4f, 42.8f, 129.3f, 45.6f,
        129.4f, 2f, 48.4f, 129.6f, 53.8f, 131.3f, 56.8f, 131.2f, 2f, 59.8f, 131.1f, 62f,
        129.5f, 63.5f, 128.8f, 2f, 65.1f, 128.1f, 66.1f, 126.9f, 66.1f, 126.9f, 2f, 66.1f,
        126.9f, 61.8f, 124.3f, 60f, 123f, 2f, 58.2f, 121.8f, 56.5f, 120.8f, 55.2f, 119.2f,
        2f, 53.9f, 117.6f, 53.3f, 115.1f, 52f, 113.4f, 2f, 50.7f, 111.7f, 48.5f, 110.4f,
        47.2f, 109f, 2f, 45.9f, 107.5f, 45.3f, 105.7f, 44f, 104.8f, 2f, 42.7f, 103.9f,
        40.7f, 104.1f, 39.2f, 103.8f, 2f, 37.7f, 103.6f, 36.3f, 103.5f, 34.7f, 103.5f, 2f,
        33.1f, 103.5f, 31.5f, 102.7f, 29.6f, 103.8f, 4f,
    )

    /** hl */
    val HEAD_HIGHLIGHT = floatArrayOf(
        0f, 24.5f, 110.2f, 3f, 30.2f, 103.8f, 36.6f, 92.3f, 1f, 38.6f, 100f, 3f,
        46.2f, 105.1f, 52.6f, 113.4f, 3f, 42.4f, 110.2f, 30.9f, 116.6f, 4f,
    )

    /** eye */
    val EYE = floatArrayOf(
        0f, 42.4f, 116.6f, 1f, 50.7f, 119.2f,
    )

    /** nose centre */
    val NOSE_X = 65.6f
    val NOSE_Y = 126.9f

    /** cracks that spread over the glass in the error state (connected network, starts on the rim) */
    val CRACKS: List<FloatArray> = listOf(
        floatArrayOf(0f, 150f, 26f, 1f, 142f, 44f, 1f, 151f, 58f, 1f, 140f, 74f, 1f, 148f, 92f, 1f, 139f, 108f, 1f, 150f, 126f, 1f, 143f, 142f, 1f, 130f, 160f),
        floatArrayOf(0f, 142f, 44f, 1f, 128f, 51f, 1f, 121f, 63f),
        floatArrayOf(0f, 140f, 74f, 1f, 124f, 80f, 1f, 114f, 93f),
        floatArrayOf(0f, 150f, 126f, 1f, 166f, 134f, 1f, 172f, 149f),
        floatArrayOf(0f, 187f, 100f, 1f, 168f, 108f, 1f, 161f, 122f, 1f, 150f, 126f),
        floatArrayOf(0f, 14f, 74f, 1f, 34f, 86f, 1f, 29f, 101f),
        floatArrayOf(0f, 112f, 13f, 1f, 117f, 28f, 1f, 108f, 41f),
        floatArrayOf(0f, 130f, 160f, 1f, 118f, 172f, 1f, 116f, 184f),
    )
}
