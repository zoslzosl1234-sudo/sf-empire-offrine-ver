package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var selectModelBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var loadBtn: Button
    private lateinit var newGameBtn: Button

    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private var engineInitialized = false
    private var isModelReady = false
    private var currentModelPath: String? = null

    private val messages = mutableListOf<Message>()
    private val rawAssistant = StringBuilder()
    private var lastUiRenderMs = 0L
    private val messageAdapter = MessageAdapter(messages)

    private lateinit var coreRules: String
    private lateinit var fullRules: String
    private var needsCoreInjection = true
    private var needsResumeContext = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Back press ignored during local inference") }

        statusTv = findViewById(R.id.status)
        messagesRv = findViewById(R.id.messages)
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        selectModelBtn = findViewById(R.id.select_model)
        saveBtn = findViewById(R.id.save_game)
        loadBtn = findViewById(R.id.load_game)
        newGameBtn = findViewById(R.id.new_game)

        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter

        coreRules = readAssetText("sf_empire_core_rules.txt")
        fullRules = readAssetText("sf_empire_rules.txt")
        restoreAutosave()
        refreshButtons()

        selectModelBtn.setOnClickListener { getContent.launch(arrayOf("*/*")) }
        saveBtn.setOnClickListener { saveSnapshot() }
        loadBtn.setOnClickListener { loadSnapshot() }
        newGameBtn.setOnClickListener { startNewGame() }
        userActionFab.setOnClickListener { handleUserInput() }

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            engineInitialized = true

            val stableState = engine.state.first {
                it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
            }

            val savedPath = prefs.getString(KEY_MODEL_PATH, null)

            when {
                stableState is InferenceEngine.State.ModelReady -> {
                    currentModelPath = savedPath
                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        needsCoreInjection = true
                        if (messages.isNotEmpty()) needsResumeContext = true
                        setStatus("로컬 모델 준비 완료 · 완전 오프라인")
                        setIdleUi()
                    }
                }

                savedPath != null && File(savedPath).exists() -> {
                    currentModelPath = savedPath
                    loadModel(File(savedPath), auto = true)
                }

                else -> {
                    if (stableState is InferenceEngine.State.Error) {
                        runCatching { engine.cleanUp() }
                    }
                    withContext(Dispatchers.Main) {
                        setStatus("GGUF 모델을 선택하세요. 모든 추론은 기기 안에서 실행됩니다.")
                        refreshButtons()
                    }
                }
            }
        }
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedModel(it) }
    }

    private fun handleSelectedModel(uri: Uri) {
        if (!engineInitialized) {
            toast("AI 엔진 초기화 중입니다. 잠시 후 다시 시도하세요.")
            return
        }
        setBusyUi("GGUF 정보를 읽는 중…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                } ?: error("GGUF 파일을 읽지 못했습니다.")
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                withContext(Dispatchers.Main) { setStatus("모델 준비 중: $modelName") }
                val modelFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                } ?: error("모델 파일을 복사하지 못했습니다.")
                currentModelPath = modelFile.path
                prefs.edit().putString(KEY_MODEL_PATH, modelFile.path).apply()
                loadModel(modelFile, auto = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Model import failed", t)
                withContext(Dispatchers.Main) {
                    setStatus("모델 불러오기 실패: ${t.message}")
                    setIdleUi()
                }
            }
        }
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) = withContext(Dispatchers.IO) {
        File(ensureModelsDirectory(), modelName).also { file ->
            if (!file.exists() || file.length() == 0L) {
                withContext(Dispatchers.Main) { setStatus("모델을 앱 저장공간으로 복사 중… 처음 한 번은 오래 걸립니다.") }
                FileOutputStream(file).use { input.copyTo(it) }
            }
        }
    }

    private suspend fun loadModel(modelFile: File, auto: Boolean) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { setBusyUi("로컬 AI 모델 로딩 중…") }

            val stableState = engine.state.first {
                it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
            }

            if (stableState is InferenceEngine.State.ModelReady) {
                if (auto) {
                    currentModelPath = modelFile.path
                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        needsCoreInjection = true
                        if (messages.isNotEmpty()) needsResumeContext = true
                        setStatus("로컬 모델 자동 복구 완료 · 완전 오프라인")
                        setIdleUi()
                    }
                    return@withContext
                } else {
                    engine.cleanUp()
                }
            } else if (stableState is InferenceEngine.State.Error) {
                engine.cleanUp()
            }

            engine.loadModel(modelFile.path)
            currentModelPath = modelFile.path

            withContext(Dispatchers.Main) {
                isModelReady = true
                needsCoreInjection = true
                if (messages.isNotEmpty()) needsResumeContext = true
                setStatus(if (auto) "로컬 모델 자동 로드 완료 · 완전 오프라인" else "로컬 모델 준비 완료 · 완전 오프라인")
                setIdleUi()
                if (messages.isEmpty()) {
                    addAssistant(
                        "모델이 준비되었습니다. ‘새 게임’을 누르면 SF 우주 제국 시뮬레이션을 시작합니다.",
                        persist = false
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Model load failed", t)
            withContext(Dispatchers.Main) {
                isModelReady = engine.state.value is InferenceEngine.State.ModelReady
                if (isModelReady) {
                    setStatus("로컬 모델 준비 완료 · 완전 오프라인")
                } else {
                    setStatus("모델 로딩 실패: ${t.message}")
                }
                setIdleUi()
            }
        }
    }

    private suspend fun resetLoadedModel(path: String) = withContext(Dispatchers.IO) {
        val stableState = engine.state.first {
            it is InferenceEngine.State.Initialized ||
            it is InferenceEngine.State.ModelReady ||
            it is InferenceEngine.State.Error
        }

        if (
            stableState is InferenceEngine.State.ModelReady ||
            stableState is InferenceEngine.State.Error
        ) {
            engine.cleanUp()
        }

        engine.loadModel(path)
    }

    private fun startNewGame() {
        if (!isModelReady) {
            toast("먼저 GGUF 모델을 선택하세요.")
            return
        }

        generationJob?.cancel()
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        saveAutosave()
        needsCoreInjection = true
        needsResumeContext = false
        userInputEt.text = null

        // v0.2.3: 새 게임 때 수 GB 모델을 다시 언로드/로드하지 않는다.
        sendGameMessage(
            "새 캠페인을 시작한다. 이전 게임 내용은 무시하고 종족 선택 단계만 진행한다.",
            displayUser = false
        )
    }

    private fun handleUserInput() {
        if (!isModelReady) {
            toast("먼저 GGUF 모델을 선택하세요.")
            return
        }
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) return
        userInputEt.text = null
        sendGameMessage(userMsg, displayUser = true)
    }

    private fun sendGameMessage(userMsg: String, displayUser: Boolean) {
        if (generationJob?.isActive == true) return
        if (displayUser) addMessage(Message(UUID.randomUUID().toString(), userMsg, true))
        rawAssistant.clear()
        lastUiRenderMs = 0L
        addMessage(Message(UUID.randomUUID().toString(), "", false), persist = false)
        setBusyUi("GM이 응답 생성 중…")

        val modelPrompt = buildModelPrompt(userMsg)
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            engine.sendUserPrompt(modelPrompt)
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        needsCoreInjection = false
                        needsResumeContext = false
                        val visible = stripHiddenThinking(rawAssistant.toString()).trim()
                        replaceLastAssistant(if (visible.isBlank()) "응답을 생성하지 못했습니다. 같은 행동을 다시 입력해주세요." else visible)
                        saveAutosave()
                        setIdleUi()
                    }
                }
                .collect { token ->
                    rawAssistant.append(token)

                    val now = System.currentTimeMillis()
                    if (now - lastUiRenderMs >= 180L) {
                        lastUiRenderMs = now
                        val visible = stripHiddenThinking(rawAssistant.toString())
                        withContext(Dispatchers.Main) {
                            replaceLastAssistant(visible)
                        }
                    }
                }
        }
    }

    private fun buildModelPrompt(userMsg: String): String {
        val out = StringBuilder()

        if (needsCoreInjection) {
            out.append(
                """
/no_think
너는 'SF 우주 제국 시뮬레이션 v3.2'의 한국어 GM이다.
모바일 로컬 모델이므로 아래 핵심 규칙만 우선 적용한다.

- 확정된 설정과 수치를 임의로 바꾸지 않는다.
- 게임은 2200년에 시작한다.
- 시작은 반드시 한 단계씩 진행한다.
- 첫 단계는 9종족 선택이다: 인류, 사이보그, 노바인, 드라칸, 실리카, 테리안, 아쿠아리, 바이오닉, 제로니안.
- 종족 선택 후 국가명/정치체계, 시작 행성, 추가 특성 6포인트, 최종 확인 순으로 진행한다.
- 선택하지 않은 8종족은 독립 AI 문명으로 존재하지만 미접촉 정보는 공개하지 않는다.
- 성간항행 이전에는 다른 태양계로 이동하거나 타 문명과 직접 외교/무역/전쟁할 수 없다.
- 실제 행동이 끝난 경우에만 시간과 턴을 진행한다.
- 단순 질문은 턴을 넘기지 않는다.
- 선택지가 필요한 경우 5개를 제시하고 자유 행동도 허용한다.
- 내부 사고과정, <think>, <analysis>는 절대 출력하지 않는다.
- 짧고 읽기 쉬운 한국어로 답한다.
- 게임 시작 시 지금은 종족 선택만 출력하고 다음 단계로 미리 넘어가지 않는다.
""".trimIndent()
            )
            out.append("\n\n")
        }

        if (needsResumeContext && messages.isNotEmpty()) {
            out.append("[최근 확정 기록]\n")
            messages.takeLast(4).forEach { m ->
                out.append(if (m.isUser) "플레이어: " else "GM: ")
                    .append(m.content.take(700))
                    .append('\n')
            }
            out.append('\n')
        }

        if (!needsCoreInjection) {
            val details = relevantRuleSnippet(userMsg).take(1800)
            if (details.isNotBlank()) {
                out.append("[관련 세부 규칙]\n")
                    .append(details)
                    .append("\n\n")
            }
        }

        out.append("[플레이어 입력]\n").append(userMsg)
        out.append("\n\n/no_think\n최종 게임 본문만 한국어로 출력한다.")

        return out.toString()
    }

    private fun relevantRuleSnippet(input: String): String {
        val q = input.lowercase()
        val titles = mutableListOf<String>()
        fun hit(vararg words: String) = words.any { q.contains(it) }
        if (hit("종족", "인류", "사이보그", "노바인", "드라칸", "실리카", "테리안", "아쿠아리", "바이오닉", "제로니안")) titles += listOf("3. 종족 체계", "4. 종족 추가 특성")
        if (hit("정부", "정치", "공화국", "독재", "기업", "집단의식", "제정", "신정", "연방")) titles += "5. 정치체계"
        if (hit("항행", "ftl", "성간", "탐사", "첫 접촉", "외계")) titles += listOf("5-C. 항행 기술과 성간 진출", "5-D. 첫 접촉과 미발견 문명")
        if (hit("인구", "식량", "성장")) titles += "6. 인구 시스템"
        if (hit("행성", "식민", "적합도", "지구", "광물", "철광석", "티타늄", "수소", "에너지")) titles += listOf("7. 행성 유형", "9. 종족-행성 적합도 판정", "10. 행성 지구와 경제 생산")
        if (hit("함선", "함대", "전투", "전쟁", "조선", "건조")) titles += listOf("12. 함선 규모등급과 함종", "13. 함선 전투력·건조·유지 기준", "14. 함선 건조 조건")
        if (hit("연구", "기술", "rp", "초공간", "고대기술")) titles += "15. 연구 시스템"
        if (hit("외교", "관계", "동맹", "협상", "무역")) titles += "16. 외교 시스템"
        if (hit("안정", "반란", "치안")) titles += "18. 안정도·치안"
        if (hit("네메시스", "기계의 부상", "2250")) titles += "19. 메인 이벤트"
        if (hit("상태창", "현재 상태", "현황")) titles += "23. 상태창"
        if (titles.isEmpty()) return ""
        return titles.distinct().joinToString("\n\n") { extractSection(it) }.take(1800)
    }

    private fun extractSection(titleNeedle: String): String {
        val lines = fullRules.lines()
        var start = -1
        for (i in lines.indices) {
            if (lines[i].contains(titleNeedle, ignoreCase = true)) { start = i; break }
        }
        if (start < 0) return ""
        val out = StringBuilder()
        for (i in start until lines.size) {
            if (i > start && lines[i].startsWith("====") && out.isNotEmpty()) break
            out.append(lines[i]).append('\n')
        }
        return out.toString().trim()
    }

    private fun stripHiddenThinking(raw: String): String {
        var s = raw
        s = s.replace(Regex("(?is)<think>.*?</think>"), "")
        s = s.replace(Regex("(?is)<analysis>.*?</analysis>"), "")
        val thinkOpen = s.indexOf("<think>", ignoreCase = true)
        if (thinkOpen >= 0) s = s.substring(0, thinkOpen)
        val analysisOpen = s.indexOf("<analysis>", ignoreCase = true)
        if (analysisOpen >= 0) s = s.substring(0, analysisOpen)
        return s.replace(Regex("(?is)</?(think|analysis)>"), "").trimStart()
    }

    private fun addAssistant(text: String, persist: Boolean = true) = addMessage(Message(UUID.randomUUID().toString(), text, false), persist)

    private fun addMessage(message: Message, persist: Boolean = true) {
        messages.add(message)
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
        if (persist) saveAutosave()
    }

    private fun replaceLastAssistant(content: String) {
        if (messages.isEmpty() || messages.last().isUser) return
        messages[messages.lastIndex] = messages.last().copy(content = content)
        messageAdapter.notifyItemChanged(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
    }

    private fun saveSnapshot() {
        prefs.edit().putString(KEY_SLOT, serializeMessages()).apply()
        toast("게임을 저장했습니다.")
    }

    private fun loadSnapshot() {
        val json = prefs.getString(KEY_SLOT, null)
        if (json.isNullOrBlank()) { toast("저장된 게임이 없습니다."); return }
        restoreMessages(json)
        needsCoreInjection = true
        needsResumeContext = true
        val path = currentModelPath
        if (isModelReady && path != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) { setBusyUi("세이브 복구 중…") }
                    resetLoadedModel(path)
                    withContext(Dispatchers.Main) { setIdleUi(); toast("세이브를 불러왔습니다.") }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) { setIdleUi(); toast("세이브 복구 중 모델 초기화 실패") }
                }
            }
        } else toast("세이브를 불러왔습니다. 모델을 선택하세요.")
    }

    private fun saveAutosave() {
        prefs.edit().putString(KEY_AUTOSAVE, serializeMessages()).apply()
    }

    private fun restoreAutosave() {
        val json = prefs.getString(KEY_AUTOSAVE, null) ?: return
        restoreMessages(json)
        needsResumeContext = messages.isNotEmpty()
    }

    private fun serializeMessages(): String {
        val arr = JSONArray()
        messages.takeLast(MAX_SAVED_MESSAGES).forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("content", m.content).put("isUser", m.isUser))
        }
        return arr.toString()
    }

    private fun restoreMessages(json: String) {
        try {
            val arr = JSONArray(json)
            messages.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(Message(o.optString("id", UUID.randomUUID().toString()), o.optString("content"), o.optBoolean("isUser")))
            }
            messageAdapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) messagesRv.scrollToPosition(messages.lastIndex)
            saveAutosave()
        } catch (t: Throwable) {
            Log.e(TAG, "Restore failed", t)
            toast("세이브 파일을 읽지 못했습니다.")
        }
    }

    private fun readAssetText(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    private fun setBusyUi(status: String) {
        setStatus(status)
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        selectModelBtn.isEnabled = false
        saveBtn.isEnabled = false
        loadBtn.isEnabled = false
        newGameBtn.isEnabled = false
    }

    private fun setIdleUi() {
        userInputEt.isEnabled = isModelReady
        userActionFab.isEnabled = isModelReady
        refreshButtons()
    }

    private fun refreshButtons() {
        selectModelBtn.isEnabled = generationJob?.isActive != true
        saveBtn.isEnabled = messages.isNotEmpty() && generationJob?.isActive != true
        loadBtn.isEnabled = !prefs.getString(KEY_SLOT, null).isNullOrBlank() && generationJob?.isActive != true
        newGameBtn.isEnabled = isModelReady && generationJob?.isActive != true
    }

    private fun setStatus(text: String) { statusTv.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun ensureModelsDirectory() = File(filesDir, DIRECTORY_MODELS).also {
        if (it.exists() && !it.isDirectory) it.delete()
        if (!it.exists()) it.mkdir()
    }

    override fun onStop() {
        saveAutosave()
        super.onStop()
    }

    override fun onDestroy() {
        generationJob?.cancel()
        // InferenceEngine is a process-wide singleton.
        // Do not destroy it here because Android may recreate the Activity
        // while the process is still alive, leaving the singleton in ModelReady.
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
        private const val PREFS = "sf_empire_offline"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_SLOT = "save_slot_1"
        private const val MAX_SAVED_MESSAGES = 80
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> basic.name?.let { name -> basic.sizeLabel?.let { size -> "$name-$size" } ?: name }
    architecture?.architecture != null -> architecture?.architecture?.let { arch -> basic.uuid?.let { uuid -> "$arch-$uuid" } ?: "$arch-${System.currentTimeMillis()}" }
    else -> "model-${System.currentTimeMillis()}"
}
