package mg.maniry.doremii.omr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.R
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.commonUtils.Values
import mg.maniry.doremii.editor.EditorActivity

class ScanSolfaActivity : AppCompatActivity() {
    private lateinit var viewModel: ScanViewModel
    private lateinit var flipper: ViewFlipper
    private lateinit var preview: PreviewView
    private lateinit var stillView: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var reviewImage: ImageView
    private lateinit var signatureSpinner: Spinner
    private lateinit var keySpinner: Spinner
    private lateinit var voiceList: LinearLayout
    private lateinit var reviewMessage: TextView
    private lateinit var captureMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var readButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private val signatureValues = intArrayOf(2, 3, 4, 5, 6)
    private val editors = mutableListOf<Pair<String, EditText>>()
    private var imageCapture: ImageCapture? = null
    private var renderedGeneration = -1
    private var suppressSpinner = false
    private var renderingReview = false
    private var textDirty = false
    private var cameraAvailable = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showCaptureMessage(getString(R.string.scan_permission))
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadGallery(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileManager.initDir(this)
        setContentView(R.layout.scan_solfa_activity)
        viewModel = ViewModelProvider(this)[ScanViewModel::class.java]
        bindViews()
        bindActions()
        observe()
        cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!cameraAvailable) {
            findViewById<View>(R.id.scan_shutter).visibility = View.GONE
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.phase.value == ScanViewModel.Phase.REVIEW) {
                    viewModel.phase.value = if (viewModel.still != null) {
                        ScanViewModel.Phase.ADJUST
                    } else {
                        ScanViewModel.Phase.CAMERA
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun bindViews() {
        flipper = findViewById(R.id.scan_flipper)
        preview = findViewById(R.id.scan_preview)
        stillView = findViewById(R.id.scan_still)
        overlay = findViewById(R.id.scan_overlay)
        reviewImage = findViewById(R.id.scan_review_image)
        signatureSpinner = findViewById(R.id.scan_signature)
        keySpinner = findViewById(R.id.scan_key)
        voiceList = findViewById(R.id.scan_voices)
        reviewMessage = findViewById(R.id.scan_review_message)
        captureMessage = findViewById(R.id.scan_capture_message)
        progress = findViewById(R.id.scan_progress)
        readButton = findViewById(R.id.scan_read)
        confirmButton = findViewById(R.id.scan_confirm)
        signatureSpinner.adapter = spinnerAdapter(R.array.signature_list)
        keySpinner.adapter = spinnerAdapter(R.array.keys_list)
        signatureSpinner.setSelection(2)
    }

    private fun bindActions() {
        findViewById<View>(R.id.scan_shutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.scan_gallery).setOnClickListener {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        readButton.setOnClickListener { readCrop() }
        findViewById<View>(R.id.scan_retake).setOnClickListener {
            textDirty = false
            renderedGeneration = -1
            viewModel.retake()
            stillView.setImageBitmap(null)
        }
        findViewById<View>(R.id.scan_cancel).setOnClickListener {
            viewModel.retake()
            finish()
        }
        confirmButton.setOnClickListener { confirm() }
        signatureSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSpinner || flipper.displayedChild != 1 || textDirty) return
                viewModel.reparse(signatureValues[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun observe() {
        viewModel.phase.observe(this) { phase ->
            val reviewing = phase == ScanViewModel.Phase.REVIEW
            val adjusting = phase == ScanViewModel.Phase.ADJUST
            flipper.displayedChild = if (reviewing) 1 else 0
            stillView.visibility = if (adjusting) View.VISIBLE else View.GONE
            preview.visibility = if (adjusting) View.INVISIBLE else View.VISIBLE
            readButton.visibility = if (adjusting) View.VISIBLE else View.GONE
            findViewById<View>(R.id.scan_retake).visibility = if (adjusting) View.VISIBLE else View.GONE
            findViewById<View>(R.id.scan_shutter).visibility =
                if (phase == ScanViewModel.Phase.CAMERA && cameraAvailable) View.VISIBLE else View.GONE
            if (adjusting) stillView.setImageBitmap(viewModel.still)
            if (reviewing) reviewImage.setImageBitmap(viewModel.crop)
        }
        viewModel.busy.observe(this) { busy ->
            progress.visibility = if (busy == true) View.VISIBLE else View.GONE
            readButton.isEnabled = busy != true
            confirmButton.isEnabled = busy != true
        }
        viewModel.captureMessage.observe(this) { showCaptureMessage(it) }
        viewModel.review.observe(this) { state ->
            if (state != null && state.generation != renderedGeneration) renderReview(state)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    imageCapture,
                )
            } catch (error: Exception) {
                showCaptureMessage(getString(R.string.scan_failed))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        preview.display?.let { capture.targetRotation = it.rotation }
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val raw = bitmapFromCapture(image)
                        showStill(raw)
                    } catch (error: Exception) {
                        showCaptureMessage(getString(R.string.scan_failed))
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    showCaptureMessage(getString(R.string.scan_failed))
                }
            },
        )
    }

    private fun loadGallery(uri: Uri) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeGallery(uri) }
            progress.visibility = View.GONE
            if (bitmap == null) showCaptureMessage(getString(R.string.scan_failed))
            else showStill(bitmap)
        }
    }

    private fun showStill(bitmap: Bitmap) {
        val scaled = ImagePreprocessor.scaleDown(ImagePreprocessor.software(bitmap))
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        viewModel.setStill(scaled)
    }

    private fun readCrop() {
        val source = viewModel.still ?: return
        val cropped = ImagePreprocessor.cropDisplayed(source, overlay.width, overlay.height, overlay.crop)
        if (cropped == null) {
            showCaptureMessage(getString(R.string.scan_crop_small))
            return
        }
        viewModel.recognize(cropped, signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { 4 })
    }

    private fun renderReview(state: ReviewState) {
        renderedGeneration = state.generation
        renderingReview = true
        suppressSpinner = true
        val signatureIndex = signatureValues.indexOf(state.signature).coerceAtLeast(0)
        signatureSpinner.setSelection(signatureIndex)
        keySpinner.setSelection(state.keyIndex.coerceIn(0, keySpinner.adapter.count - 1))
        suppressSpinner = false
        textDirty = false
        voiceList.removeAllViews()
        editors.clear()
        val color = ContextCompat.getColor(this, R.color.colorAccent)
        for (voice in state.voices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
            }
            val label = TextView(this).apply {
                text = voice.id
                minWidth = (36 * resources.displayMetrics.density).toInt()
                textSize = 18f
            }
            val editor = EditText(this).apply {
                setText(styled(voice.text, voice.inferred, color))
                isSingleLine = true
                typeface = Typeface.MONOSPACE
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                doAfterTextChanged { if (!renderingReview) textDirty = true }
            }
            row.addView(label)
            row.addView(editor)
            voiceList.addView(row)
            editors.add(voice.id to editor)
        }
        reviewMessage.text = when {
            state.empty -> getString(R.string.scan_empty)
            state.voices.any { it.inferred.isNotEmpty() } -> getString(R.string.scan_inferred)
            else -> ""
        }
        renderingReview = false
        textDirty = false
    }

    private fun confirm() {
        val signature = signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { 4 }
        val key = keySpinner.selectedItemPosition.coerceAtLeast(0)
        val voices = if (textDirty) {
            editors.map { (id, editor) ->
                ParsedVoice(id, PrintedSolfaFormatter.parse(editor.text.toString(), signature), emptyList())
            }
        } else {
            viewModel.scoreVoices(signature, null)
        }
        if (voices.isEmpty() || voices.all { voice -> voice.cells.all { it.isBlank() } }) {
            reviewMessage.setText(R.string.scan_empty)
            return
        }
        val title = "scan_${System.currentTimeMillis()}"
        val data = ScanScoreBuilder.build(voices, signature, key, title)
        if (FileManager.write(title, data.toJson()) != Values.saved) {
            reviewMessage.setText(R.string.scan_failed)
            return
        }
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra("fileToOpen", title)
        })
        finish()
    }

    private fun styled(text: String, inferred: List<IntRange>, color: Int): CharSequence {
        if (inferred.isEmpty()) return text
        val span = SpannableString(text)
        for (range in inferred) {
            val end = (range.last + 1).coerceAtMost(text.length)
            if (range.first in 0 until end) {
                span.setSpan(
                    ForegroundColorSpan(color),
                    range.first,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return span
    }

    private fun showCaptureMessage(text: String?) {
        captureMessage.text = text
        captureMessage.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun spinnerAdapter(array: Int): ArrayAdapter<CharSequence> {
        return ArrayAdapter.createFromResource(this, array, android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun bitmapFromCapture(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = if (image.format == ImageFormat.JPEG || image.planes.size == 1) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            null
        } ?: throw IllegalStateException("empty capture")
        val turned = ImagePreprocessor.rotate(decoded, image.imageInfo.rotationDegrees)
        if (turned !== decoded) decoded.recycle()
        return ImagePreprocessor.software(turned)
    }

    private fun decodeGallery(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 4800 || bounds.outHeight / sample > 4800) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val turned = if (degrees == 0) decoded else ImagePreprocessor.rotate(decoded, degrees).also {
            if (it !== decoded) decoded.recycle()
        }
        return ImagePreprocessor.software(turned)
    }
}
