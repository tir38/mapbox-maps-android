package com.mapbox.maps.renderer.widget

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.mapbox.maps.renderer.gl.GlUtils
import com.mapbox.maps.renderer.gl.GlUtils.put
import com.mapbox.maps.renderer.gl.GlUtils.toFloatBuffer

internal class BitmapWidgetRenderer(
  @Volatile
  private var bitmap: Bitmap?,
  private val position: WidgetPosition,
  private val marginX: Float,
  private val marginY: Float,
) : WidgetRenderer {

  private var bitmapWidth = bitmap?.width ?: 0
  private var bitmapHeight = bitmap?.height ?: 0

  private var surfaceWidth = 0
  private var surfaceHeight = 0

  private var program = 0
  private var vertexShader = 0
  private var fragmentShader = 0

  private var attributeVertexPosition = 0
  private var attributeTexturePosition = 0
  private var uniformTexture = 0
  private var uniformMvpMatrix = 0
  private val textures = intArrayOf(0)

  private var screenMatrix = FloatArray(16)
  private var translateRotate = FloatArray(16)
  private val rotationMatrix = GlUtils.getIdentityMatrix()
  private val translateMatrix = GlUtils.getIdentityMatrix()
  private val mvpMatrix = GlUtils.getIdentityMatrix()
  private val mvpMatrixBuffer = mvpMatrix.toFloatBuffer()

  private var updateMatrix: Boolean = true

  private val vertexPositionBuffer = FloatArray(8).toFloatBuffer()
  private val texturePositionBuffer = floatArrayOf(
    0f, 0f,
    0f, 1f,
    1f, 0f,
    1f, 1f
  ).toFloatBuffer()

  override var needRender: Boolean = true

  override fun onSurfaceChanged(width: Int, height: Int) {
    surfaceWidth = width
    surfaceHeight = height

    // transforms from (0,0) - (width, height) in screen pixels
    // to (-1, -1) - (1, 1) for GL
    screenMatrix.put(
      2f / width, 0f, 0f, 0f,
      0f, -2f / height, 0f, 0f,
      0f, 0f, 0f, 0f,
      -1f, 1f, 0f, 1f
    )

    Matrix.translateM(
      translateMatrix,
      0,
      leftX(),
      topY(),
      0f
    )

    updateVertexBuffer()

    updateMatrix = true
    needRender = true
  }

  private fun updateVertexBuffer() {
    // in pixels, (-bitmapWidth / 2, -bitmapHeight/2) - (bitmapWidth / 2, bitmapHeight/2)
    vertexPositionBuffer.put(
      -bitmapWidth / 2f, -bitmapHeight / 2f,
      -bitmapWidth / 2f, bitmapHeight / 2f,
      bitmapWidth / 2f, -bitmapHeight / 2f,
      bitmapWidth / 2f, bitmapHeight / 2f,
    )
  }

  private fun topY() = when (position.vertical) {
    WidgetPosition.Vertical.BOTTOM -> surfaceHeight.toFloat() - bitmapHeight.toFloat() / 2f - marginY
    WidgetPosition.Vertical.CENTER -> surfaceHeight.toFloat() / 2 - bitmapHeight.toFloat() / 2f + marginY
    WidgetPosition.Vertical.TOP -> marginY + bitmapHeight.toFloat() / 2f
  }

  private fun leftX() = when (position.horizontal) {
    WidgetPosition.Horizontal.LEFT -> marginX + bitmapWidth.toFloat() / 2f
    WidgetPosition.Horizontal.CENTER -> surfaceWidth.toFloat() / 2 - bitmapWidth.toFloat() / 2f + marginX
    WidgetPosition.Horizontal.RIGHT -> surfaceWidth.toFloat() - bitmapWidth.toFloat() / 2f - marginX
  }

  override fun prepare() {
    val maxAttrib = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, maxAttrib, 0)

    vertexShader = GlUtils.loadShader(
      GLES20.GL_VERTEX_SHADER,
      VERTEX_SHADER_CODE
    ).also(GlUtils::checkCompileStatus)

    fragmentShader = GlUtils.loadShader(
      GLES20.GL_FRAGMENT_SHADER,
      FRAGMENT_SHADER_CODE
    ).also(GlUtils::checkCompileStatus)

    program = GLES20.glCreateProgram().also { program ->
      GlUtils.checkError("glCreateProgram")

      GLES20.glAttachShader(program, vertexShader)
      GlUtils.checkError("glAttachShader")

      GLES20.glAttachShader(program, fragmentShader)
      GlUtils.checkError("glAttachShader")

      GLES20.glLinkProgram(program)
      GlUtils.checkError("glLinkProgram")
    }

    uniformMvpMatrix =
      GLES20.glGetUniformLocation(program, "uMvpMatrix")
    GlUtils.checkError("glGetUniformLocation")

    attributeVertexPosition =
      GLES20.glGetAttribLocation(program, "aPosition")
    GlUtils.checkError("glGetAttribLocation")

    attributeTexturePosition =
      GLES20.glGetAttribLocation(program, "aCoordinate")
    GlUtils.checkError("glGetAttribLocation")

    uniformTexture =
      GLES20.glGetUniformLocation(program, "uTexture")
    GlUtils.checkError("glGetUniformLocation")

    needRender = true
  }

  override fun render() {
    if (program == 0) {
      prepare()
    }
    GLES20.glUseProgram(program)
    GlUtils.checkError("glUseProgram")

    if (updateMatrix) {
      Matrix.setIdentityM(mvpMatrix, 0)

      Matrix.multiplyMM(translateRotate, 0, translateMatrix, 0, rotationMatrix, 0)
      Matrix.multiplyMM(mvpMatrix, 0, screenMatrix, 0, translateRotate, 0)

      mvpMatrixBuffer.rewind()
      mvpMatrixBuffer.put(mvpMatrix)
      mvpMatrixBuffer.rewind()

      updateMatrix = false
    }

    GLES20.glUniformMatrix4fv(uniformMvpMatrix, 1, false, mvpMatrixBuffer)

    textureFromBitmapIfChanged()

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])

    GLES20.glUniform1i(uniformTexture, 0)

    GLES20.glClearColor(1f, 1f, 1f, 1f)

    GLES20.glEnableVertexAttribArray(attributeVertexPosition)
    GlUtils.checkError("glEnableVertexAttribArray")

    GLES20.glVertexAttribPointer(
      attributeVertexPosition, COORDS_PER_VERTEX,
      GLES20.GL_FLOAT, false,
      VERTEX_STRIDE, vertexPositionBuffer
    )
    GlUtils.checkError("glVertexAttribPointer")

    GLES20.glEnableVertexAttribArray(attributeTexturePosition)
    GlUtils.checkError("glEnableVertexAttribArray")

    GLES20.glVertexAttribPointer(
      attributeTexturePosition, COORDS_PER_VERTEX,
      GLES20.GL_FLOAT, false,
      VERTEX_STRIDE, texturePositionBuffer
    )
    GlUtils.checkError("glVertexAttribPointer")

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
    GlUtils.checkError("glDrawArrays")

    GLES20.glDisableVertexAttribArray(attributeVertexPosition)
    GLES20.glDisableVertexAttribArray(attributeTexturePosition)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    GLES20.glUseProgram(0)

    needRender = false
  }

  override fun release() {
    if (program != 0) {
      GLES20.glDisableVertexAttribArray(attributeVertexPosition)
      GLES20.glDetachShader(program, vertexShader)
      GLES20.glDetachShader(program, fragmentShader)
      GLES20.glDeleteShader(vertexShader)
      GLES20.glDeleteShader(fragmentShader)
      GLES20.glDeleteTextures(textures.size, textures, 0)
      GLES20.glDeleteProgram(program)
      program = 0
    }
    needRender = false
  }

  /**
   * Updates texture from bitmap once and nullifies bitmap.
   */
  private fun textureFromBitmapIfChanged() {
    bitmap?.let {
      if (textures[0] == 0) {
        GLES20.glGenTextures(1, textures, 0)
      }
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_NEAREST.toFloat()
      )
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR.toFloat()
      )
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE.toFloat()
      )
      GLES20.glTexParameterf(
        GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE.toFloat()
      )
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

      bitmap = null
    }
  }

  fun updateBitmap(bitmap: Bitmap) {
    this.bitmap = bitmap
    this.bitmapWidth = bitmap.width
    this.bitmapHeight = bitmap.height
    updateVertexBuffer()
    updateMatrix = true
    needRender = true
  }

  override fun setRotation(angleDegrees: Float) {
    Matrix.setIdentityM(rotationMatrix, 0)
    Matrix.setRotateM(rotationMatrix, 0, angleDegrees, 0f, 0f, 1f)
    updateMatrix = true
    needRender = true
  }

  override fun setTranslation(translationX: Float, translationY: Float) {
    Matrix.setIdentityM(translateMatrix, 0)
    Matrix.translateM(
      translateMatrix,
      0,
      leftX() + translationX,
      topY() + translationY,
      0f
    )

    updateMatrix = true
    needRender = true
  }

  private companion object {
    const val COORDS_PER_VERTEX = 2
    const val BYTES_PER_FLOAT = 4
    const val VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT
    const val VERTEX_COUNT = 4

    val VERTEX_SHADER_CODE = """
      precision highp float;
      uniform mat4 uMvpMatrix;
      attribute vec2 aPosition;
      attribute vec2 aCoordinate;
      varying vec2 vCoordinate;
      void main() {
        vCoordinate = aCoordinate;
        gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
      }
    """.trimIndent()

    val FRAGMENT_SHADER_CODE = """
      precision mediump float;
      uniform sampler2D uTexture;
      varying vec2 vCoordinate;
      void main() {
        gl_FragColor = texture2D(uTexture, vCoordinate);
      }
    """.trimIndent()
  }
}