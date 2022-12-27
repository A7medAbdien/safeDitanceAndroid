/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.safeDistnace.kotlin

import android.content.Intent
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.MlKitException
import com.safeDistnace.CameraXViewModel
import com.safeDistnace.GraphicOverlay
import com.safeDistnace.R
import com.safeDistnace.VisionImageProcessor
import com.safeDistnace.kotlin.posedetector.PoseDetectorProcessor
import com.safeDistnace.preference.PreferenceUtils
import com.safeDistnace.preference.SettingsActivity
import com.safeDistnace.preference.SettingsActivity.LaunchSource

/** Live preview demo app for ML Kit APIs using CameraX. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
class CameraXLivePreviewActivity :
  AppCompatActivity(), CompoundButton.OnCheckedChangeListener {
  // Custom View that displays the camera feed for CameraX's Preview use case.
  // This class manages the Surface lifecycle, as well as the preview aspect ratio and orientation.
  // ! Internally, it uses either a TextureView or SurfaceView to display the camera feed.
  private var previewView: PreviewView? = null
  private var graphicOverlay: GraphicOverlay? = null

  // used to bind the lifecycle of cameras to any LifecycleOwner within an application's process.
  private var cameraProvider: ProcessCameraProvider? = null

  // camera preview stream for displaying on-screen
  private var previewUseCase: Preview? = null
  // CPU accessible images for an app to perform image analysis on.
  /**
   * ImageAnalysis acquires images from the camera via an ImageReader.
   * Each image is provided to an ImageAnalysis.Analyzer function which can be implemented by application code,
   * where it can access image data for application analysis via an ImageProxy.
   */
  // provides CPU-accessible buffers for analysis, such as for machine learning inference :)
  private var analysisUseCase: ImageAnalysis? = null

  // An interface to process the images with different vision detectors and custom image models.
  private var imageProcessor: VisionImageProcessor? = null
  private var needUpdateGraphicOverlayImageSourceInfo = false
  private var selectedModel = POSE_DETECTION

  // CameraSelector: A set of requirements and priorities used to select a camera or return a filtered set of cameras.
  private var lensFacing = CameraSelector.LENS_FACING_BACK
  private var cameraSelector: CameraSelector? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate")
    // insure a safe state if nothing got selected !!
    if (savedInstanceState != null) {
      selectedModel = savedInstanceState.getString(STATE_SELECTED_MODEL, OBJECT_DETECTION)
    }
    // that will select our camera
    cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    // ! -------------------------------- xml connections ----------------------------------
    setContentView(R.layout.activity_vision_camerax_live_preview)
    previewView = findViewById(R.id.preview_view)
    if (previewView == null) {
      Log.d(TAG, "previewView is null")
    }
    graphicOverlay = findViewById(R.id.graphic_overlay)
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null")
    }

    // cam switching
