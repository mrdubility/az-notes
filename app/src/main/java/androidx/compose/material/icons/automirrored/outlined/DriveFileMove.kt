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

package androidx.compose.material.icons.automirrored.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Outlined.DriveFileMove: ImageVector
    get() {
        if (_driveFileMove != null) {
            return _driveFileMove!!
        }
        _driveFileMove = materialIcon(name = "AutoMirrored.Outlined.DriveFileMove", autoMirror =
                true) {
            materialPath {
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-8.0f)
                lineToRelative(-2.0f, -2.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
                close()
                moveTo(20.0f, 18.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(5.17f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.17f, 8.0f)
                horizontalLineTo(20.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(12.16f, 12.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.16f)
                lineToRelative(-1.59f, 1.59f)
                lineTo(11.99f, 17.0f)
                lineTo(16.0f, 13.01f)
                lineTo(11.99f, 9.0f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(12.16f, 12.0f)
                close()
            }
        }
        return _driveFileMove!!
    }

private var _driveFileMove: ImageVector? = null
