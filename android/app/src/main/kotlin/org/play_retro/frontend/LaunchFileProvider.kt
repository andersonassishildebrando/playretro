//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  LaunchFileProvider.kt
//
//  FileProvider used for %ROMPROVIDER% game launches. A dedicated subclass avoids
//  conflicts with FileProvider declarations from libraries.
//

package org.play_retro.frontend

import androidx.core.content.FileProvider

class LaunchFileProvider : FileProvider()
