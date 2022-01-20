package com.mapbox.maps.renderer

import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.mapbox.common.Logger
import com.mapbox.maps.renderer.egl.EGLCore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLSurface
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * The render thread is responsible for the communication between any thread and the render thread it creates.
 * It is also responsible for EGL set up, managing context, window surfaces etc.
 */
internal class MapboxRenderThread : Choreographer.FrameCallback {

  internal val renderHandlerThread: RenderHandlerThread
  private val translucentSurface: Boolean
  private val mapboxRenderer: MapboxRenderer
  private val eglCore: EGLCore

  private val lock = ReentrantLock()
  private val createCondition = lock.newCondition()
  private val destroyCondition = lock.newCondition()

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal val renderEventQueue = CopyOnWriteArrayList<RenderEvent>()
  private val snapshotQueue = CopyOnWriteArrayList<Runnable>()

  private var surface: Surface? = null
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal var eglSurface: EGLSurface? = null
  private var width: Int = 0
  private var height: Int = 0

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal val renderTimeNs = AtomicLong(0)
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  private var needRenderOnResume = false
  private var expectedVsyncWakeTimeNs = 0L
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal val awaitingNextVsync = AtomicBoolean(false)
  private var sizeChanged = false
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal var paused = false
  private var shouldExit = false
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal var eglPrepared = false
  private var nativeRenderCreated = false
  private var nativeRenderNotSupported = false

  internal var fpsChangedListener: OnFpsChangedListener? = null
  private var timeElapsed = 0L

  constructor(
    mapboxRenderer: MapboxRenderer,
    translucentSurface: Boolean,
    antialiasingSampleCount: Int,
  ) {
    this.translucentSurface = translucentSurface
    this.mapboxRenderer = mapboxRenderer
    this.eglCore = EGLCore(translucentSurface, antialiasingSampleCount)
    renderHandlerThread = RenderHandlerThread().apply { start() }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  constructor(
    mapboxRenderer: MapboxRenderer,
    handlerThread: RenderHandlerThread,
    eglCore: EGLCore
  ) {
    this.translucentSurface = false
    this.mapboxRenderer = mapboxRenderer
    this.renderHandlerThread = handlerThread
    this.eglCore = eglCore
  }

  private fun postPrepareRenderFrame(delayMillis: Long = 0L) {
    renderHandlerThread.postDelayed(
      {
        prepareRenderFrame()
      },
      delayMillis
    )
  }

  private fun checkSurfaceReady(creatingSurface: Boolean): Boolean {
    lock.withLock {
      try {
        surface?.let {
          if (!nativeRenderCreated) {
            return prepareEglSurface(it, creatingSurface)
          }
        } ?: return false
        return true
      } finally {
        createCondition.signal()
      }
    }
  }

  private fun prepareEglSurface(surface: Surface, creatingSurface: Boolean): Boolean {
    if (!eglPrepared) {
      eglCore.prepareEgl()
      if (eglCore.eglStatusSuccess) {
        eglPrepared = true
      } else {
        Logger.e(TAG, "EGL was not configured, please check logs above.")
        nativeRenderNotSupported = true
        return false
      }
    }
    if (!surface.isValid) {
      Logger.w(TAG, "EGL was configured but surface is not valid.")
      // give system a bit of time and try rendering again hoping surface will be valid now
      postPrepareRenderFrame(delayMillis = RETRY_DELAY_MS)
      return false
    }
    // on Android SDK <= 23 at least on x86 emulators we need to force set EGL10.EGL_NO_CONTEXT
    // when resuming activity
    if (creatingSurface) {
      eglCore.makeNothingCurrent()
    }
    if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
      eglSurface = eglCore.createWindowSurface(surface)
      if (!eglCore.eglStatusSuccess) {
        // Set EGL Surface as EGL_NO_SURFACE and try recreate it in next iteration.
        eglSurface = EGL10.EGL_NO_SURFACE
        postPrepareRenderFrame(delayMillis = RETRY_DELAY_MS)
        return false
      }
    }
    eglSurface?.let {
      val eglContextAttached = eglCore.makeCurrent(it)
      if (!eglContextAttached) {
        Logger.w(TAG, "EGL was configured but context could not be made current. Trying again in a moment...")
        postPrepareRenderFrame(delayMillis = RETRY_DELAY_MS)
      }
    }

    if (!nativeRenderCreated) {
      mapboxRenderer.onSurfaceCreated()
      nativeRenderCreated = true
    }
    mapboxRenderer.onSurfaceChanged(
      width = width,
      height = height
    )
    return true
  }

