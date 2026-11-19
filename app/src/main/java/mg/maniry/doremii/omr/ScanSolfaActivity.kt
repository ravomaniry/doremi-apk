package mg.maniry.doremii.omr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
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
    private lateinit var player: ScanPreviewPlayer
    private lateinit var flipper: ViewFlipper
    private lateinit var preview: PreviewView
    private lateinit var stillView: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var pageStripScroll: View
    private lateinit var pageStrip: LinearLayout
    private lateinit var score: FlexboxLayout
    private lateinit var signatureSpinner: Spinner
    private lateinit var keySpinner: Spinner
    private lateinit var tempoField: EditText
    private lateinit var titleField: EditText
    private lateinit var singerField: EditText
    private lateinit var authorField: EditText
    private lateinit var compositorField: EditText
    private lateinit var reviewMessage: TextView
    private lateinit var captureMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var readButton: MaterialButton
    private lateinit var addPageButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private lateinit var playButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private val signatureValues = intArrayOf(2, 3, 4, 5, 6)
    private var imageCapture: ImageCapture? = null
    private var renderedGeneration = -1
    private var suppressSpinner = false
    private var cameraAvailable = false
    private var playing = false
    private var shownPhase: ScanViewModel.Phase? = null

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
        player = ScanPreviewPlayer(this) { active ->
            playing = active
            refreshActions()
        }
        bindActions()
        observe()
        cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        showPhase(viewModel.phase.value)
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
                when (viewModel.phase.value) {
                    ScanViewModel.Phase.PREVIEW -> viewModel.phase.value = ScanViewModel.Phase.CAMERA
                    ScanViewModel.Phase.ADJUST -> viewModel.discardStill()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onPause() {
        player.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun bindViews() {
        flipper = findViewById(R.id.scan_flipper)
        preview = findViewById(R.id.scan_preview)
        stillView = findViewById(R.id.scan_still)
        overlay = findViewById(R.id.scan_overlay)
        pageStripScroll = findViewById(R.id.scan_page_strip_scroll)
        pageStrip = findViewById(R.id.scan_page_strip)
        score = findViewById(R.id.scan_score)
        signatureSpinner = findViewById(R.id.scan_signature)
        keySpinner = findViewById(R.id.scan_key)
        tempoField = findViewById(R.id.scan_tempo)
        titleField = findViewById(R.id.scan_title)
        singerField = findViewById(R.id.scan_singer)
        authorField = findViewById(R.id.scan_author)
        compositorField = findViewById(R.id.scan_compositor)
        reviewMessage = findViewById(R.id.scan_review_message)
        captureMessage = findViewById(R.id.scan_capture_message)
        progress = findViewById(R.id.scan_progress)
        readButton = findViewById(R.id.scan_read)
        addPageButton = findViewById(R.id.scan_add_page)
        confirmButton = findViewById(R.id.scan_confirm)
        playButton = findViewById(R.id.scan_play)
        stopButton = findViewById(R.id.scan_stop)
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
        addPageButton.setOnClickListener { addPage() }
        findViewById<View>(R.id.scan_retake).setOnClickListener { viewModel.discardStill() }
        findViewById<View>(R.id.scan_cancel).setOnClickListener {
            player.stop()
            finish()
        }
        confirmButton.setOnClickListener { confirm() }
        playButton.setOnClickListener { playPreview() }
        stopButton.setOnClickListener { player.stop() }
        signatureSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSpinner || flipper.displayedChild != 1) return
                val before = viewModel.review.value?.generation
                viewModel.reparse(signatureValues[position])
                if (viewModel.review.value?.generation == before) renderScore()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        keySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSpinner || flipper.displayedChild != 1) return
                renderScore()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun observe() {
        viewModel.phase.observe(this) { phase ->
            if (shownPhase == ScanViewModel.Phase.PREVIEW && phase != ScanViewModel.Phase.PREVIEW) {
                player.stop()
            }
            shownPhase = phase
            showPhase(phase)
        }
        viewModel.pages.observe(this) { renderPages(it.orEmpty()) }
        viewModel.busy.observe(this) { refreshActions() }
        viewModel.progress.observe(this) { value ->
            if (viewModel.busy.value != true) return@observe
            progress.isIndeterminate = false
            progress.progress = value ?: 0
        }
        viewModel.captureMessage.observe(this) { showCaptureMessage(it) }
        viewModel.review.observe(this) { state ->
            if (state != null && state.generation != renderedGeneration) renderReview(state)
        }
    }

    private fun showPhase(phase: ScanViewModel.Phase?) {
        val adjusting = phase == ScanViewModel.Phase.ADJUST
        val camera = phase == ScanViewModel.Phase.CAMERA
        val queued = viewModel.pages.value.orEmpty().isNotEmpty()
        flipper.displayedChild = if (phase == ScanViewModel.Phase.PREVIEW) 1 else 0
        if (adjusting) {
            stillView.setImageBitmap(viewModel.still)
            stillView.visibility = View.VISIBLE
        } else {
            stillView.setImageBitmap(null)
            stillView.visibility = View.GONE
        }
        preview.visibility = if (adjusting) View.INVISIBLE else View.VISIBLE
        overlay.visibility = if (adjusting) View.VISIBLE else View.GONE
        addPageButton.visibility = if (adjusting) View.VISIBLE else View.GONE
        readButton.visibility = if (adjusting || (camera && queued)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scan_retake).visibility = if (adjusting) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scan_shutter).visibility =
            if (camera && cameraAvailable) View.VISIBLE else View.GONE
        refreshActions()
    }

    private fun renderPages(items: List<ScanPage>) {
        pageStrip.removeAllViews()
        val working = viewModel.busy.value == true
        items.forEachIndexed { index, page ->
            val item = layoutInflater.inflate(R.layout.scan_page_thumb, pageStrip, false)
            item.findViewById<ImageView>(R.id.scan_page_thumb).setImageBitmap(page.thumb)
            item.findViewById<View>(R.id.scan_page_remove).apply {
                isEnabled = !working
                setOnClickListener { viewModel.removePage(index) }
            }
            pageStrip.addView(item)
        }
        pageStripScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (::flipper.isInitialized) showPhase(viewModel.phase.value)
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
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeGallery(uri) }
            progress.isIndeterminate = false
            progress.visibility = View.GONE
            if (bitmap == null) showCaptureMessage(getString(R.string.scan_failed))
            else showStill(bitmap)
        }
    }

    private fun showStill(bitmap: Bitmap) {
        val scaled = ImagePreprocessor.scaleDown(ImagePreprocessor.software(bitmap))
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        viewModel.setStill(scaled)
        stillView.setImageBitmap(scaled)
    }

    private fun addPage() {
        val cropped = croppedStill() ?: return
        viewModel.addPage(cropped)
    }

    private fun readCrop() {
        if (viewModel.phase.value == ScanViewModel.Phase.ADJUST) {
            val cropped = croppedStill() ?: return
            viewModel.recognize(cropped)
        } else {
            viewModel.recognize(null)
        }
    }

    private fun croppedStill(): Bitmap? {
        val source = viewModel.still ?: return null
        val cropped = ImagePreprocessor.cropDisplayed(source, overlay.width, overlay.height, overlay.crop)
        if (cropped == null) showCaptureMessage(getString(R.string.scan_crop_small))
        return cropped
    }

    private fun renderReview(state: ReviewState) {
        renderedGeneration = state.generation
        if (state.detected) {
            suppressSpinner = true
            signatureSpinner.setSelection(signatureValues.indexOf(state.signature).coerceAtLeast(0))
            keySpinner.setSelection(state.keyIndex.coerceIn(0, keySpinner.adapter.count - 1))
            suppressSpinner = false
        }
        reviewMessage.text = when {
            state.empty -> getString(R.string.scan_empty)
            state.voices.any { it.inferred.isNotEmpty() } -> getString(R.string.scan_inferred)
            else -> ""
        }
        renderScore()
        refreshActions()
    }

    private fun renderScore() {
        val state = viewModel.review.value ?: return
        val signature = signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { state.signature }
        val key = keySpinner.selectedItemPosition.coerceAtLeast(0)
        ScanScorePreview.render(score, viewModel.scoreVoices(signature, null), signature, key)
    }

    private fun playPreview() {
        val voices = audibleVoices() ?: return
        val tempo = tempoOrWarn() ?: return
        player.play(
            voices,
            signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { 4 },
            keySpinner.selectedItemPosition.coerceAtLeast(0),
            tempo,
        )
    }

    private fun confirm() {
        player.stop()
        val voices = audibleVoices() ?: return
        val tempo = tempoOrWarn() ?: return
        val signature = signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { 4 }
        val key = keySpinner.selectedItemPosition.coerceAtLeast(0)
        val title = titleField.text?.toString() ?: ""
        val singer = singerField.text?.toString() ?: ""
        val filename = ScanScoreBuilder.filename(title, singer, "scan_${System.currentTimeMillis()}")
        val data = ScanScoreBuilder.build(
            voices = voices,
            signature = signature,
            keyIndex = key,
            title = title,
            singer = singer,
            author = authorField.text?.toString() ?: "",
            compositor = compositorField.text?.toString() ?: "",
            tempo = tempo,
        )
        if (FileManager.write(filename, data.toJson()) != Values.saved) {
            reviewMessage.setText(R.string.scan_failed)
            return
        }
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra("fileToOpen", filename)
        })
        finish()
    }

    private fun audibleVoices(): List<ParsedVoice>? {
        val signature = signatureValues.getOrElse(signatureSpinner.selectedItemPosition) { 4 }
        val voices = viewModel.scoreVoices(signature, null)
        if (voices.isEmpty() || voices.all { voice -> voice.cells.all { it.isBlank() } }) {
            reviewMessage.setText(R.string.scan_empty)
            return null
        }
        return voices
    }

    private fun tempoOrWarn(): Int? {
        val tempo = tempoField.text?.toString()?.trim()?.toIntOrNull()
        if (tempo == null || tempo !in 31..399) {
            reviewMessage.setText(R.string.scan_tempo_invalid)
            return null
        }
        return tempo
    }

    private fun refreshActions() {
        val working = viewModel.busy.value == true
        val ready = viewModel.review.value?.empty == false
        progress.visibility = if (working) View.VISIBLE else View.GONE
        if (working) {
            progress.isIndeterminate = false
            progress.progress = viewModel.progress.value ?: 0
        }
        readButton.isEnabled = !working
        addPageButton.isEnabled = !working
        findViewById<View>(R.id.scan_shutter).isEnabled = !working
        findViewById<View>(R.id.scan_gallery).isEnabled = !working
        confirmButton.isEnabled = !working && ready
        playButton.isEnabled = !working && ready && !playing
        stopButton.visibility = if (playing) View.VISIBLE else View.GONE
        for (index in 0 until pageStrip.childCount) {
            pageStrip.getChildAt(index).findViewById<View>(R.id.scan_page_remove).isEnabled = !working
        }
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
