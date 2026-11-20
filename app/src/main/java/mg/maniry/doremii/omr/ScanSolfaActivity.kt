package mg.maniry.doremii.omr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
    private lateinit var stillView: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var pageGrid: FlexboxLayout
    private lateinit var score: FlexboxLayout
    private lateinit var signatureField: MaterialAutoCompleteTextView
    private lateinit var keyField: MaterialAutoCompleteTextView
    private lateinit var tempoField: EditText
    private lateinit var titleField: EditText
    private lateinit var singerField: EditText
    private lateinit var authorField: EditText
    private lateinit var compositorField: EditText
    private lateinit var reviewMessage: TextView
    private lateinit var captureMessage: TextView
    private lateinit var pagesMessage: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var readButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private lateinit var playButton: ImageButton
    private val signatureValues = intArrayOf(2, 3, 4, 5, 6)
    private var renderedGeneration = -1
    private var suppressDropdown = false
    private var playing = false
    private var loadingImage = false
    private var shownPhase: ScanViewModel.Phase? = null

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loadGallery(uri)
        } else if (viewModel.pages.value.isNullOrEmpty() && viewModel.still == null) {
            finish()
        }
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
        showPhase(viewModel.phase.value)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (viewModel.phase.value) {
                    ScanViewModel.Phase.PREVIEW -> viewModel.phase.value = ScanViewModel.Phase.PAGES
                    ScanViewModel.Phase.ADJUST -> discardCrop()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        if (savedInstanceState == null &&
            viewModel.phase.value == ScanViewModel.Phase.PAGES &&
            viewModel.pages.value.isNullOrEmpty()
        ) {
            openPicker()
        }
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
        stillView = findViewById(R.id.scan_still)
        overlay = findViewById(R.id.scan_overlay)
        pageGrid = findViewById(R.id.scan_page_grid)
        score = findViewById(R.id.scan_score)
        signatureField = findViewById(R.id.scan_signature)
        keyField = findViewById(R.id.scan_key)
        tempoField = findViewById(R.id.scan_tempo)
        titleField = findViewById(R.id.scan_title)
        singerField = findViewById(R.id.scan_singer)
        authorField = findViewById(R.id.scan_author)
        compositorField = findViewById(R.id.scan_compositor)
        reviewMessage = findViewById(R.id.scan_review_message)
        captureMessage = findViewById(R.id.scan_capture_message)
        pagesMessage = findViewById(R.id.scan_pages_message)
        progress = findViewById(R.id.scan_progress)
        readButton = findViewById(R.id.scan_read)
        confirmButton = findViewById(R.id.scan_confirm)
        playButton = findViewById(R.id.scan_play)
        bindDropdown(signatureField, R.array.signature_list, 2) { position ->
            val before = viewModel.review.value?.generation
            viewModel.reparse(signatureValues.getOrElse(position) { 4 })
            if (viewModel.review.value?.generation == before) renderScore()
        }
        bindDropdown(keyField, R.array.keys_list, 0) { renderScore() }
    }

    private fun bindActions() {
        findViewById<View>(R.id.scan_crop_cancel).setOnClickListener { discardCrop() }
        findViewById<View>(R.id.scan_accept).setOnClickListener { acceptCrop() }
        findViewById<View>(R.id.scan_pages_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.scan_cancel).setOnClickListener {
            player.stop()
            finish()
        }
        readButton.setOnClickListener { viewModel.recognize(null) }
        confirmButton.setOnClickListener { confirm() }
        playButton.setOnClickListener {
            if (playing) player.stop() else playPreview()
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
            progress.setProgressCompat(value ?: 0, true)
        }
        viewModel.captureMessage.observe(this) { showCaptureMessage(it) }
        viewModel.review.observe(this) { state ->
            if (state != null && state.generation != renderedGeneration) renderReview(state)
        }
    }

    private fun showPhase(phase: ScanViewModel.Phase?) {
        flipper.displayedChild = when (phase) {
            ScanViewModel.Phase.ADJUST -> PAGE_CROP
            ScanViewModel.Phase.PREVIEW -> PAGE_REVIEW
            else -> PAGE_GRID
        }
        if (phase == ScanViewModel.Phase.ADJUST) {
            stillView.setImageBitmap(viewModel.still)
        } else {
            stillView.setImageBitmap(null)
        }
        showCaptureMessage(viewModel.captureMessage.value)
        refreshActions()
    }

    private fun renderPages(items: List<ScanPage>) {
        pageGrid.removeAllViews()
        val locked = viewModel.busy.value == true || loadingImage
        items.forEachIndexed { index, page ->
            val item = layoutInflater.inflate(R.layout.scan_page_thumb, pageGrid, false)
            item.findViewById<ImageView>(R.id.scan_page_thumb).setImageBitmap(page.thumb)
            item.findViewById<View>(R.id.scan_page_remove).apply {
                isEnabled = !locked
                setOnClickListener {
                    if (viewModel.busy.value == true || loadingImage) return@setOnClickListener
                    viewModel.removePage(index)
                }
            }
            pageGrid.addView(item)
        }
        val add = layoutInflater.inflate(R.layout.scan_page_add, pageGrid, false)
        add.setOnClickListener {
            if (viewModel.busy.value == true || loadingImage) return@setOnClickListener
            openPicker()
        }
        pageGrid.addView(add)
        refreshActions()
    }

    private fun openPicker() {
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun loadGallery(uri: Uri) {
        loadingImage = true
        viewModel.captureMessage.value = null
        refreshActions()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeGallery(uri) }
            loadingImage = false
            if (bitmap == null) {
                viewModel.captureMessage.value = getString(R.string.scan_failed)
                refreshActions()
            } else {
                showStill(bitmap)
            }
        }
    }

    private fun showStill(bitmap: Bitmap) {
        val scaled = ImagePreprocessor.scaleDown(ImagePreprocessor.software(bitmap))
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        overlay.resetCrop()
        viewModel.setStill(scaled)
    }

    private fun acceptCrop() {
        val cropped = croppedStill() ?: return
        viewModel.addPage(cropped)
    }

    private fun discardCrop() {
        val reopen = viewModel.pages.value.isNullOrEmpty()
        viewModel.captureMessage.value = null
        viewModel.discardStill()
        if (reopen) openPicker()
    }

    private fun croppedStill(): Bitmap? {
        val source = viewModel.still ?: return null
        val cropped = ImagePreprocessor.cropDisplayed(source, overlay.width, overlay.height, overlay.crop)
        if (cropped == null) viewModel.captureMessage.value = getString(R.string.scan_crop_small)
        return cropped
    }

    private fun renderReview(state: ReviewState) {
        renderedGeneration = state.generation
        if (state.detected) {
            suppressDropdown = true
            selectDropdown(signatureField, signatureValues.indexOf(state.signature).coerceAtLeast(0))
            selectDropdown(keyField, state.keyIndex.coerceIn(0, keyField.adapter.count - 1))
            suppressDropdown = false
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
        val signature = signatureValues.getOrElse(selectedIndex(signatureField)) { state.signature }
        val key = selectedIndex(keyField)
        ScanScorePreview.render(score, viewModel.scoreVoices(signature, null), signature, key)
    }

    private fun playPreview() {
        val voices = audibleVoices() ?: return
        val tempo = tempoOrWarn() ?: return
        player.play(
            voices,
            signatureValues.getOrElse(selectedIndex(signatureField)) { 4 },
            selectedIndex(keyField),
            tempo,
        )
    }

    private fun confirm() {
        player.stop()
        val voices = audibleVoices() ?: return
        val tempo = tempoOrWarn() ?: return
        val signature = signatureValues.getOrElse(selectedIndex(signatureField)) { 4 }
        val key = selectedIndex(keyField)
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
        val signature = signatureValues.getOrElse(selectedIndex(signatureField)) { 4 }
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
        val loading = loadingImage
        val ready = viewModel.review.value?.empty == false
        val hasPages = viewModel.pages.value.orEmpty().isNotEmpty()
        if (loading && !working) {
            progress.isIndeterminate = true
            progress.visibility = View.VISIBLE
        } else if (working) {
            progress.isIndeterminate = false
            progress.setProgressCompat(viewModel.progress.value ?: 0, true)
            progress.visibility = View.VISIBLE
        } else {
            progress.visibility = View.GONE
        }
        readButton.isEnabled = !working && !loading && hasPages
        confirmButton.isEnabled = !working && ready
        playButton.isEnabled = !working && ready
        updatePlayButton()
        val pageEnabled = !working && !loading
        for (index in 0 until pageGrid.childCount) {
            val child = pageGrid.getChildAt(index)
            val remove = child.findViewById<View>(R.id.scan_page_remove)
            if (remove != null) {
                remove.isEnabled = pageEnabled
                remove.alpha = if (pageEnabled) 1f else 0.4f
            } else {
                child.isEnabled = pageEnabled
                child.alpha = if (pageEnabled) 1f else 0.4f
            }
        }
    }

    private fun updatePlayButton() {
        if (playing) {
            playButton.setImageResource(R.drawable.ic_stop_circle)
            playButton.setBackgroundResource(R.drawable.stop_button_background)
            playButton.contentDescription = getString(R.string.scan_stop)
        } else {
            playButton.setImageResource(R.drawable.ic_play)
            playButton.setBackgroundResource(R.drawable.play_button_background)
            playButton.contentDescription = getString(R.string.scan_play)
        }
    }

    private fun showCaptureMessage(text: String?) {
        val onCrop = viewModel.phase.value == ScanViewModel.Phase.ADJUST
        val active = if (onCrop) captureMessage else pagesMessage
        val inactive = if (onCrop) pagesMessage else captureMessage
        inactive.text = ""
        inactive.visibility = View.GONE
        active.text = text
        active.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun bindDropdown(
        field: MaterialAutoCompleteTextView,
        array: Int,
        initial: Int,
        onPick: (Int) -> Unit,
    ) {
        field.setAdapter(ArrayAdapter.createFromResource(this, array, android.R.layout.simple_list_item_1))
        selectDropdown(field, initial)
        field.setOnItemClickListener { _, _, position, _ ->
            if (suppressDropdown || flipper.displayedChild != PAGE_REVIEW) return@setOnItemClickListener
            onPick(position)
        }
    }

    private fun selectDropdown(field: MaterialAutoCompleteTextView, index: Int) {
        val label = field.adapter?.getItem(index.coerceAtLeast(0))?.toString().orEmpty()
        field.setText(label, false)
    }

    private fun selectedIndex(field: MaterialAutoCompleteTextView): Int {
        val text = field.text?.toString().orEmpty()
        val adapter = field.adapter ?: return 0
        for (index in 0 until adapter.count) {
            if (adapter.getItem(index)?.toString() == text) return index
        }
        return 0
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

    private companion object {
        const val PAGE_CROP = 0
        const val PAGE_GRID = 1
        const val PAGE_REVIEW = 2
    }
}