//    val facingSwitch = findViewById<ToggleButton>(R.id.facing_switch)
//    facingSwitch.setOnCheckedChangeListener(this)

    val settingsButton = findViewById<ImageView>(R.id.settings_button)
    settingsButton.setOnClickListener {
      val intent = Intent(applicationContext, SettingsActivity::class.java)
      intent.putExtra(SettingsActivity.EXTRA_LAUNCH_SOURCE, LaunchSource.CAMERAX_LIVE_PREVIEW)
      startActivity(intent)
    }
    // ! --------------------------------- xml connections done --------------------------------

    // To Bind CameraX useCases
    // Creating a ViewModel that can interact with CameraX with live data, images/frames,
    // because it will be observed by ProcessCameraProvider!!
    // * Sets the cameraProvider and bind UseCase of CameraX, show use of CameraX tutorial
    ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
      .get(CameraXViewModel::class.java)
      .processCameraProvider
      .observe(
        this,
        Observer { provider: ProcessCameraProvider? ->
          cameraProvider = provider
          bindAllCameraUseCases()
        }
      )
  }

  // ! ---------------------- changing the cam/Lens, rebind all cases ---------------------------
  override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
    if (cameraProvider == null) {
      return
    }
    val newLensFacing =
      if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        CameraSelector.LENS_FACING_BACK
      } else {
        CameraSelector.LENS_FACING_FRONT
      }
    val newCameraSelector = CameraSelector.Builder().requireLensFacing(newLensFacing).build()
    try {
      if (cameraProvider!!.hasCamera(newCameraSelector)) {
        Log.d(TAG, "Set facing to " + newLensFacing)
        lensFacing = newLensFacing
        cameraSelector = newCameraSelector
        bindAllCameraUseCases()
        return
      }
    } catch (e: CameraInfoUnavailableException) {
      // Falls through
    }
    Toast.makeText(
      applicationContext,
      "This device does not have lens with facing: $newLensFacing",
      Toast.LENGTH_SHORT
    )
      .show()
  }
  // ! --------------------------- changing the cam/Lens, rebind all cases -------------------------

  public override fun onResume() {
    super.onResume()
    bindAllCameraUseCases()
  }

  override fun onPause() {
    super.onPause()
    imageProcessor?.run { this.stop() }
  }

  public override fun onDestroy() {
    super.onDestroy()
    imageProcessor?.run { this.stop() }
  }

  private fun bindAllCameraUseCases() {
    if (cameraProvider != null) {
      // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
      cameraProvider!!.unbindAll()
      bindPreviewUseCase()
      bindAnalysisUseCase()
    }
  }

  // * preview Use case
  private fun bindPreviewUseCase() {
    if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
      return
    }
    if (cameraProvider == null) {
      return
    }
    if (previewUseCase != null) {
      cameraProvider!!.unbind(previewUseCase)
    }

    val builder = Preview.Builder()
    //  handel settings
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    previewUseCase = builder.build()
    // This interface is implemented by the application to provide a Surface.
    // This will be called by CameraX when it needs a Surface for Preview.
    // It also signals when the Surface is no longer in use by CameraX.
    previewUseCase!!.setSurfaceProvider(previewView!!.getSurfaceProvider())
    /**
     * @param lifecycleOwner The lifecycleOwner which controls the lifecycle transitions of the use
     *                       cases.
     * @param cameraSelector The camera selector which determines the camera to use for set of
     *                       use cases.
     * @param useCases       The use cases to bind to a lifecycle.
     *
     * @return The {@link Camera} instance which is determined by the camera selector and
     * internal requirements.
     *
     * @throws IllegalStateException    If the use case has already been bound to another lifecycle
     *                                  or method is not called on main thread.
     * @throws IllegalArgumentException If the provided camera selector is unable to resolve a
     *                                  camera to be used for the given use cases.
     */
    cameraProvider!!.bindToLifecycle(/* lifecycleOwner= */ this,
      cameraSelector!!,
      previewUseCase)
  }

  // * Analysis use case
  private fun bindAnalysisUseCase() {
    if (cameraProvider == null) {
      return
    }
    if (analysisUseCase != null) {
      cameraProvider!!.unbind(analysisUseCase)
    }
    if (imageProcessor != null) {
      imageProcessor!!.stop()
    }
    /**
     *  ? Notice that PreferenceUtils is used to get the Options of the the processor,
     *  PreferenceUtils: Utility class to retrieve shared preferences.
     */
    // ! ----------------------------------- imageProcessor ----------------------------------------
    imageProcessor =
      try {
        val poseDetectorOptions =
          PreferenceUtils.getPoseDetectorOptionsForLivePreview(this)
        val shouldshowDistance =
          PreferenceUtils.shouldShowPoseDetectionInFrameLikelihoodLivePreview(this)
        val visualizeZ = PreferenceUtils.shouldPoseDetectionVisualizeZ(this)
        val rescaleZ =
          PreferenceUtils.shouldPoseDetectionRescaleZForVisualization(this)
        val runClassification =
          PreferenceUtils.shouldPoseDetectionRunClassification(this)
//        Log.d(TAG + "Safe", PreferenceUtils.safeDistance(this).toString())
//        PreferenceUtils.setSafeDistance(this,"500")
//        Log.d(TAG + "Safe", PreferenceUtils.safeDistance(this).toString())
        val safeDistance = PreferenceUtils.safeDistance(this)
        val safeDistanceFloat:Float
        val safeDistanceUOM = PreferenceUtils.safeDistanceUOM(this)
        safeDistanceFloat = if (safeDistanceUOM == "Feat"){
          convertFeetToMeters(safeDistance.toFloat())
        }else{
          safeDistance.toFloat();
        }
        PoseDetectorProcessor(
          this,
          poseDetectorOptions,
          shouldshowDistance,
          visualizeZ,
          rescaleZ,
          runClassification,
          /* isStreamMode = */ true,
          safeDistanceFloat
        )
      } catch (e: Exception) {
        Log.e(TAG, "Can not create image processor: $selectedModel", e)
        Toast.makeText(
          applicationContext,
          "Can not create image processor: " + e.localizedMessage,
          Toast.LENGTH_LONG
        ).show()
        return
      }

    // ! ----------------------------- Use Case Builder -----------------------------
    val builder = ImageAnalysis.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    analysisUseCase = builder.build()

    needUpdateGraphicOverlayImageSourceInfo = true

    /**
    * @param Executor .
    * @param Analyzer of the image.
    */
    analysisUseCase?.setAnalyzer(
      // imageProcessor.processImageProxy will use another thread to run the detection underneath,
      // thus we can just runs the analyzer itself on main thread.
      /**
       * *  ContextCompat: Helper for accessing features in Context.
       * * getMainExecutor: Return an Executor that will run enqueued tasks on the main thread associated with this context.
       * This is the thread used to dispatch calls to application components (activities, services, etc).
       * */
      ContextCompat.getMainExecutor(this),
      ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
        /* ! -------------------  Rotation Handling ------------------------------------- */
        if (needUpdateGraphicOverlayImageSourceInfo) {
          val isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT
          val rotationDegrees = imageProxy.imageInfo.rotationDegrees
          if (rotationDegrees == 0 || rotationDegrees == 180) {
            graphicOverlay!!.setImageSourceInfo(imageProxy.width, imageProxy.height, isImageFlipped)
          } else {
            graphicOverlay!!.setImageSourceInfo(imageProxy.height, imageProxy.width, isImageFlipped)
          }
          needUpdateGraphicOverlayImageSourceInfo = false
        }
        /* ! -------------------  Rotation Handling Done ------------------------------- */

        try {
          // ! -------------------------- processImageProxy ---------------------
          imageProcessor!!.processImageProxy(imageProxy, graphicOverlay)

        } catch (e: MlKitException) {
          Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
          Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT).show()
        }
      }
    )
    cameraProvider!!.bindToLifecycle(/* lifecycleOwner= */ this, cameraSelector!!, analysisUseCase)
  }

  private fun convertFeetToMeters(feet: Float): Float {
    return (feet / 3.28084).toFloat()
  }

  companion object {
    private const val TAG = "CameraXLivePreview"
    private const val OBJECT_DETECTION = "Object Detection"
    private const val POSE_DETECTION = "Pose Detection"

    private const val STATE_SELECTED_MODEL = "selected_model"
  }
}
