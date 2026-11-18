/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This file contains icon vector definitions copied verbatim from
 * androidx.compose.material:material-icons-extended 1.7.8 (Apache License 2.0).
 * They are bundled locally so this application does not need the full
 * (~35 MB) extended icon artifact on its build classpath. Every icon involved
 * is an official Material Design System icon by Google.
 */

package com.vauth.rxfoxyvpn.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Local copies of the Material icon vectors used by this application.
 *
 * Semantics are identical to `androidx.compose.material.icons.Icons.Filled` /
 * `Icons.AutoMirrored.Filled`; only the lookup site differs. Icons here are
 * immutable and initialized lazily on first use.
 */
object FoxyIcons {


    val Login: ImageVector
        get() {
            if (_login != null) {
                return _login!!
            }
            _login = materialIcon(name = "AutoMirrored.Filled.Login", autoMirror = true) {
                materialPath {
                    moveTo(11.0f, 7.0f)
                    lineTo(9.6f, 8.4f)
                    lineToRelative(2.6f, 2.6f)
                    horizontalLineTo(2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(10.2f)
                    lineToRelative(-2.6f, 2.6f)
                    lineTo(11.0f, 17.0f)
                    lineToRelative(5.0f, -5.0f)
                    lineTo(11.0f, 7.0f)
                    close()
                    moveTo(20.0f, 19.0f)
                    horizontalLineToRelative(-8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(8.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineTo(5.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    horizontalLineToRelative(-8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineTo(19.0f)
                    close()
                }
            }
            return _login!!
        }

    private var _login: ImageVector? = null

    val Logout: ImageVector
        get() {
            if (_logout != null) {
                return _logout!!
            }
            _logout = materialIcon(name = "AutoMirrored.Filled.Logout", autoMirror = true) {
                materialPath {
                    moveTo(17.0f, 7.0f)
                    lineToRelative(-1.41f, 1.41f)
                    lineTo(18.17f, 11.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(10.17f)
                    lineToRelative(-2.58f, 2.58f)
                    lineTo(17.0f, 17.0f)
                    lineToRelative(5.0f, -5.0f)
                    close()
                    moveTo(4.0f, 5.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(4.0f)
                    verticalLineTo(5.0f)
                    close()
                }
            }
            return _logout!!
        }

    private var _logout: ImageVector? = null

    val Apps: ImageVector
        get() {
            if (_apps != null) {
                return _apps!!
            }
            _apps = materialIcon(name = "Filled.Apps") {
                materialPath {
                    moveTo(4.0f, 8.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(8.0f, 4.0f)
                    lineTo(4.0f, 4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(10.0f, 20.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(4.0f, 20.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    lineTo(4.0f, 16.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(4.0f, 14.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    lineTo(4.0f, 10.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(10.0f, 14.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(16.0f, 4.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(20.0f, 4.0f)
                    horizontalLineToRelative(-4.0f)
                    close()
                    moveTo(10.0f, 8.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(14.0f, 4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(16.0f, 14.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                    moveTo(16.0f, 20.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(4.0f)
                    close()
                }
            }
            return _apps!!
        }

    private var _apps: ImageVector? = null

    val Campaign: ImageVector
        get() {
            if (_campaign != null) {
                return _campaign!!
            }
            _campaign = materialIcon(name = "Filled.Campaign") {
                materialPath {
                    moveTo(18.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-4.0f)
                    close()
                    moveTo(16.0f, 17.61f)
                    curveToRelative(0.96f, 0.71f, 2.21f, 1.65f, 3.2f, 2.39f)
                    curveToRelative(0.4f, -0.53f, 0.8f, -1.07f, 1.2f, -1.6f)
                    curveToRelative(-0.99f, -0.74f, -2.24f, -1.68f, -3.2f, -2.4f)
                    curveToRelative(-0.4f, 0.54f, -0.8f, 1.08f, -1.2f, 1.61f)
                    close()
                    moveTo(20.4f, 5.6f)
                    curveToRelative(-0.4f, -0.53f, -0.8f, -1.07f, -1.2f, -1.6f)
                    curveToRelative(-0.99f, 0.74f, -2.24f, 1.68f, -3.2f, 2.4f)
                    curveToRelative(0.4f, 0.53f, 0.8f, 1.07f, 1.2f, 1.6f)
                    curveToRelative(0.96f, -0.72f, 2.21f, -1.65f, 3.2f, -2.4f)
                    close()
                    moveTo(4.0f, 9.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(2.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(1.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(1.0f)
                    lineToRelative(5.0f, 3.0f)
                    lineTo(13.0f, 6.0f)
                    lineTo(8.0f, 9.0f)
                    lineTo(4.0f, 9.0f)
                    close()
                    moveTo(15.5f, 12.0f)
                    curveToRelative(0.0f, -1.33f, -0.58f, -2.53f, -1.5f, -3.35f)
                    verticalLineToRelative(6.69f)
                    curveToRelative(0.92f, -0.81f, 1.5f, -2.01f, 1.5f, -3.34f)
                    close()
                }
            }
            return _campaign!!
        }

    private var _campaign: ImageVector? = null

    val ChevronRight: ImageVector
        get() {
            if (_chevronRight != null) {
                return _chevronRight!!
            }
            _chevronRight = materialIcon(name = "Filled.ChevronRight") {
                materialPath {
                    moveTo(10.0f, 6.0f)
                    lineTo(8.59f, 7.41f)
                    lineTo(13.17f, 12.0f)
                    lineToRelative(-4.58f, 4.59f)
                    lineTo(10.0f, 18.0f)
                    lineToRelative(6.0f, -6.0f)
                    close()
                }
            }
            return _chevronRight!!
        }

    private var _chevronRight: ImageVector? = null

    val CloudDownload: ImageVector
        get() {
            if (_cloudDownload != null) {
                return _cloudDownload!!
            }
            _cloudDownload = materialIcon(name = "Filled.CloudDownload") {
                materialPath {
                    moveTo(19.35f, 10.04f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                    curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(13.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    close()
                    moveTo(17.0f, 13.0f)
                    lineToRelative(-5.0f, 5.0f)
                    lineToRelative(-5.0f, -5.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineTo(9.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(3.0f)
                    close()
                }
            }
            return _cloudDownload!!
        }

    private var _cloudDownload: ImageVector? = null

    val CloudUpload: ImageVector
        get() {
            if (_cloudUpload != null) {
                return _cloudUpload!!
            }
            _cloudUpload = materialIcon(name = "Filled.CloudUpload") {
                materialPath {
                    moveTo(19.35f, 10.04f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                    curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(13.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    close()
                    moveTo(14.0f, 13.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineTo(7.0f)
                    lineToRelative(5.0f, -5.0f)
                    lineToRelative(5.0f, 5.0f)
                    horizontalLineToRelative(-3.0f)
                    close()
                }
            }
            return _cloudUpload!!
        }

    private var _cloudUpload: ImageVector? = null

    val Code: ImageVector
        get() {
            if (_code != null) {
                return _code!!
            }
            _code = materialIcon(name = "Filled.Code") {
                materialPath {
                    moveTo(9.4f, 16.6f)
                    lineTo(4.8f, 12.0f)
                    lineToRelative(4.6f, -4.6f)
                    lineTo(8.0f, 6.0f)
                    lineToRelative(-6.0f, 6.0f)
                    lineToRelative(6.0f, 6.0f)
                    lineToRelative(1.4f, -1.4f)
                    close()
                    moveTo(14.6f, 16.6f)
                    lineToRelative(4.6f, -4.6f)
                    lineToRelative(-4.6f, -4.6f)
                    lineTo(16.0f, 6.0f)
                    lineToRelative(6.0f, 6.0f)
                    lineToRelative(-6.0f, 6.0f)
                    lineToRelative(-1.4f, -1.4f)
                    close()
                }
            }
            return _code!!
        }

    private var _code: ImageVector? = null

    val ContentCopy: ImageVector
        get() {
            if (_contentCopy != null) {
                return _contentCopy!!
            }
            _contentCopy = materialIcon(name = "Filled.ContentCopy") {
                materialPath {
                    moveTo(16.0f, 1.0f)
                    lineTo(4.0f, 1.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    horizontalLineToRelative(2.0f)
                    lineTo(4.0f, 3.0f)
                    horizontalLineToRelative(12.0f)
                    lineTo(16.0f, 1.0f)
                    close()
                    moveTo(19.0f, 5.0f)
                    lineTo(8.0f, 5.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(11.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(21.0f, 7.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(19.0f, 21.0f)
                    lineTo(8.0f, 21.0f)
                    lineTo(8.0f, 7.0f)
                    horizontalLineToRelative(11.0f)
                    verticalLineToRelative(14.0f)
                    close()
                }
            }
            return _contentCopy!!
        }

    private var _contentCopy: ImageVector? = null

    val DeleteOutline: ImageVector
        get() {
            if (_deleteOutline != null) {
                return _deleteOutline!!
            }
            _deleteOutline = materialIcon(name = "Filled.DeleteOutline") {
                materialPath {
                    moveTo(6.0f, 19.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(8.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(18.0f, 7.0f)
                    lineTo(6.0f, 7.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(8.0f, 9.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(10.0f)
                    lineTo(8.0f, 19.0f)
                    lineTo(8.0f, 9.0f)
                    close()
                    moveTo(15.5f, 4.0f)
                    lineToRelative(-1.0f, -1.0f)
                    horizontalLineToRelative(-5.0f)
                    lineToRelative(-1.0f, 1.0f)
                    lineTo(5.0f, 4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(14.0f)
                    lineTo(19.0f, 4.0f)
                    close()
                }
            }
            return _deleteOutline!!
        }

    private var _deleteOutline: ImageVector? = null

    val DeleteSweep: ImageVector
        get() {
            if (_deleteSweep != null) {
                return _deleteSweep!!
            }
            _deleteSweep = materialIcon(name = "Filled.DeleteSweep") {
                materialPath {
                    moveTo(15.0f, 16.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-4.0f)
                    close()
                    moveTo(15.0f, 8.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-7.0f)
                    close()
                    moveTo(15.0f, 12.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-6.0f)
                    close()
                    moveTo(3.0f, 18.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(6.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(13.0f, 8.0f)
                    lineTo(3.0f, 8.0f)
                    verticalLineToRelative(10.0f)
                    close()
                    moveTo(14.0f, 5.0f)
                    horizontalLineToRelative(-3.0f)
                    lineToRelative(-1.0f, -1.0f)
                    lineTo(6.0f, 4.0f)
                    lineTo(5.0f, 5.0f)
                    lineTo(2.0f, 5.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(12.0f)
                    close()
                }
            }
            return _deleteSweep!!
        }

    private var _deleteSweep: ImageVector? = null

    val Description: ImageVector
        get() {
            if (_description != null) {
                return _description!!
            }
            _description = materialIcon(name = "Filled.Description") {
                materialPath {
                    moveTo(14.0f, 2.0f)
                    lineTo(6.0f, 2.0f)
                    curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                    lineTo(4.0f, 20.0f)
                    curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                    lineTo(18.0f, 22.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(20.0f, 8.0f)
                    lineToRelative(-6.0f, -6.0f)
                    close()
                    moveTo(16.0f, 18.0f)
                    lineTo(8.0f, 18.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(16.0f, 14.0f)
                    lineTo(8.0f, 14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(13.0f, 9.0f)
                    lineTo(13.0f, 3.5f)
                    lineTo(18.5f, 9.0f)
                    lineTo(13.0f, 9.0f)
                    close()
                }
            }
            return _description!!
        }

    private var _description: ImageVector? = null

    val Dns: ImageVector
        get() {
            if (_dns != null) {
                return _dns!!
            }
            _dns = materialIcon(name = "Filled.Dns") {
                materialPath {
                    moveTo(20.0f, 13.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                    verticalLineToRelative(6.0f)
                    curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                    horizontalLineToRelative(16.0f)
                    curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                    verticalLineToRelative(-6.0f)
                    curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                    close()
                    moveTo(7.0f, 19.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                    moveTo(20.0f, 3.0f)
                    horizontalLineTo(4.0f)
                    curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                    verticalLineToRelative(6.0f)
                    curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                    horizontalLineToRelative(16.0f)
                    curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                    verticalLineTo(4.0f)
                    curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                    close()
                    moveTo(7.0f, 9.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                }
            }
            return _dns!!
        }

    private var _dns: ImageVector? = null

    val Download: ImageVector
        get() {
            if (_download != null) {
                return _download!!
            }
            _download = materialIcon(name = "Filled.Download") {
                materialPath {
                    moveTo(5.0f, 20.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(20.0f)
                    close()
                    moveTo(19.0f, 9.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineTo(5.0f)
                    lineToRelative(7.0f, 7.0f)
                    lineTo(19.0f, 9.0f)
                    close()
                }
            }
            return _download!!
        }

    private var _download: ImageVector? = null

    val Language: ImageVector
        get() {
            if (_language != null) {
                return _language!!
            }
            _language = materialIcon(name = "Filled.Language") {
                materialPath {
                    moveTo(11.99f, 2.0f)
                    curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                    curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                    close()
                    moveTo(18.92f, 8.0f)
                    horizontalLineToRelative(-2.95f)
                    curveToRelative(-0.32f, -1.25f, -0.78f, -2.45f, -1.38f, -3.56f)
                    curveToRelative(1.84f, 0.63f, 3.37f, 1.91f, 4.33f, 3.56f)
                    close()
                    moveTo(12.0f, 4.04f)
                    curveToRelative(0.83f, 1.2f, 1.48f, 2.53f, 1.91f, 3.96f)
                    horizontalLineToRelative(-3.82f)
                    curveToRelative(0.43f, -1.43f, 1.08f, -2.76f, 1.91f, -3.96f)
                    close()
                    moveTo(4.26f, 14.0f)
                    curveTo(4.1f, 13.36f, 4.0f, 12.69f, 4.0f, 12.0f)
                    reflectiveCurveToRelative(0.1f, -1.36f, 0.26f, -2.0f)
                    horizontalLineToRelative(3.38f)
                    curveToRelative(-0.08f, 0.66f, -0.14f, 1.32f, -0.14f, 2.0f)
                    curveToRelative(0.0f, 0.68f, 0.06f, 1.34f, 0.14f, 2.0f)
                    lineTo(4.26f, 14.0f)
                    close()
                    moveTo(5.08f, 16.0f)
                    horizontalLineToRelative(2.95f)
                    curveToRelative(0.32f, 1.25f, 0.78f, 2.45f, 1.38f, 3.56f)
                    curveToRelative(-1.84f, -0.63f, -3.37f, -1.9f, -4.33f, -3.56f)
                    close()
                    moveTo(8.03f, 8.0f)
                    lineTo(5.08f, 8.0f)
                    curveToRelative(0.96f, -1.66f, 2.49f, -2.93f, 4.33f, -3.56f)
                    curveTo(8.81f, 5.55f, 8.35f, 6.75f, 8.03f, 8.0f)
                    close()
                    moveTo(12.0f, 19.96f)
                    curveToRelative(-0.83f, -1.2f, -1.48f, -2.53f, -1.91f, -3.96f)
                    horizontalLineToRelative(3.82f)
                    curveToRelative(-0.43f, 1.43f, -1.08f, 2.76f, -1.91f, 3.96f)
                    close()
                    moveTo(14.34f, 14.0f)
                    lineTo(9.66f, 14.0f)
                    curveToRelative(-0.09f, -0.66f, -0.16f, -1.32f, -0.16f, -2.0f)
                    curveToRelative(0.0f, -0.68f, 0.07f, -1.35f, 0.16f, -2.0f)
                    horizontalLineToRelative(4.68f)
                    curveToRelative(0.09f, 0.65f, 0.16f, 1.32f, 0.16f, 2.0f)
                    curveToRelative(0.0f, 0.68f, -0.07f, 1.34f, -0.16f, 2.0f)
                    close()
                    moveTo(14.59f, 19.56f)
                    curveToRelative(0.6f, -1.11f, 1.06f, -2.31f, 1.38f, -3.56f)
                    horizontalLineToRelative(2.95f)
                    curveToRelative(-0.96f, 1.65f, -2.49f, 2.93f, -4.33f, 3.56f)
                    close()
                    moveTo(16.36f, 14.0f)
                    curveToRelative(0.08f, -0.66f, 0.14f, -1.32f, 0.14f, -2.0f)
                    curveToRelative(0.0f, -0.68f, -0.06f, -1.34f, -0.14f, -2.0f)
                    horizontalLineToRelative(3.38f)
                    curveToRelative(0.16f, 0.64f, 0.26f, 1.31f, 0.26f, 2.0f)
                    reflectiveCurveToRelative(-0.1f, 1.36f, -0.26f, 2.0f)
                    horizontalLineToRelative(-3.38f)
                    close()
                }
            }
            return _language!!
        }

    private var _language: ImageVector? = null

    val Layers: ImageVector
        get() {
            if (_layers != null) {
                return _layers!!
            }
            _layers = materialIcon(name = "Filled.Layers") {
                materialPath {
                    moveTo(11.99f, 18.54f)
                    lineToRelative(-7.37f, -5.73f)
                    lineTo(3.0f, 14.07f)
                    lineToRelative(9.0f, 7.0f)
                    lineToRelative(9.0f, -7.0f)
                    lineToRelative(-1.63f, -1.27f)
                    lineToRelative(-7.38f, 5.74f)
                    close()
                    moveTo(12.0f, 16.0f)
                    lineToRelative(7.36f, -5.73f)
                    lineTo(21.0f, 9.0f)
                    lineToRelative(-9.0f, -7.0f)
                    lineToRelative(-9.0f, 7.0f)
                    lineToRelative(1.63f, 1.27f)
                    lineTo(12.0f, 16.0f)
                    close()
                }
            }
            return _layers!!
        }

    private var _layers: ImageVector? = null

    val Link: ImageVector
        get() {
            if (_link != null) {
                return _link!!
            }
            _link = materialIcon(name = "Filled.Link") {
                materialPath {
                    moveTo(3.9f, 12.0f)
                    curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                    horizontalLineToRelative(4.0f)
                    lineTo(11.0f, 7.0f)
                    lineTo(7.0f, 7.0f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                    reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(-1.9f)
                    lineTo(7.0f, 15.1f)
                    curveToRelative(-1.71f, 0.0f, -3.1f, -1.39f, -3.1f, -3.1f)
                    close()
                    moveTo(8.0f, 13.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(8.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(17.0f, 7.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(1.9f)
                    horizontalLineToRelative(4.0f)
                    curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                    reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(13.0f, 17.0f)
                    horizontalLineToRelative(4.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
                    close()
                }
            }
            return _link!!
        }

    private var _link: ImageVector? = null

    val PersonAdd: ImageVector
        get() {
            if (_personAdd != null) {
                return _personAdd!!
            }
            _personAdd = materialIcon(name = "Filled.PersonAdd") {
                materialPath {
                    moveTo(15.0f, 12.0f)
                    curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                    reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                    reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
                    reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                    close()
                    moveTo(6.0f, 10.0f)
                    lineTo(6.0f, 7.0f)
                    lineTo(4.0f, 7.0f)
                    verticalLineToRelative(3.0f)
                    lineTo(1.0f, 10.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(6.0f, 10.0f)
                    close()
                    moveTo(15.0f, 14.0f)
                    curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(-2.0f)
                    curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
                    close()
                }
            }
            return _personAdd!!
        }

    private var _personAdd: ImageVector? = null

    val Public: ImageVector
        get() {
            if (_public != null) {
                return _public!!
            }
            _public = materialIcon(name = "Filled.Public") {
                materialPath {
                    moveTo(12.0f, 2.0f)
                    curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                    reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                    close()
                    moveTo(11.0f, 19.93f)
                    curveToRelative(-3.95f, -0.49f, -7.0f, -3.85f, -7.0f, -7.93f)
                    curveToRelative(0.0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f)
                    lineTo(9.0f, 15.0f)
                    verticalLineToRelative(1.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    verticalLineToRelative(1.93f)
                    close()
                    moveTo(17.9f, 17.39f)
                    curveToRelative(-0.26f, -0.81f, -1.0f, -1.39f, -1.9f, -1.39f)
                    horizontalLineToRelative(-1.0f)
                    verticalLineToRelative(-3.0f)
                    curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                    lineTo(8.0f, 12.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                    lineTo(11.0f, 7.0f)
                    horizontalLineToRelative(2.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineToRelative(-0.41f)
                    curveToRelative(2.93f, 1.19f, 5.0f, 4.06f, 5.0f, 7.41f)
                    curveToRelative(0.0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f)
                    close()
                }
            }
            return _public!!
        }

    private var _public: ImageVector? = null

    val Router: ImageVector
        get() {
            if (_router != null) {
                return _router!!
            }
            _router = materialIcon(name = "Filled.Router") {
                materialPath {
                    moveTo(20.2f, 5.9f)
                    lineToRelative(0.8f, -0.8f)
                    curveTo(19.6f, 3.7f, 17.8f, 3.0f, 16.0f, 3.0f)
                    reflectiveCurveToRelative(-3.6f, 0.7f, -5.0f, 2.1f)
                    lineToRelative(0.8f, 0.8f)
                    curveTo(13.0f, 4.8f, 14.5f, 4.2f, 16.0f, 4.2f)
                    reflectiveCurveToRelative(3.0f, 0.6f, 4.2f, 1.7f)
                    close()
                    moveTo(19.3f, 6.7f)
                    curveToRelative(-0.9f, -0.9f, -2.1f, -1.4f, -3.3f, -1.4f)
                    reflectiveCurveToRelative(-2.4f, 0.5f, -3.3f, 1.4f)
                    lineToRelative(0.8f, 0.8f)
                    curveToRelative(0.7f, -0.7f, 1.6f, -1.0f, 2.5f, -1.0f)
                    curveToRelative(0.9f, 0.0f, 1.8f, 0.3f, 2.5f, 1.0f)
                    lineToRelative(0.8f, -0.8f)
                    close()
                    moveTo(19.0f, 13.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(17.0f, 9.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(4.0f)
                    lineTo(5.0f, 13.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(4.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineToRelative(-4.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(8.0f, 18.0f)
                    lineTo(6.0f, 18.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(11.5f, 18.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(15.0f, 18.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }
            return _router!!
        }

    private var _router: ImageVector? = null

    val Schedule: ImageVector
        get() {
            if (_schedule != null) {
                return _schedule!!
            }
            _schedule = materialIcon(name = "Filled.Schedule") {
                materialPath {
                    moveTo(11.99f, 2.0f)
                    curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                    curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                    close()
                    moveTo(12.0f, 20.0f)
                    curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                    reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                    reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                    reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                    close()
                }
                materialPath {
                    moveTo(12.5f, 7.0f)
                    horizontalLineTo(11.0f)
                    verticalLineToRelative(6.0f)
                    lineToRelative(5.25f, 3.15f)
                    lineToRelative(0.75f, -1.23f)
                    lineToRelative(-4.5f, -2.67f)
                    close()
                }
            }
            return _schedule!!
        }

    private var _schedule: ImageVector? = null

    val Security: ImageVector
        get() {
            if (_security != null) {
                return _security!!
            }
            _security = materialIcon(name = "Filled.Security") {
                materialPath {
                    moveTo(12.0f, 1.0f)
                    lineTo(3.0f, 5.0f)
                    verticalLineToRelative(6.0f)
                    curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                    curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                    lineTo(21.0f, 5.0f)
                    lineToRelative(-9.0f, -4.0f)
                    close()
                    moveTo(12.0f, 11.99f)
                    horizontalLineToRelative(7.0f)
                    curveToRelative(-0.53f, 4.12f, -3.28f, 7.79f, -7.0f, 8.94f)
                    lineTo(12.0f, 12.0f)
                    lineTo(5.0f, 12.0f)
                    lineTo(5.0f, 6.3f)
                    lineToRelative(7.0f, -3.11f)
                    verticalLineToRelative(8.8f)
                    close()
                }
            }
            return _security!!
        }

    private var _security: ImageVector? = null

    val Speed: ImageVector
        get() {
            if (_speed != null) {
                return _speed!!
            }
            _speed = materialIcon(name = "Filled.Speed") {
                materialPath {
                    moveTo(20.38f, 8.57f)
                    lineToRelative(-1.23f, 1.85f)
                    arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
                    lineTo(5.07f, 18.0f)
                    arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
                    lineToRelative(1.85f, -1.23f)
                    arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                    horizontalLineToRelative(13.85f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                    arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
                    close()
                    moveTo(10.59f, 15.41f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
                    lineToRelative(5.66f, -8.49f)
                    lineToRelative(-8.49f, 5.66f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
                    close()
                }
            }
            return _speed!!
        }

    private var _speed: ImageVector? = null

    val SwapHoriz: ImageVector
        get() {
            if (_swapHoriz != null) {
                return _swapHoriz!!
            }
            _swapHoriz = materialIcon(name = "Filled.SwapHoriz") {
                materialPath {
                    moveTo(6.99f, 11.0f)
                    lineTo(3.0f, 15.0f)
                    lineToRelative(3.99f, 4.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(6.99f)
                    verticalLineToRelative(-3.0f)
                    close()
                    moveTo(21.0f, 9.0f)
                    lineToRelative(-3.99f, -4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(10.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(7.01f)
                    verticalLineToRelative(3.0f)
                    lineTo(21.0f, 9.0f)
                    close()
                }
            }
            return _swapHoriz!!
        }

    private var _swapHoriz: ImageVector? = null

    val SystemUpdateAlt: ImageVector
        get() {
            if (_systemUpdateAlt != null) {
                return _systemUpdateAlt!!
            }
            _systemUpdateAlt = materialIcon(name = "Filled.SystemUpdateAlt") {
                materialPath {
                    moveTo(12.0f, 16.5f)
                    lineToRelative(4.0f, -4.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineToRelative(-9.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(9.0f)
                    lineTo(8.0f, 12.5f)
                    lineToRelative(4.0f, 4.0f)
                    close()
                    moveTo(21.0f, 3.5f)
                    horizontalLineToRelative(-6.0f)
                    verticalLineToRelative(1.99f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(14.03f)
                    lineTo(3.0f, 19.52f)
                    lineTo(3.0f, 5.49f)
                    horizontalLineToRelative(6.0f)
                    lineTo(9.0f, 3.5f)
                    lineTo(3.0f, 3.5f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(18.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineToRelative(-14.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                }
            }
            return _systemUpdateAlt!!
        }

    private var _systemUpdateAlt: ImageVector? = null

    val Tune: ImageVector
        get() {
            if (_tune != null) {
                return _tune!!
            }
            _tune = materialIcon(name = "Filled.Tune") {
                materialPath {
                    moveTo(3.0f, 17.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(3.0f, 17.0f)
                    close()
                    moveTo(3.0f, 5.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(10.0f)
                    lineTo(13.0f, 5.0f)
                    lineTo(3.0f, 5.0f)
                    close()
                    moveTo(13.0f, 21.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(2.0f)
                    close()
                    moveTo(7.0f, 9.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(3.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(2.0f)
                    lineTo(9.0f, 9.0f)
                    lineTo(7.0f, 9.0f)
                    close()
                    moveTo(21.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(11.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(10.0f)
                    close()
                    moveTo(15.0f, 9.0f)
                    horizontalLineToRelative(2.0f)
                    lineTo(17.0f, 7.0f)
                    horizontalLineToRelative(4.0f)
                    lineTo(21.0f, 5.0f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(17.0f, 3.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(6.0f)
                    close()
                }
            }
            return _tune!!
        }

    private var _tune: ImageVector? = null

    val Upload: ImageVector
        get() {
            if (_upload != null) {
                return _upload!!
            }
            _upload = materialIcon(name = "Filled.Upload") {
                materialPath {
                    moveTo(5.0f, 20.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(20.0f)
                    close()
                    moveTo(5.0f, 10.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-6.0f)
                    horizontalLineToRelative(4.0f)
                    lineToRelative(-7.0f, -7.0f)
                    lineTo(5.0f, 10.0f)
                    close()
                }
            }
            return _upload!!
        }

    private var _upload: ImageVector? = null

    val Visibility: ImageVector
        get() {
            if (_visibility != null) {
                return _visibility!!
            }
            _visibility = materialIcon(name = "Filled.Visibility") {
                materialPath {
                    moveTo(12.0f, 4.5f)
                    curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
                    curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                    reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
                    curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                    close()
                    moveTo(12.0f, 17.0f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                    reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                    reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                    reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                    close()
                    moveTo(12.0f, 9.0f)
                    curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                    reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                    reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                    reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
                    close()
                }
            }
            return _visibility!!
        }

    private var _visibility: ImageVector? = null

    val VisibilityOff: ImageVector
        get() {
            if (_visibilityOff != null) {
                return _visibilityOff!!
            }
            _visibilityOff = materialIcon(name = "Filled.VisibilityOff") {
                materialPath {
                    moveTo(12.0f, 7.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                    curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
                    lineToRelative(2.92f, 2.92f)
                    curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
                    curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                    curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
                    lineToRelative(2.16f, 2.16f)
                    curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
                    close()
                    moveTo(2.0f, 4.27f)
                    lineToRelative(2.28f, 2.28f)
                    lineToRelative(0.46f, 0.46f)
                    curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
                    curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                    curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
                    lineToRelative(0.42f, 0.42f)
                    lineTo(19.73f, 22.0f)
                    lineTo(21.0f, 20.73f)
                    lineTo(3.27f, 3.0f)
                    lineTo(2.0f, 4.27f)
                    close()
                    moveTo(7.53f, 9.8f)
                    lineToRelative(1.55f, 1.55f)
                    curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                    curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                    curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
                    lineToRelative(1.55f, 1.55f)
                    curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                    curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
                    close()
                    moveTo(11.84f, 9.02f)
                    lineToRelative(3.15f, 3.15f)
                    lineToRelative(0.02f, -0.16f)
                    curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    lineToRelative(-0.17f, 0.01f)
                    close()
                }
            }
            return _visibilityOff!!
        }

    private var _visibilityOff: ImageVector? = null

    val OpenInNew: ImageVector
        get() {
            if (_openInNew != null) {
                return _openInNew!!
            }
            _openInNew = materialIcon(name = "AutoMirrored.Filled.OpenInNew", autoMirror = true) {
                materialPath {
                    moveTo(19.0f, 19.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(5.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(5.0f)
                    curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(7.0f)
                    close()
                    moveTo(14.0f, 3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(3.59f)
                    lineToRelative(-9.83f, 9.83f)
                    lineToRelative(1.41f, 1.41f)
                    lineTo(19.0f, 6.41f)
                    verticalLineTo(10.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineTo(3.0f)
                    horizontalLineToRelative(-7.0f)
                    close()
                }
            }
            return _openInNew!!
        }

    private var _openInNew: ImageVector? = null
}
