package com.supportgenie.tflitetroubleshooter.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import com.supportgenie.tflitetroubleshooter.R
import com.supportgenie.tflitetroubleshooter.databinding.FragmentCameraBinding

import java.util.ArrayList

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


    // Vars For automated assistance
    private var step = 0
    private var detectedTimes = 0
    private var detectionClasses: ArrayList<String> = ArrayList<String>();



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
            stepOne()
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // When clicked, lower detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (objectDetectorHelper.threshold >= 0.1) {
                objectDetectorHelper.threshold -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (objectDetectorHelper.threshold <= 0.8) {
                objectDetectorHelper.threshold += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (objectDetectorHelper.maxResults > 1) {
                objectDetectorHelper.maxResults--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (objectDetectorHelper.maxResults < 5) {
                objectDetectorHelper.maxResults++
                updateControlsUi()
            }
        }

        // When clicked, decrease the number of threads used for detection
        fragmentCameraBinding.bottomSheetLayout.threadsMinus.setOnClickListener {
            if (objectDetectorHelper.numThreads > 1) {
                objectDetectorHelper.numThreads--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of threads used for detection
        fragmentCameraBinding.bottomSheetLayout.threadsPlus.setOnClickListener {
            if (objectDetectorHelper.numThreads < 4) {
                objectDetectorHelper.numThreads++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(0, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    objectDetectorHelper.currentDelegate = p2
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // When clicked, change the underlying model used for object detection
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(0, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    objectDetectorHelper.currentModel = p2
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
        fragmentCameraBinding.buttonNext.setOnClickListener {
            // TODO: Next button functionality.
        }
    }

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            objectDetectorHelper.maxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", objectDetectorHelper.threshold)
        fragmentCameraBinding.bottomSheetLayout.threadsValue.text =
            objectDetectorHelper.numThreads.toString()

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        objectDetectorHelper.clearObjectDetector()
        fragmentCameraBinding.overlay.clear()
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
//    override fun onResults(
//      results: MutableList<Detection>?,
//      inferenceTime: Long,
//      imageHeight: Int,
//      imageWidth: Int
//    ) {
//        activity?.runOnUiThread {
//            fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
//                            String.format("%d ms", inferenceTime)
//
//            // Pass necessary information to OverlayView for drawing on the canvas
//            fragmentCameraBinding.overlay.setResults(
//                results ?: LinkedList<Detection>(),
//                imageHeight,
//                imageWidth
//            )
//
//            // Force a redraw
//            fragmentCameraBinding.overlay.invalidate()
//        }
//    }

    override fun onResults(
        results: List<Map<String, Any>>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ){
        activity?.runOnUiThread {
            fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                String.format("%d ms", inferenceTime)
            // Pass necessary information to OverlayView for drawing on the canvas
            if (results != null) {
                var showResults: ArrayList<Map<String, Any>> = ArrayList<Map<String, Any>>();
                if (detectionClasses.isEmpty())
                    showResults.addAll(results)
                else
                    for (result in results){
                        if (detectionClasses.contains(result["tag"]))
                            showResults.add(result)
                    }
                fragmentCameraBinding.overlay.setResults(
                    showResults,
                    imageHeight,
                    imageWidth
                )
                if (step == 1)
                    confirmStepOne(results)
                else if (step == 2)
                    confirmStepTwo(results)
                else if (step == 3)
                    confirmStepThree(results)
                else if (step == 4)
                    confirmStepFour(results)
                else if (step == 5)
                    confirmStepFive(results)
            }
            // Force a redraw
            fragmentCameraBinding.overlay.invalidate()
        }
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }


    // Functions for automated assistance

    private fun clearData(){
        fragmentCameraBinding.stepTextView.text = ""
        detectionClasses = ArrayList<String>();
    }

    private fun stepOne(){
//        detectionClasses.add("ethernet-Notcon")
        detectionClasses.add("WAN-NotCon")
//        detectionClasses.add("WAN-Con")
        detectionClasses.add("power-Notcon")
//        detectionClasses.add("power-con")
        // set stepTextView text
        fragmentCameraBinding.stepTextView.text = "Show the rear panel of the device."
        step = 1
    }

    private fun stepTwo(){
        // TODO: Add ethernet cable class
        fragmentCameraBinding.stepTextView.text = "Find the ethernet cable."
        detectionClasses.add("ethernet-cable")
        step = 2;
    }

    private fun stepThree(){
        fragmentCameraBinding.stepTextView.text = "Plug ethernet cable into the WAN port."
        detectionClasses.add("WAN-NotCon")
        detectionClasses.add("WAN-Con")
        step = 3;
    }

    private fun stepFour(){
        fragmentCameraBinding.stepTextView.text = "Find the power cable."
        detectionClasses.add("power-cable")
        step = 4;
    }

    private fun stepFive(){
        fragmentCameraBinding.stepTextView.text = "Plug power cable into the power port."
        detectionClasses.add("power-Notcon")
        detectionClasses.add("power-con")
        step = 5;
    }

    private fun confirmStepOne(results: List<Map<String, Any>>?){
        var detectedClasses: ArrayList<String> = ArrayList<String>();
        for (result in results!!){
            detectedClasses.add(result["tag"] as String)
        }
        if ((detectedClasses.contains(detectionClasses[0]) || detectedClasses.contains(detectionClasses[1]))){
//            && (detectedClasses.contains(detectionClasses[2]) || detectedClasses.contains(detectionClasses[3]))){
            detectedTimes++
            if (detectedTimes<20)
                return
            Toast.makeText(requireContext(), "Step 1 completed", Toast.LENGTH_SHORT).show()
            clearData();
            stepTwo()
        } else {
            detectedTimes = 0
        }
    }

    private fun confirmStepTwo(results: List<Map<String, Any>>?){
        var detectedClasses: ArrayList<String> = ArrayList<String>();
        for (result in results!!){
            detectedClasses.add(result["tag"] as String)
        }
        if ((detectedClasses.containsAll(detectionClasses))){
            detectedTimes++
            if (detectedTimes<20)
                return
            Toast.makeText(requireContext(), "Step 2 completed", Toast.LENGTH_SHORT).show()
            clearData();
            stepThree()
        } else {
            detectedTimes = 0
        }
    }

    private fun confirmStepThree(results: List<Map<String, Any>>?){
        var detectedClasses: ArrayList<String> = ArrayList<String>();
        for (result in results!!){
            detectedClasses.add(result["tag"] as String)
        }
        if ((detectedClasses.contains("WAN-Con"))){
            detectedTimes++
            if (detectedTimes<20)
                return
            Toast.makeText(requireContext(), "Step 3 completed", Toast.LENGTH_SHORT).show()
            clearData();
            stepFour()
        } else {
            detectedTimes = 0
        }
    }

    private fun confirmStepFour(results: List<Map<String, Any>>?){
        var detectedClasses: ArrayList<String> = ArrayList<String>();
        for (result in results!!){
            detectedClasses.add(result["tag"] as String)
        }
        if ((detectedClasses.containsAll(detectionClasses))){
            detectedTimes++
            if (detectedTimes<20)
                return
            Toast.makeText(requireContext(), "Step 2 completed", Toast.LENGTH_SHORT).show()
            clearData();
            stepFive()
        } else {
            detectedTimes = 0
        }
    }

    private fun confirmStepFive(results: List<Map<String, Any>>?){
        var detectedClasses: ArrayList<String> = ArrayList<String>();
        for (result in results!!){
            detectedClasses.add(result["tag"] as String)
        }
        if ((detectedClasses.contains("power-con"))){
            detectedTimes++
            if (detectedTimes<20)
                return
            Toast.makeText(requireContext(), "Step 5 completed", Toast.LENGTH_SHORT).show()
            clearData();
//            stepFour()
        } else {
            detectedTimes = 0
        }
    }
}
