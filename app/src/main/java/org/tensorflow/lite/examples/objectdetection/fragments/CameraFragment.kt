/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tensorflow.lite.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper
import org.tensorflow.lite.examples.objectdetection.R
import org.tensorflow.lite.examples.objectdetection.databinding.FragmentCameraBinding
import org.tensorflow.lite.task.vision.detector.Detection

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private var sharedPreferences: SharedPreferences ? = null

    var mediaPlayer:MediaPlayer?=null

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("org.tensorflow.lite",
            Context.MODE_PRIVATE)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        objectDetectorHelper = ObjectDetectorHelper(
            context = requireContext(),
            objectDetectorListener = this)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

    }



    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        if (!::bitmapBuffer.isInitialized) {
                            // The image rotation and RGB image buffer are initialized only once
                            // the analyzer has started running
                            bitmapBuffer = Bitmap.createBitmap(
                              image.width,
                              image.height,
                              Bitmap.Config.ARGB_8888
                            )
                        }

                        detectObjects(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectObjects(image: ImageProxy) {
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        val imageRotation = image.imageInfo.rotationDegrees
        // Pass Bitmap and rotation to the object detector helper for processing and detection
        objectDetectorHelper.detect(bitmapBuffer, imageRotation)

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onResults(
      results: MutableList<Detection>?,
      inferenceTime: Long,
      imageHeight: Int,
      imageWidth: Int
    ) {
        activity?.runOnUiThread {

            fragmentCameraBinding.overlay.setResults(
                results ?: LinkedList<Detection>(),
                imageHeight,
                imageWidth
            )

            if(results!=null&&results.size>1){
                val firstItem = results.get(0)
                val secondItem = results.get(1)

                    val intersecting = areRectanglesIntersecting(
                        firstItem.boundingBox,  // Birinci objenin koordinatları
                        secondItem.boundingBox   // İkinci objenin koordinatları
                    )

                    var strCountry = ""
                    var strFinger = ""

                    if (intersecting) {
                        if(!((sharedPreferences?.getString("firstItem","")!!.equals(firstItem.categories.get(0).label.toString())||sharedPreferences?.getString("firstItem","").equals(secondItem.categories.get(0).label.toString())&&
                                    (sharedPreferences?.getString("secondItem","")!!.equals(firstItem.categories.get(0).label.toString())||sharedPreferences?.getString("secondItem","").equals(secondItem.categories.get(0).label.toString()))))){
                            if(firstItem.categories.get(0).label.equals("finger")){
                                strFinger += "finger"
                                strCountry += secondItem.categories.get(0).label.toString()

                            }else if(secondItem.categories.get(0).label.equals("finger")){
                                strFinger += "finger"
                                strCountry += firstItem.categories.get(0).label.toString()
                            }else if(firstItem.categories.get(0).label.equals("two_fingers")){
                                strFinger += "two_fingers"
                                strCountry += secondItem.categories.get(0).label.toString()
                            }else if(secondItem.categories.get(0).label.equals("two_fingers")){
                                strFinger += "two_fingers"
                                strCountry += firstItem.categories.get(0).label.toString()
                            }
                            sharedPreferences!!.edit().remove("firstItem").apply()
                            sharedPreferences!!.edit().remove("secondItem").apply()

                            sharedPreferences!!.edit().putString("firstItem",strFinger).apply()
                            sharedPreferences!!.edit().putString("secondItem",strCountry).apply()


                            Toast.makeText(requireContext(),"1",Toast.LENGTH_SHORT).show()
                            GiveInformation(strFinger,strCountry)

                        }
                        Toast.makeText(requireContext(), "Objeler çakışıyor!", Toast.LENGTH_SHORT).show()
                }
            }

            // Force a redraw
            fragmentCameraBinding.overlay.invalidate()
        }
    }

    private fun GiveInformation(strFinger: String, strCountry: String) {

        var temp = 0

        if(strFinger.equals("two_fingers")){
            when(strCountry){
                "america"->{
                    temp = R.raw.unitedstatesinfo
                }
                "canada" ->{
                    temp = R.raw.canadainfo
                }
                "mexico" ->{
                    temp = R.raw.mexicoinfo
                }
                "alasca" -> {
                    temp = R.raw.alascainfo
                }


            }

        }else if(strFinger.equals("finger")){
            when(strCountry){
                "america"->{
                    temp = R.raw.america
                }
                "canada" ->{
                    temp = R.raw.canada
                }
                "mexico" ->{
                    temp = R.raw.mexico
                }
                "alasca" -> {
                    temp = R.raw.alasca
                }


            }

        }
        try {
            mediaPlayer = MediaPlayer.create(requireContext(), temp)
            Toast.makeText(requireContext(),strCountry,Toast.LENGTH_SHORT).show()
            mediaPlayer = MediaPlayer.create(requireContext(), temp)
            mediaPlayer!!.start()
        }catch (e:Exception){

        }

    }

    fun areRectanglesIntersecting(rect1: RectF, rect2: RectF): Boolean {
        return !(rect1.right < rect2.left ||
                rect1.left > rect2.right ||
                rect1.bottom < rect2.top ||
                rect1.top > rect2.bottom)
    }
    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaPlayer!!.stop()
        sharedPreferences?.edit()!!.remove("firstItem").apply()
        sharedPreferences?.edit()!!.remove("secondItem").apply()
    }
}