  private fun checkSurfaceSizeChanged() {
    if (sizeChanged) {
      mapboxRenderer.onSurfaceChanged(
        width = width,
        height = height
      )
      sizeChanged = false
    }
  }

  private fun draw() {
    val renderTimeNsCopy = renderTimeNs.get()
    val currentTimeNs = SystemClock.elapsedRealtimeNanos()
    val expectedEndRenderTimeNs = currentTimeNs + renderTimeNsCopy
    if (expectedVsyncWakeTimeNs > currentTimeNs) {
      return
    }
    mapboxRenderer.onDrawFrame()
    // assuming render event queue holds user's runnables with OpenGL ES commands
    // it makes sense to execute them after drawing a map but before swapping buffers
    if (renderEventQueue.isNotEmpty()) {
      renderEventQueue.forEach {
        it.runnable?.run()
      }
      renderEventQueue.clear()
    }
    // snapshots should be taken before swapBuffers otherwise buffers may be already empty
    snapshotQueue.apply {
      if (isNotEmpty()) {
        forEach(Runnable::run)
        clear()
      }
    }
    eglSurface?.let {
      when (val swapStatus = eglCore.swapBuffers(it)) {
        EGL10.EGL_SUCCESS -> {}
        EGL11.EGL_CONTEXT_LOST -> {
          Logger.w(TAG, "Context lost. Waiting for re-acquire")
          releaseEgl()
        }
        else -> {
          Logger.w(TAG, "eglSwapBuffer error: $swapStatus. Waiting for new surface")
          releaseEglSurface()
        }
      }
    }
    val actualEndRenderTimeNs = SystemClock.elapsedRealtimeNanos()
    if (renderTimeNsCopy != 0L && actualEndRenderTimeNs < expectedEndRenderTimeNs) {
      // we need to stop swap buffers for less than time requested in order to have some time to render upcoming frame
      // before next vsync so it will be drawn, otherwise we will drop it
      expectedVsyncWakeTimeNs = expectedEndRenderTimeNs - ONE_MILLISECOND_NS
    }
    fpsChangedListener?.let {
      val fps = 1E9 / (actualEndRenderTimeNs - timeElapsed)
      if (timeElapsed != 0L) {
        it.onFpsChanged(fps)
      }
      timeElapsed = actualEndRenderTimeNs
    }
  }

  private fun releaseEgl() {
    releaseEglSurface()
    if (eglPrepared) {
      eglCore.release()
    }
    eglPrepared = false
  }

  private fun releaseEglSurface() {
    eglSurface?.let {
      eglCore.releaseSurface(it)
    }
    eglSurface = null
  }

  private fun releaseAll() {
    mapboxRenderer.onSurfaceDestroyed()
    nativeRenderCreated = false
    releaseEgl()
    surface?.release()
  }

