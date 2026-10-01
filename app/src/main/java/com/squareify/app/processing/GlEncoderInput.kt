package com.squareify.app.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Hands finished frames to a video encoder through OpenGL: each bitmap is uploaded as a texture
 * and drawn onto the encoder's input surface, and the encoder's own hardware converts the colours.
 * Much quicker than converting every pixel to YUV on the CPU.
 *
 * Like [GlFrameReader], it must be created, used and released on one thread; it makes its own
 * EGL context current before drawing, so it can share that thread with frame readers.
 */
internal class GlEncoderInput(private val surface: Surface, private val width: Int, private val height: Int) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val texture: Int
    private var textureWidth = 0
    private var textureHeight = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(QUAD).also { it.position(0) }

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            // The encoder's surface only takes configs marked as recordable.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config"
        }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            throw IllegalStateException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        makeCurrent()
        program = createProgram()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Sends [frame] (the encoder's size) to the encoder, shown at [presentationTimeUs]. */
    fun draw(frame: Bitmap, presentationTimeUs: Long) {
        makeCurrent()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (frame.width != textureWidth || frame.height != textureHeight) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
            textureWidth = frame.width
            textureHeight = frame.height
        } else {
            // Same size every frame: refill the texture instead of making a new one.
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame)
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "drawing a frame failed: 0x${Integer.toHexString(error)}" }
        // The timestamp travels with the frame into the encoder.
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeUs * 1000)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun release() {
        makeCurrent()
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        surface.release()
        // No eglTerminate: the display is shared with the frame readers.
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        /** EGLExt.EGL_RECORDABLE_ANDROID, which EGL14 doesn't name. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        /**
         * Full-viewport quad as x, y, s, t. The bitmap's top row is texture row 0 and the
         * framebuffer's bottom is y = -1, so t runs 1 → 0 bottom to top to keep the picture upright.
         */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
    }
}
