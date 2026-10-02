# Third-party components

ParseHub Studio includes the MIT-licensed ParseHub project by z-mio:
https://github.com/z-mio/ParseHub (see the repository root LICENSE).

The desktop shell uses Tauri (MIT / Apache-2.0) and bundles Python and its
dependencies via PyInstaller. Their original licenses continue to apply.

Windows packages also include FFmpeg and FFprobe from the Chocolatey ffmpeg
package / Gyan release essentials build. FFmpeg is distributed under the GNU
GPL for this build, separately from the MIT-licensed Studio source. See the
license and version files shipped in resources/ffmpeg, https://ffmpeg.org/legal.html,
https://www.gyan.dev/ffmpeg/builds/ and https://github.com/FFmpeg/FFmpeg for
build information and corresponding FFmpeg source. Binary build provenance and
checksums are included in each GitHub Actions artifact.