  private fun prepareRenderFrame(creatingSurface: Boolean = false) {
    // Check first if we have to stop rendering at all (even if there was no EGL config) and cleanup EGL.
    // We need to check it ASAP in order not to block thread that is calling `onSurfaceTextureDestroyed`.
    // After that check MapView could be actually rendered on this device (has valid EGL config).
    // After that we check if activity / fragment is paused.
    if (shouldExit || nativeRenderNotSupported || paused) {
      if (paused) {
        needRenderOnResume = true
      }
      // at least on Android 8 devices we create surface before Activity#onStart
      // so we need to proceed to EGL creation in any case to avoid deadlock
      if (!creatingSurface) {
        return
      }
    }
    if (!checkSurfaceReady(creatingSurface)) {
      return
    }
    checkSurfaceSizeChanged()
    // listen to next VSYNC event if not listening already
    if (awaitingNextVsync.compareAndSet(false, true)) {
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  @UiThread
  fun onSurfaceSizeChanged(width: Int, height: Int) {
    if (this.width != width || this.height != height) {
      renderHandlerThread.post {
        this.width = width
        this.height = height
        sizeChanged = true
        prepareRenderFrame()
      }
    }
  }

  @UiThread
  fun onSurfaceDestroyed() {
    lock.withLock {
      // in some situations `destroy` is called earlier than onSurfaceDestroyed - in that case no need to clean up
      if (renderHandlerThread.started) {
        renderHandlerThread.post {
          awaitingNextVsync.set(false)
          Choreographer.getInstance().removeFrameCallback(this)
          shouldExit = true
          lock.withLock {
            // TODO https://github.com/mapbox/mapbox-maps-android/issues/607
            if (nativeRenderCreated && mapboxRenderer is MapboxTextureViewRenderer) {
              releaseAll()
            } else {
              releaseEglSurface()
            }
            destroyCondition.signal()
          }
        }
        destroyCondition.await()
      }
    }
  }

  @UiThread
  fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
    lock.withLock {
      renderHandlerThread.post {
        if (this.surface != surface) {
          releaseEgl()
          this.surface?.release()
          this.surface = surface
        }
        this.width = width
        this.height = height
        shouldExit = false
        // we clean only Mapbox events to avoid outdated runnables associated with previous EGL context
        val iterator = renderEventQueue.iterator()
        while (iterator.hasNext()) {
          val next = iterator.next()
          if (next.eventType == EventType.MAPBOX) {
            iterator.remove()
          }
        }
        snapshotQueue.clear()
        // we do not want to clear render events scheduled by user
        renderHandlerThread.clearMessageQueue(clearAll = false)
        prepareRenderFrame(creatingSurface = true)
      }
      createCondition.await()
    }
  }

  @AnyThread
  fun setMaximumFps(fps: Int) {
    renderTimeNs.set(ONE_SECOND_NS / fps)
  }

  @WorkerThread
  override fun doFrame(frameTimeNanos: Long) {
    awaitingNextVsync.set(false)
    // it makes sense to draw not only when EGL config is prepared but when native renderer is created
    if (nativeRenderCreated && !paused && !shouldExit) {
      draw()
    }
  }

  // MapRenderer delegate methods

  @AnyThread
  fun queueRenderEvent(renderEvent: RenderEvent) {
    if (renderEvent.needRender) {
      renderEvent.runnable?.let {
        renderEventQueue.add(renderEvent)
      }
      postPrepareRenderFrame()
    } else {
      postNonRenderEvent(renderEvent)
    }
  }

  private fun postNonRenderEvent(renderEvent: RenderEvent, delayMillis: Long = 0L) {
    renderHandlerThread.postDelayed(
      {
        // at the time we start executing surface may be already destroyed
        if (!shouldExit) {
          if (nativeRenderCreated) {
            renderEvent.runnable?.run()
          } else {
            postNonRenderEvent(renderEvent, delayMillis = RETRY_DELAY_MS)
          }
        }
      },
      delayMillis,
      renderEvent.eventType
    )
  }

  @AnyThread
  fun queueSnapshot(performSnapshotTask: Runnable) {
    snapshotQueue.add(performSnapshotTask)
    postPrepareRenderFrame()
  }

  @UiThread
  fun pause() {
    renderHandlerThread.post {
      paused = true
    }
  }

  @UiThread
  fun resume() {
    renderHandlerThread.post {
      paused = false
      if (needRenderOnResume) {
        prepareRenderFrame()
        needRenderOnResume = false
      }
    }
  }

  @UiThread
  internal fun destroy() {
    lock.withLock {
      // do nothing if destroy for some reason called more than once to avoid deadlock
      if (renderHandlerThread.started) {
        renderHandlerThread.post {
          lock.withLock {
            if (nativeRenderCreated) {
              releaseAll()
            }
            renderHandlerThread.clearMessageQueue(clearAll = true)
            destroyCondition.signal()
          }
        }
        destroyCondition.await()
      }
    }
    renderHandlerThread.stop()
    mapboxRenderer.map = null
  }

  private companion object {
    const val TAG = "Mbgl-RenderThread"
    const val RETRY_DELAY_MS = 50L
    val ONE_SECOND_NS = 10.0.pow(9.0).toLong()
    val ONE_MILLISECOND_NS = 10.0.pow(6.0).toLong()
  }
}